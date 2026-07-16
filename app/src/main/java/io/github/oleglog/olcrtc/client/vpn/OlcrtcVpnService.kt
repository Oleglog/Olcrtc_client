package io.github.oleglog.olcrtc.client.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.UserManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ProfileConfig
import io.github.oleglog.olcrtc.client.data.ProfileRepository
import io.github.oleglog.olcrtc.client.diagnostics.DiagnosticsLogStore
import io.github.oleglog.olcrtc.client.routing.DnsEndpoint
import io.github.oleglog.olcrtc.client.routing.GeoAssetManager
import io.github.oleglog.olcrtc.client.routing.PerAppPolicy
import io.github.oleglog.olcrtc.client.routing.RoutingPolicy
import io.github.oleglog.olcrtc.client.routing.RoutingSettings
import io.github.oleglog.olcrtc.client.statistics.ConnectionSessionRepository
import io.github.oleglog.olcrtc.client.subscription.SubscriptionHttpClient
import io.github.oleglog.olcrtc.client.subscription.SubscriptionRefresher
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

class OlcrtcVpnService : VpnService() {
    private val callbacks = RemoteCallbackList<IVpnStateCallback>()
    private val commands = Executors.newSingleThreadScheduledExecutor()
    private val subscriptionRefresh = Executors.newSingleThreadExecutor()
    private val stateMachine = VpnStateMachine()
    private val reconnectBackoff = ReconnectBackoff()

    @Volatile
    private var publishedState = VpnState.NO_PROFILE
    private lateinit var profiles: ProfileRepository
    private lateinit var routingSettings: RoutingSettings
    private lateinit var connectionSessions: ConnectionSessionRepository
    private lateinit var diagnostics: DiagnosticsLogStore
    private lateinit var connectivity: ConnectivityManager
    private var activeProfile: ProfileReference? = null
    private var activeProfileInfo: ProfileInfo? = null
    private var activeSessionId: Long? = null
    private var lastTrafficCounters = TrafficCounters()
    private var lastTrafficSampleAt = 0L
    private var trafficSpeedText = ""
    private var notificationTicker: ScheduledFuture<*>? = null
    @Volatile private var activeSocksPort: Int? = null
    private var nativeSession: NativeSession? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var networkReconnectRequested = false
    private var reconnectAttemptCount = 0
    @Volatile private var activeNetwork: Network? = null

    private val userUnlockedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_UNLOCKED) {
                commands.execute { restoreDesiredVpn() }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            commands.execute { handleNetworkAvailable(network) }
        }

        override fun onLost(network: Network) {
            commands.execute { handleNetworkLost(network) }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            commands.execute { handleNetworkAvailable(network) }
        }
    }

    private val binder = object : IOlcrtcVpnService.Stub() {
        override fun start(profileId: Long) {
            commands.execute {
                if (profileId > 0) {
                    val reference = ProfileReference.Local(profileId)
                    persistVpnIntent(true, reference)
                    startProfile(reference)
                } else {
                    transitionToError("profileId must be positive")
                }
            }
        }

        override fun startSubscriptionProfile(profileId: String?) {
            commands.execute {
                if (!profileId.isNullOrBlank()) {
                    val reference = ProfileReference.Subscription(profileId)
                    persistVpnIntent(true, reference)
                    startProfile(reference)
                } else {
                    transitionToError("profileId must not be blank")
                }
            }
        }

        override fun stop() {
            commands.execute {
                persistVpnIntent(false, activeProfile ?: persistedProfileReference())
                stopVpn()
            }
        }

        override fun reconnect() {
            commands.execute(::reconnectVpn)
        }

        override fun refreshSubscription(subscriptionId: Long): IntArray {
            require(subscriptionId > 0) { "subscriptionId must be positive" }
            val result = subscriptionRefresher().refreshWithChanges(subscriptionId)
            return intArrayOf(if (result.success) 1 else 0, result.added, result.removed, result.total)
        }

        override fun getState(): Int = publishedState.ordinal

        override fun registerCallback(callback: IVpnStateCallback) {
            callbacks.register(callback)
            try {
                callback.onStateChanged(publishedState.ordinal, null)
            } catch (_: RemoteException) {
                callbacks.unregister(callback)
            }
        }

        override fun unregisterCallback(callback: IVpnStateCallback) {
            callbacks.unregister(callback)
        }
    }

    override fun onCreate() {
        super.onCreate()
        profiles = ProfileRepository.open(this)
        routingSettings = RoutingSettings.open(this)
        connectionSessions = ConnectionSessionRepository.open(this)
        diagnostics = DiagnosticsLogStore.open(this)
        diagnostics.prune()
        connectivity = getSystemService(ConnectivityManager::class.java)
        GomobileCore.setProtector(::protect)
        GomobileCore.setLogWriter { diagnostics.append("info", "olcRTC core: $it") }
        createNotificationChannel()
        activeNetwork = connectivity.activeNetwork?.takeIf(::isUnderlyingNetwork)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            connectivity.registerBestMatchingNetworkCallback(request, networkCallback, Handler(mainLooper))
        } else {
            connectivity.requestNetwork(request, networkCallback)
        }
        ContextCompat.registerReceiver(
            this,
            userUnlockedReceiver,
            IntentFilter(Intent.ACTION_USER_UNLOCKED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == SERVICE_INTERFACE) super.onBind(intent) else binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, notification())
                val subscriptionProfileId = intent.getStringExtra(EXTRA_SUBSCRIPTION_PROFILE_ID)
                val localProfileId = intent.getLongExtra(EXTRA_PROFILE_ID, 0)
                val profile = when {
                    !subscriptionProfileId.isNullOrBlank() -> ProfileReference.Subscription(subscriptionProfileId)
                    localProfileId > 0 -> ProfileReference.Local(localProfileId)
                    else -> null
                }
                if (profile != null) {
                    commands.execute {
                        persistVpnIntent(true, profile)
                        startProfile(profile)
                    }
                } else {
                    commands.execute {
                        transitionToError("profileId is missing")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> commands.execute {
                persistVpnIntent(false, activeProfile ?: persistedProfileReference())
                stopVpn()
            }
            ACTION_RECONNECT -> commands.execute(::reconnectVpn)
            ACTION_TOGGLE -> {
                startForeground(NOTIFICATION_ID, notification())
                commands.execute(::toggleDesiredVpn)
            }
            else -> commands.execute { restoreDesiredVpn() }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        commands.execute {
            persistVpnIntent(false, activeProfile ?: persistedProfileReference())
            stopVpn()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        networkReconnectRequested = false
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        runCatching { unregisterReceiver(userUnlockedReceiver) }
        activeNetwork = null
        stopNotificationTicker()
        finishConnectionSession("service destroyed")
        activeSocksPort = null
        runCatching { nativeSession?.close() }
        nativeSession = null
        commands.shutdownNow()
        subscriptionRefresh.shutdownNow()
        callbacks.kill()
        super.onDestroy()
    }

    private fun toggleDesiredVpn() {
        val desired = routingSettings.getVpnIntent()
        if (desired.desiredConnected) {
            persistVpnIntent(false, activeProfile ?: persistedProfileReference(desired))
            stopVpn()
        } else {
            restoreDesiredVpn(desired.copy(desiredConnected = true))
        }
    }

    private fun restoreDesiredVpn(intent: RoutingSettings.VpnIntent = routingSettings.getVpnIntent()) {
        if (activeProfile != null || publishedState !in setOf(VpnState.NO_PROFILE, VpnState.DISCONNECTED)) {
            return
        }
        if (!getSystemService(UserManager::class.java).isUserUnlocked) {
            return
        }
        if (!intent.desiredConnected) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val reference = persistedProfileReference(intent)
        if (reference == null) {
            persistVpnIntent(false, null)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        persistVpnIntent(true, reference)
        startForeground(NOTIFICATION_ID, notification())
        startProfile(reference)
    }

    private fun persistedProfileReference(
        intent: RoutingSettings.VpnIntent = routingSettings.getVpnIntent(),
    ): ProfileReference? = when {
        intent.localProfileId != null -> ProfileReference.Local(intent.localProfileId)
        intent.subscriptionProfileId != null -> ProfileReference.Subscription(intent.subscriptionProfileId)
        else -> null
    }

    private fun persistVpnIntent(desiredConnected: Boolean, reference: ProfileReference?) {
        val intent = when (reference) {
            is ProfileReference.Local -> RoutingSettings.VpnIntent(
                desiredConnected = desiredConnected,
                localProfileId = reference.value,
            )
            is ProfileReference.Subscription -> RoutingSettings.VpnIntent(
                desiredConnected = desiredConnected,
                subscriptionProfileId = reference.value,
            )
            null -> RoutingSettings.VpnIntent(desiredConnected = desiredConnected)
        }
        routingSettings.setVpnIntent(intent)
    }

    private fun startProfile(reference: ProfileReference) {
        cancelAutomaticReconnect()
        if (publishedState == VpnState.NO_PROFILE) transition(VpnState.DISCONNECTED)
        if (!stateMachine.canStart()) return

        val profile = try {
            loadProfile(reference) ?: error("profile ${reference.displayId} not found")
        } catch (error: Throwable) {
            activeProfile = null
            activeProfileInfo = null
            persistVpnIntent(false, reference)
            transitionToError(error.message ?: error.javaClass.simpleName)
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        activeProfile = reference
        activeProfileInfo = ProfileInfo.from(profile)
        if (activeNetwork == null) {
            startForeground(NOTIFICATION_ID, notification())
            transition(VpnState.PREPARING)
            networkReconnectRequested = true
            transition(VpnState.RECONNECTING)
            return
        }
        startForeground(NOTIFICATION_ID, notification())
        transition(VpnState.PREPARING)
        try {
            transition(VpnState.CONNECTING)
            nativeSession = startNativeSession(profile)
            transition(VpnState.CONNECTED)
            startConnectionSession(reference)
            refreshStaleSubscriptions()
        } catch (error: Throwable) {
            diagnostics.append("error", "VPN start failed for ${reference.displayId}", error)
            handleConnectionFailure(error)
        }
    }

    private fun reconnectVpn() {
        val reference = activeProfile ?: return
        when (publishedState) {
            VpnState.CONNECTED -> {
                cancelAutomaticReconnect()
                transition(VpnState.RECONNECTING)
                networkReconnectRequested = true
                if (activeNetwork == null) {
                    runCatching { closeNativeSession() }
                } else {
                    scheduleAutomaticReconnect(0)
                }
            }
            VpnState.RECONNECTING -> {
                cancelAutomaticReconnect()
                networkReconnectRequested = true
                if (activeNetwork != null) scheduleAutomaticReconnect(0)
            }
            VpnState.DISCONNECTED, VpnState.ERROR -> startProfile(reference)
            else -> Unit
        }
    }

    private fun loadProfile(reference: ProfileReference): ProfileConfig? = when (reference) {
        is ProfileReference.Local -> profiles.get(reference.value)
        is ProfileReference.Subscription -> profiles.getSubscriptionProfile(reference.value)
    }

    private fun refreshStaleSubscriptions() {
        subscriptionRefresh.execute {
            subscriptionRefresher().refreshStale()
        }
    }

    private fun subscriptionRefresher(): SubscriptionRefresher {
        val socksPort = activeSocksPort ?: return SubscriptionRefresher(profiles)
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", socksPort))
        return SubscriptionRefresher(
            profiles,
            userHttp = SubscriptionHttpClient(proxy = proxy),
            strictHttp = SubscriptionHttpClient(),
        )
    }

    private fun handleNetworkAvailable(network: Network) {
        val eligible = isUnderlyingNetwork(network)
        when (
            underlyingNetworkChange(
                current = activeNetwork,
                candidate = network,
                available = true,
                eligible = eligible,
            )
        ) {
            UnderlyingNetworkChange.KEEP -> return
            UnderlyingNetworkChange.LOST -> return
            UnderlyingNetworkChange.REPLACE -> {
                val previous = activeNetwork
                activeNetwork = network
                diagnostics.append("info", "Underlying network changed")
                requestNetworkReconnect(
                    if (previous == null) 0
                    else reconnectBackoff.nextDelayMillis(),
                )
            }
        }
    }

    private fun handleNetworkLost(network: Network) {
        if (
            underlyingNetworkChange(
                current = activeNetwork,
                candidate = network,
                available = false,
                eligible = false,
            ) != UnderlyingNetworkChange.LOST
        ) return
        activeNetwork = null
        diagnostics.append("info", "Underlying network lost")
        requestNetworkReconnect(null)
    }

    private fun isUnderlyingNetwork(network: Network): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private fun requestNetworkReconnect(delayMillis: Long?) {
        if (activeProfile == null || publishedState !in RECONNECTABLE_STATES) return
        // Ignore capability callbacks until the initial native session has started.
        if (nativeSession == null && (publishedState == VpnState.PREPARING || publishedState == VpnState.CONNECTING)) return
        networkReconnectRequested = true
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        runCatching { closeNativeSession() }
        if (publishedState != VpnState.RECONNECTING) transition(VpnState.RECONNECTING)
        if (delayMillis != null) scheduleAutomaticReconnect(delayMillis)
    }

    private fun scheduleAutomaticReconnect(delayMillis: Long = reconnectBackoff.nextDelayMillis()) {
        if (!networkReconnectRequested || activeNetwork == null || reconnectFuture != null) return
        reconnectFuture = commands.schedule({
            reconnectFuture = null
            automaticReconnect()
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun automaticReconnect() {
        val reference = activeProfile ?: return cancelAutomaticReconnect()
        if (!networkReconnectRequested || activeNetwork == null) return
        try {
            closeNativeSession()
            val profile = loadProfile(reference) ?: error("profile ${reference.displayId} not found")
            nativeSession = startNativeSession(profile)
            transition(VpnState.CONNECTED)
            startConnectionSession(reference)
            cancelAutomaticReconnect()
            refreshStaleSubscriptions()
        } catch (error: Throwable) {
            diagnostics.append("error", "Automatic reconnect failed for ${reference.displayId}", error)
            runCatching { closeNativeSession() }
            handleConnectionFailure(error)
        }
    }

    private fun handleConnectionFailure(error: Throwable) {
        if (isFatalReconnectError(error) || reconnectAttemptCount >= MAX_RECONNECT_ATTEMPTS) {
            cancelAutomaticReconnect()
            finishConnectionSession(error.message ?: error.javaClass.simpleName)
            persistVpnIntent(false, activeProfile ?: persistedProfileReference())
            transition(VpnState.ERROR, error.message ?: error.javaClass.simpleName)
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        networkReconnectRequested = true
        reconnectAttemptCount++
        if (publishedState != VpnState.RECONNECTING) transition(VpnState.RECONNECTING)
        scheduleAutomaticReconnect()
    }

    private fun cancelAutomaticReconnect() {
        networkReconnectRequested = false
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        reconnectBackoff.reset()
        reconnectAttemptCount = 0
    }

    private fun isFatalReconnectError(error: Throwable): Boolean =
        error is IllegalArgumentException ||
            error.message?.startsWith("profile ") == true ||
            GomobileCore.isFatalError(error)

    private fun stopVpn() {
        cancelAutomaticReconnect()
        when (publishedState) {
            VpnState.PREPARING,
            VpnState.CONNECTING,
            VpnState.CONNECTED,
            VpnState.RECONNECTING,
            VpnState.ERROR,
            -> {
                transition(VpnState.STOPPING)
                try {
                    finishConnectionSession("user stopped")
                    closeNativeSession()
                    transition(if (activeProfile == null) VpnState.NO_PROFILE else VpnState.DISCONNECTED)
                } catch (error: Throwable) {
                    transition(VpnState.ERROR, error.message ?: error.javaClass.simpleName)
                }
            }
            else -> Unit
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startConnectionSession(reference: ProfileReference) {
        if (activeSessionId != null) return
        val profile = activeProfileInfo ?: return
        activeSessionId = connectionSessions.start(
            profileId = reference.sessionId,
            profileName = profile.name,
            protocol = profile.protocol,
            networkType = currentNetworkType(),
        )
    }

    private fun finishConnectionSession(reason: String?) {
        val sessionId = activeSessionId ?: return
        activeSessionId = null
        val counters = runCatching { nativeSession?.trafficCounters() ?: TrafficCounters() }.getOrDefault(TrafficCounters())
        runCatching {
            connectionSessions.finish(
                id = sessionId,
                reason = reason,
                bytesUp = counters.bytesUp,
                bytesDown = counters.bytesDown,
            )
        }
    }

    private fun currentNetworkType(): String {
        val capabilities = connectivity.getNetworkCapabilities(activeNetwork) ?: return "unknown"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    private fun establishTun(dns: DnsEndpoint): TunDescriptor {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(VPN_MTU)
            .addAddress(VPN_IPV4_ADDRESS, VPN_IPV4_PREFIX)
            .addAddress(VPN_IPV6_ADDRESS, VPN_IPV6_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(dns.address)
        activeNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
        applyPerAppPolicy(builder, routingSettings.getPerAppPolicy())
        val descriptor = builder.establish()
            ?: error("failed to establish VPN interface")
        return object : TunDescriptor {
            override val fd = descriptor.fd
            override fun close() = descriptor.close()
        }
    }

    private fun applyPerAppPolicy(builder: Builder, policy: PerAppPolicy) {
        val packages = policy.packagesWithVpnAppExcluded(packageName)
        if (policy.mode == PerAppPolicy.Mode.ALL) {
            builder.addDisallowedApplication(packageName)
            return
        }

        var applied = 0
        packages.sorted().forEach { packageName ->
            try {
                when (policy.mode) {
                    PerAppPolicy.Mode.EXCLUDE_SELECTED -> builder.addDisallowedApplication(packageName)
                    PerAppPolicy.Mode.ONLY_SELECTED -> builder.addAllowedApplication(packageName)
                    PerAppPolicy.Mode.ALL -> Unit
                }
                applied++
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        require(policy.mode != PerAppPolicy.Mode.ONLY_SELECTED || applied > 0) {
            "No selected applications are installed"
        }
    }

    private fun startNativeSession(profile: ProfileConfig): NativeSession {
        val routingRules = profiles.getEnabledRoutingRules()
        val requestedRoutingPolicy = routingSettings.get()
        val geoAssets = GeoAssetManager(this).prepare().getOrNull()
        val routingPolicy = if (
            requestedRoutingPolicy.preset == RoutingPolicy.Preset.RUSSIA_DIRECT && geoAssets == null
        ) {
            requestedRoutingPolicy.copy(preset = RoutingPolicy.Preset.ALL_VPN)
        } else {
            requestedRoutingPolicy
        }
        val profileDns = when (profile) {
            is ProfileConfig.Olcrtc -> profile.value.dnsServer
            is ProfileConfig.Standard -> profile.value.dnsServer
        }
        val dns = DnsEndpoint.parse(profileDns ?: routingSettings.getDnsServer() ?: DnsEndpoint.DEFAULT)
        val xraySocksPort: Int
        val xrayConfig: String
        val olcrtcConfig: NativeOlcrtcConfig?
        when (profile) {
            is ProfileConfig.Olcrtc -> {
                olcrtcConfig = NativeOlcrtcConfig.from(profile.value, freeLoopbackPort(), dns)
                xraySocksPort = freeLoopbackPort(olcrtcConfig.socksPort)
                xrayConfig = NativeConfig.xray(
                    socksPort = xraySocksPort,
                    olcrtcSocksPort = olcrtcConfig.socksPort,
                    dns = dns,
                    routingRules = routingRules,
                    routingPolicy = routingPolicy,
                )
            }
            is ProfileConfig.Standard -> {
                olcrtcConfig = null
                xraySocksPort = freeLoopbackPort()
                xrayConfig = NativeConfig.xray(
                    socksPort = xraySocksPort,
                    profile = profile.value,
                    dns = dns,
                    routingRules = routingRules,
                    routingPolicy = routingPolicy,
                )
            }
        }
        return NativeSession(
            nativeCore = GomobileCore,
            hevTunnel = HevTunnel(),
            establishTun = { establishTun(dns) },
            verifyDatapath = { verifyDatapath(dns) },
            reportStage = { message, error ->
                diagnostics.append(
                    if (error == null) "info" else "error",
                    message,
                    error,
                )
            },
        ).also { session ->
            session.start(
                socksPort = xraySocksPort,
                assetDirectory = geoAssets?.absolutePath ?: noBackupFilesDir.absolutePath,
                xrayConfig = xrayConfig,
                hevConfig = NativeConfig.hev(xraySocksPort),
                olcrtcConfig = olcrtcConfig,
            )
            activeSocksPort = xraySocksPort
        }
    }

    private fun verifyDatapath(dns: DnsEndpoint) {
        val queryId = ThreadLocalRandom.current().nextInt(0x10000)
        val query = byteArrayOf(
            (queryId ushr 8).toByte(), queryId.toByte(),
            1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 'a'.code.toByte(), 'n'.code.toByte(), 'd'.code.toByte(), 'r'.code.toByte(),
            'o'.code.toByte(), 'i'.code.toByte(), 'd'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0, 0, 1, 0, 1,
        )
        DatagramSocket().use { socket ->
            socket.soTimeout = DATAPATH_TIMEOUT_MILLIS
            socket.connect(InetAddress.getByName(dns.address), dns.port)
            socket.send(DatagramPacket(query, query.size))
            val response = ByteArray(512)
            val packet = DatagramPacket(response, response.size)
            try {
                socket.receive(packet)
            } catch (error: SocketTimeoutException) {
                throw IllegalStateException(
                    "VPN datapath timed out",
                    error,
                )
            }
            check(
                packet.length >= 12 &&
                    response[0] == query[0] &&
                    response[1] == query[1] &&
                    response[2].toInt() and 0x80 != 0
            ) {
                "invalid VPN datapath response"
            }
        }
    }

    private fun closeNativeSession() {
        activeSocksPort = null
        val session = nativeSession
        nativeSession = null
        session?.close()
    }

    private fun transitionToError(error: String) {
        transition(VpnState.ERROR, error)
    }

    private fun transition(state: VpnState, error: String? = null) {
        stateMachine.transition(state)
        publishedState = state
        if (::diagnostics.isInitialized) {
            diagnostics.append(
                if (state == VpnState.ERROR) "error" else "info",
                "VPN state: $state${error?.let { ": $it" } ?: ""}",
            )
        }
        if (state == VpnState.CONNECTED) startNotificationTicker() else if (state != VpnState.RECONNECTING) stopNotificationTicker()
        updateNotification()
        VpnTileService.update(this, state)
        val count = callbacks.beginBroadcast()
        try {
            repeat(count) { index ->
                try {
                    callbacks.getBroadcastItem(index).onStateChanged(state.ordinal, error)
                } catch (_: RemoteException) {
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun startNotificationTicker() {
        if (notificationTicker != null) return
        lastTrafficCounters = runCatching { nativeSession?.trafficCounters() ?: TrafficCounters() }.getOrDefault(TrafficCounters())
        lastTrafficSampleAt = System.currentTimeMillis()
        notificationTicker = commands.scheduleAtFixedRate({ sampleTrafficAndNotify() }, 1, 1, TimeUnit.SECONDS)
    }

    private fun stopNotificationTicker() {
        notificationTicker?.cancel(false)
        notificationTicker = null
        trafficSpeedText = ""
    }

    private fun sampleTrafficAndNotify() {
        if (publishedState != VpnState.CONNECTED) return
        val now = System.currentTimeMillis()
        val counters = runCatching { nativeSession?.trafficCounters() ?: lastTrafficCounters }.getOrDefault(lastTrafficCounters)
        val elapsedMillis = (now - lastTrafficSampleAt).coerceAtLeast(1)
        val upPerSecond = ((counters.bytesUp - lastTrafficCounters.bytesUp).coerceAtLeast(0) * 1000) / elapsedMillis
        val downPerSecond = ((counters.bytesDown - lastTrafficCounters.bytesDown).coerceAtLeast(0) * 1000) / elapsedMillis
        lastTrafficCounters = counters
        lastTrafficSampleAt = now
        trafficSpeedText = getString(
            R.string.vpn_notification_speed,
            formatBytesPerSecond(upPerSecond),
            formatBytesPerSecond(downPerSecond),
        )
        updateNotification()
    }

    private fun updateNotification() {
        if (publishedState != VpnState.NO_PROFILE && publishedState != VpnState.DISCONNECTED) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    private fun notification(): android.app.Notification {
        val profile = activeProfileInfo
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(profile?.name ?: getString(R.string.vpn_notification_title))
            .setContentText(
                if (profile == null) notificationStateText() else getString(
                    R.string.vpn_notification_profile,
                    profile.protocol,
                    listOf(notificationStateText(), trafficSpeedText).filter(String::isNotBlank).joinToString(" · "),
                ),
            )
            .setOngoing(true)
            .addAction(0, getString(R.string.vpn_notification_stop), stopPendingIntent())
        if (publishedState == VpnState.CONNECTED || publishedState == VpnState.ERROR) {
            builder.addAction(0, getString(R.string.vpn_notification_reconnect), reconnectPendingIntent())
        }
        return builder.build()
    }

    private fun notificationStateText(): String = getString(
        when (publishedState) {
            VpnState.NO_PROFILE, VpnState.DISCONNECTED -> R.string.vpn_notification_disconnected
            VpnState.PREPARING, VpnState.CONNECTING -> R.string.vpn_notification_connecting
            VpnState.CONNECTED -> R.string.vpn_notification_connected
            VpnState.RECONNECTING -> R.string.vpn_notification_reconnecting
            VpnState.STOPPING -> R.string.vpn_notification_stopping
            VpnState.ERROR -> R.string.vpn_notification_error
        },
    )

    private fun formatBytesPerSecond(bytesPerSecond: Long): String {
        val units = arrayOf("B/s", "KiB/s", "MiB/s", "GiB/s")
        var value = bytesPerSecond.coerceAtLeast(0).toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "${value.toLong()} ${units[unit]}" else "%.1f %s".format(Locale.US, value, units[unit])
    }

    private fun stopPendingIntent(): PendingIntent = servicePendingIntent(ACTION_STOP, STOP_REQUEST_CODE)

    private fun reconnectPendingIntent(): PendingIntent = servicePendingIntent(ACTION_RECONNECT, RECONNECT_REQUEST_CODE)

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, OlcrtcVpnService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.vpn_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private data class ProfileInfo(
        val name: String,
        val protocol: String,
    ) {
        companion object {
            fun from(profile: ProfileConfig): ProfileInfo = when (profile) {
                is ProfileConfig.Olcrtc -> ProfileInfo(
                    name = profile.value.name,
                    protocol = "olcRTC · ${profile.value.provider.value}",
                )
                is ProfileConfig.Standard -> ProfileInfo(
                    name = profile.value.name,
                    protocol = profile.value.protocol.name,
                )
            }
        }
    }

    private sealed interface ProfileReference {
        val displayId: String
        val sessionId: String

        data class Local(val value: Long) : ProfileReference {
            override val displayId = value.toString()
            override val sessionId = "local:$value"
        }

        data class Subscription(val value: String) : ProfileReference {
            override val displayId = value
            override val sessionId = "subscription:$value"
        }
    }

    companion object {
        const val ACTION_START = "io.github.oleglog.olcrtc.client.vpn.START"
        const val ACTION_STOP = "io.github.oleglog.olcrtc.client.vpn.STOP"
        const val ACTION_RECONNECT = "io.github.oleglog.olcrtc.client.vpn.RECONNECT"
        const val ACTION_TOGGLE = "io.github.oleglog.olcrtc.client.vpn.TOGGLE"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_SUBSCRIPTION_PROFILE_ID = "subscription_profile_id"
        const val NOTIFICATION_CHANNEL_ID = "vpn"
        const val NOTIFICATION_ID = 1
        private const val STOP_REQUEST_CODE = 1
        private const val RECONNECT_REQUEST_CODE = 2
        const val VPN_MTU = 1500
        const val VPN_IPV4_ADDRESS = "10.0.0.2"
        const val VPN_IPV4_PREFIX = 32
        const val VPN_IPV6_ADDRESS = "fd00::2"
        const val VPN_IPV6_PREFIX = 128
        const val DATAPATH_TIMEOUT_MILLIS = 10_000
        const val MAX_RECONNECT_ATTEMPTS = 3
        private val RECONNECTABLE_STATES = setOf(
            VpnState.PREPARING,
            VpnState.CONNECTING,
            VpnState.CONNECTED,
            VpnState.RECONNECTING,
        )
    }
}

internal enum class UnderlyingNetworkChange {
    KEEP,
    REPLACE,
    LOST,
}

internal fun <T> underlyingNetworkChange(
    current: T?,
    candidate: T,
    available: Boolean,
    eligible: Boolean,
): UnderlyingNetworkChange = when {
    available && eligible && current != candidate ->
        UnderlyingNetworkChange.REPLACE
    !available && current == candidate ->
        UnderlyingNetworkChange.LOST
    else -> UnderlyingNetworkChange.KEEP
}
