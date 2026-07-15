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
import android.net.VpnService
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.UserManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ProfileConfig
import io.github.oleglog.olcrtc.client.data.ProfileRepository
import io.github.oleglog.olcrtc.client.routing.DnsEndpoint
import io.github.oleglog.olcrtc.client.routing.GeoAssetManager
import io.github.oleglog.olcrtc.client.routing.PerAppPolicy
import io.github.oleglog.olcrtc.client.routing.RoutingPolicy
import io.github.oleglog.olcrtc.client.routing.RoutingSettings
import io.github.oleglog.olcrtc.client.subscription.SubscriptionHttpClient
import io.github.oleglog.olcrtc.client.subscription.SubscriptionRefresher
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
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
    private lateinit var connectivity: ConnectivityManager
    private var activeProfile: ProfileReference? = null
    private var activeProfileInfo: ProfileInfo? = null
    @Volatile private var activeSocksPort: Int? = null
    private var nativeSession: NativeSession? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var networkReconnectRequested = false
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
        connectivity = getSystemService(ConnectivityManager::class.java)
        GomobileCore.setProtector(::protect)
        createNotificationChannel()
        activeNetwork = connectivity.activeNetwork
        connectivity.registerDefaultNetworkCallback(networkCallback)
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
            requestNetworkReconnect(null)
            return
        }
        startForeground(NOTIFICATION_ID, notification())
        transition(VpnState.PREPARING)
        try {
            transition(VpnState.CONNECTING)
            nativeSession = startNativeSession(profile)
            transition(VpnState.CONNECTED)
            refreshStaleSubscriptions()
        } catch (error: Throwable) {
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
        val socksPort = activeSocksPort ?: return
        subscriptionRefresh.execute {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", socksPort))
            SubscriptionRefresher(
                profiles,
                userHttp = SubscriptionHttpClient(proxy = proxy),
                strictHttp = SubscriptionHttpClient(proxy = proxy),
            ).refreshStale()
        }
    }

    private fun handleNetworkAvailable(network: Network) {
        val previous = activeNetwork
        activeNetwork = network
        if (previous == network) return
        requestNetworkReconnect(if (previous == null) 0 else reconnectBackoff.nextDelayMillis())
    }

    private fun handleNetworkLost(network: Network) {
        if (activeNetwork != network) return
        activeNetwork = null
        requestNetworkReconnect(null)
    }

    private fun requestNetworkReconnect(delayMillis: Long?) {
        if (activeProfile == null || publishedState !in RECONNECTABLE_STATES) return
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
            cancelAutomaticReconnect()
            refreshStaleSubscriptions()
        } catch (error: Throwable) {
            runCatching { closeNativeSession() }
            handleConnectionFailure(error)
        }
    }

    private fun handleConnectionFailure(error: Throwable) {
        if (isFatalReconnectError(error)) {
            cancelAutomaticReconnect()
            persistVpnIntent(false, activeProfile ?: persistedProfileReference())
            transition(VpnState.ERROR, error.message ?: error.javaClass.simpleName)
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        networkReconnectRequested = true
        if (publishedState != VpnState.RECONNECTING) transition(VpnState.RECONNECTING)
        scheduleAutomaticReconnect()
    }

    private fun cancelAutomaticReconnect() {
        networkReconnectRequested = false
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        reconnectBackoff.reset()
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

    private fun establishTun(dns: DnsEndpoint): TunDescriptor {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(VPN_MTU)
            .addAddress(VPN_IPV4_ADDRESS, VPN_IPV4_PREFIX)
            .addAddress(VPN_IPV6_ADDRESS, VPN_IPV6_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(dns.address)
        applyPerAppPolicy(builder, routingSettings.getPerAppPolicy())
        val descriptor = builder.establish()
            ?: error("failed to establish VPN interface")
        return object : TunDescriptor {
            override val fd = descriptor.fd
            override fun close() = descriptor.close()
        }
    }

    private fun applyPerAppPolicy(builder: Builder, policy: PerAppPolicy) {
        if (policy.mode == PerAppPolicy.Mode.ALL) return

        var applied = 0
        policy.packages.sorted().forEach { packageName ->
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
            socket.receive(packet)
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
                    notificationStateText(),
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

        data class Local(val value: Long) : ProfileReference {
            override val displayId = value.toString()
        }

        data class Subscription(val value: String) : ProfileReference {
            override val displayId = value
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
        private val RECONNECTABLE_STATES = setOf(
            VpnState.PREPARING,
            VpnState.CONNECTING,
            VpnState.CONNECTED,
            VpnState.RECONNECTING,
        )
    }
}
