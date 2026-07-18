package io.github.oleglog.olcrtc.client.vpn

import android.annotation.SuppressLint
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
import android.os.PowerManager
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.SystemClock
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OlcrtcVpnService : VpnService() {
    private val callbacks = RemoteCallbackList<IVpnStateCallback>()
    private val commands = Executors.newSingleThreadScheduledExecutor()
    private val connectionStartup = Executors.newSingleThreadExecutor()
    private val subscriptionRefresh = Executors.newSingleThreadExecutor()
    private val healthChecks = Executors.newSingleThreadExecutor()
    private val stateMachine = VpnStateMachine()
    private val reconnectBackoff = ReconnectBackoff()

    @Volatile
    private var publishedState = VpnState.NO_PROFILE
    @Volatile private var publishedStage = ConnectionStage.IDLE
    @Volatile private var publishedError: String? = null
    private lateinit var profiles: ProfileRepository
    private lateinit var routingSettings: RoutingSettings
    private lateinit var connectionSessions: ConnectionSessionRepository
    private lateinit var diagnostics: DiagnosticsLogStore
    private lateinit var connectivity: ConnectivityManager
    private lateinit var geoAssets: GeoAssetManager
    @Volatile private var activeProfile: ProfileReference? = null
    private var activeProfileInfo: ProfileInfo? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeSessionId: Long? = null
    private var activeSessionBaseline = TrafficCounters()
    @Volatile private var lastTrafficCounters = TrafficCounters()
    private var lastTrafficSampleAt = 0L
    @Volatile private var lastUploadBytesPerSecond = 0L
    @Volatile private var lastDownloadBytesPerSecond = 0L
    private var trafficSpeedText = ""
    private var notificationTicker: ScheduledFuture<*>? = null
    @Volatile private var activeSocksPort: Int? = null
    @Volatile private var activeDnsEndpoint: DnsEndpoint? = null
    @Volatile private var nativeSession: NativeSession? = null
    private var connectionAttempt: ConnectionAttempt? = null
    private var connectionFuture: Future<*>? = null
    private var nextConnectionGeneration = 0L
    private var activeSessionGeneration = 0L
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var networkReconnectRequested = false
    @Volatile private var reconnectAttemptCount = 0
    private var healthProbeInFlight = false
    private var healthProbeFailures = 0
    private var healthProbeToken = 0L
    private var lastHealthProbeAt = 0L
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
            return intArrayOf(
                if (result.success) 1 else 0,
                result.added,
                result.removed,
                result.total,
                result.source?.wireCode ?: 0,
            )
        }

        override fun testConnectionLatency(): Long = measureConnectionLatency()

        override fun getTrafficSnapshot(): LongArray = longArrayOf(
            lastTrafficCounters.bytesUp,
            lastTrafficCounters.bytesDown,
            lastUploadBytesPerSecond,
            lastDownloadBytesPerSecond,
        )

        override fun getActiveProfileReference(): String? = activeProfile?.sessionId

        override fun getState(): Int = publishedState.ordinal

        override fun registerCallback(callback: IVpnStateCallback) {
            callbacks.register(callback)
            try {
                callback.onStateChanged(
                    publishedState.ordinal,
                    publishedError,
                    publishedStage.ordinal,
                    reconnectAttemptCount,
                )
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
        geoAssets = GeoAssetManager(this)
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
                acquireWakeLock()
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
                        releaseWakeLock()
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
        cancelConnectionAttempt("service destroyed")
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        runCatching { unregisterReceiver(userUnlockedReceiver) }
        activeNetwork = null
        releaseWakeLock()
        stopNotificationTicker()
        finishConnectionSession("service destroyed")
        activeSocksPort = null
        activeDnsEndpoint = null
        runCatching { nativeSession?.close() }
        nativeSession = null
        connectionStartup.shutdownNow()
        healthChecks.shutdownNow()
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
        if (publishedState == VpnState.NO_PROFILE) transition(VpnState.DISCONNECTED)
        if (publishedState == VpnState.CONNECTED && activeProfile == reference) return
        val switchingProfile = publishedState == VpnState.CONNECTED
        if (!switchingProfile && !stateMachine.canStart()) return
        val previousReference = activeProfile
        cancelAutomaticReconnect()
        cancelConnectionAttempt("new profile requested")
        publishedError = null

        publishStage(ConnectionStage.LOAD_PROFILE)
        val loadStartedAt = SystemClock.elapsedRealtime()
        val profile = try {
            loadProfile(reference) ?: error("profile ${reference.displayId} not found")
        } catch (error: Throwable) {
            logStageFailure(ConnectionStage.LOAD_PROFILE, loadStartedAt, error)
            if (switchingProfile) {
                persistVpnIntent(true, previousReference)
                publishedError = userConnectionError(error)
                notifyCallbacks()
                return
            }
            activeProfile = null
            activeProfileInfo = null
            releaseWakeLock()
            persistVpnIntent(false, reference)
            transitionToError(userConnectionError(error))
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        logStageReady(ConnectionStage.LOAD_PROFILE, loadStartedAt)

        activeProfile = reference
        activeProfileInfo = ProfileInfo.from(profile)
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, notification())
        if (switchingProfile) {
            finishConnectionSession("profile switched")
            transition(VpnState.RECONNECTING)
            runCatching { closeNativeSession() }
                .onFailure { diagnostics.append("error", "Full profile switch cleanup failed", it) }
            diagnostics.append(
                "info",
                "VPN profile switch ${previousReference?.displayId} -> ${reference.displayId} mode=full",
            )
            launchConnectionAttempt(reference, profile, reconnecting = true)
            return
        }
        transition(VpnState.PREPARING)
        if (activeNetwork == null) {
            networkReconnectRequested = true
            publishStage(ConnectionStage.WAIT_NETWORK)
            transition(VpnState.RECONNECTING)
            return
        }
        launchConnectionAttempt(reference, profile, reconnecting = false)
    }

    private fun reconnectVpn() {
        val reference = activeProfile ?: return
        when (publishedState) {
            VpnState.CONNECTED -> {
                cancelAutomaticReconnect()
                transition(VpnState.RECONNECTING)
                networkReconnectRequested = true
                cancelConnectionAttempt("manual reconnect")
                if (activeNetwork == null) {
                    runCatching { closeNativeSession() }
                } else {
                    runCatching { closeNativeSession() }
                    scheduleAutomaticReconnect(0)
                }
            }
            VpnState.RECONNECTING -> {
                cancelAutomaticReconnect()
                networkReconnectRequested = true
                cancelConnectionAttempt("manual reconnect")
                runCatching { closeNativeSession() }
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

    private fun launchConnectionAttempt(
        reference: ProfileReference,
        profile: ProfileConfig,
        reconnecting: Boolean,
    ) {
        val network = activeNetwork ?: run {
            networkReconnectRequested = true
            publishStage(ConnectionStage.WAIT_NETWORK)
            if (publishedState != VpnState.RECONNECTING) transition(VpnState.RECONNECTING)
            return
        }
        cancelConnectionAttempt("superseded")
        val attempt = ConnectionAttempt(
            generation = ++nextConnectionGeneration,
            reference = reference,
            network = network,
            startedAt = SystemClock.elapsedRealtime(),
        )
        connectionAttempt = attempt
        if (!reconnecting) transition(VpnState.CONNECTING)
        connectionFuture = connectionStartup.submit {
            val result = runCatching {
                attempt.requireActive()
                startNativeSession(profile, attempt)
            }
            runCatching {
                commands.execute { completeConnectionAttempt(attempt, result) }
            }.onFailure {
                result.getOrNull()?.session?.let { session -> runCatching { session.close() } }
            }
        }
    }

    private fun completeConnectionAttempt(
        attempt: ConnectionAttempt,
        result: Result<StartedSession>,
    ) {
        if (!shouldAcceptConnectionResult(connectionAttempt?.generation, attempt.generation, attempt.cancelled.get())) {
            result.getOrNull()?.session?.let { session -> runCatching { session.close() } }
            return
        }
        connectionAttempt = null
        connectionFuture = null
        if (activeNetwork != attempt.network) {
            result.getOrNull()?.session?.let { session -> runCatching { session.close() } }
            networkReconnectRequested = true
            publishStage(ConnectionStage.WAIT_NETWORK)
            if (publishedState != VpnState.RECONNECTING) transition(VpnState.RECONNECTING)
            if (activeNetwork != null) scheduleAutomaticReconnect(NETWORK_CHANGE_DEBOUNCE_MILLIS)
            return
        }
        result.onSuccess { started ->
            nativeSession = started.session
            activeSocksPort = started.socksPort
            activeDnsEndpoint = started.dns
            activeSessionGeneration = attempt.generation
            publishStage(ConnectionStage.READY)
            routingSettings.setLastSuccessfulProfileReference(attempt.reference.sessionId)
            transition(VpnState.CONNECTED)
            diagnostics.append(
                "info",
                "VPN connected attempt=${attempt.generation} total=${SystemClock.elapsedRealtime() - attempt.startedAt}ms",
            )
            startConnectionSession(attempt.reference)
            cancelAutomaticReconnect()
            refreshStaleSubscriptions()
        }.onFailure { error ->
            diagnostics.append("error", "VPN start failed for ${attempt.reference.displayId}", error)
            handleConnectionFailure(error)
        }
    }

    private fun cancelConnectionAttempt(reason: String) {
        val attempt = connectionAttempt ?: return
        connectionAttempt = null
        connectionFuture?.cancel(true)
        connectionFuture = null
        val startedAt = SystemClock.elapsedRealtime()
        attempt.cancelled.set(true)
        runCatching { attempt.session?.close() }
            .onFailure { diagnostics.append("error", "VPN startup cancellation failed", it) }
        diagnostics.append(
            "info",
            "VPN startup cancelled attempt=${attempt.generation} reason=$reason cleanup=${SystemClock.elapsedRealtime() - startedAt}ms",
        )
    }

    private fun refreshStaleSubscriptions() {
        subscriptionRefresh.execute {
            subscriptionRefresher().refreshStale()
        }
    }

    private fun subscriptionRefresher(): SubscriptionRefresher {
        val socksPort = activeSocksPort ?: return SubscriptionRefresher(profiles)
        val proxy = java.net.Proxy(
            java.net.Proxy.Type.SOCKS,
            java.net.InetSocketAddress.createUnresolved("127.0.0.1", socksPort),
        )
        return SubscriptionRefresher(
            profiles,
            userHttp = SubscriptionHttpClient(proxy = proxy),
            strictHttp = SubscriptionHttpClient(),
        )
    }

    private fun measureConnectionLatency(): Long {
        check(publishedState == VpnState.CONNECTED) { "VPN is not connected" }
        return GomobileCore.urlTest(CONNECTION_TEST_URL, CONNECTION_TEST_TIMEOUT_MILLIS).coerceAtLeast(1)
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
                reconnectBackoff.reset()
                reconnectAttemptCount = 0
                diagnostics.append("info", "Underlying network changed")
                requestNetworkReconnect(networkReconnectDelay(previous != null))
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
        networkReconnectRequested = true
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        cancelConnectionAttempt("underlying network changed")
        runCatching { closeNativeSession() }
        if (publishedState != VpnState.RECONNECTING) transition(VpnState.RECONNECTING)
        publishStage(if (activeNetwork == null) ConnectionStage.WAIT_NETWORK else ConnectionStage.LOAD_PROFILE)
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
        publishStage(ConnectionStage.LOAD_PROFILE)
        val loadStartedAt = SystemClock.elapsedRealtime()
        try {
            val profile = loadProfile(reference) ?: error("profile ${reference.displayId} not found")
            logStageReady(ConnectionStage.LOAD_PROFILE, loadStartedAt)
            launchConnectionAttempt(reference, profile, reconnecting = true)
        } catch (error: Throwable) {
            logStageFailure(ConnectionStage.LOAD_PROFILE, loadStartedAt, error)
            handleConnectionFailure(error)
        }
    }

    private fun handleConnectionFailure(error: Throwable) {
        if (error is CancellationException) return
        if (isFatalReconnectError(error)) {
            val userError = userConnectionError(error)
            cancelAutomaticReconnect()
            finishConnectionSession(error.message ?: error.javaClass.simpleName)
            persistVpnIntent(false, activeProfile ?: persistedProfileReference())
            transition(VpnState.ERROR, userError)
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        networkReconnectRequested = true
        reconnectAttemptCount++
        if (publishedState != VpnState.RECONNECTING) transition(VpnState.RECONNECTING)
        else notifyCallbacks()
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
        isFatalConnectionError(error, GomobileCore::isFatalError)

    private fun userConnectionError(error: Throwable): String {
        val message = error.message.orEmpty().lowercase(Locale.ROOT)
        return when {
            message.startsWith("profile ") || error is IllegalArgumentException ->
                getString(R.string.vpn_error_invalid_profile)
            "auth" in message || "authentication" in message ->
                getString(R.string.vpn_error_authentication)
            "timeout" in message || "timed out" in message ->
                getString(R.string.vpn_error_timeout)
            "no such host" in message || "network" in message || "unreachable" in message ->
                getString(R.string.vpn_error_network)
            else -> getString(R.string.vpn_error_generic)
        }
    }

    private fun stopVpn() {
        cancelAutomaticReconnect()
        cancelConnectionAttempt("VPN stopped")
        when (publishedState) {
            VpnState.PREPARING,
            VpnState.CONNECTING,
            VpnState.CONNECTED,
            VpnState.RECONNECTING,
            VpnState.ERROR,
            -> {
                publishStage(ConnectionStage.STOPPING)
                transition(VpnState.STOPPING)
                try {
                    finishConnectionSession("user stopped")
                    nativeSession?.releaseTun()
                    transition(if (activeProfile == null) VpnState.NO_PROFILE else VpnState.DISCONNECTED)
                    closeNativeSession()
                } catch (error: Throwable) {
                    diagnostics.append("error", "VPN stop failed", error)
                    transition(VpnState.ERROR, userConnectionError(error))
                }
            }
            else -> Unit
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, disconnectedNotification())
        releaseWakeLock()
        stopSelf()
    }

    private fun startConnectionSession(reference: ProfileReference) {
        if (activeSessionId != null) return
        val profile = activeProfileInfo ?: return
        activeSessionBaseline = runCatching { nativeSession?.trafficCounters() ?: TrafficCounters() }
            .getOrDefault(TrafficCounters())
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
        val baseline = activeSessionBaseline
        activeSessionBaseline = TrafficCounters()
        runCatching {
            connectionSessions.finish(
                id = sessionId,
                reason = reason,
                bytesUp = (counters.bytesUp - baseline.bytesUp).coerceAtLeast(0),
                bytesDown = (counters.bytesDown - baseline.bytesDown).coerceAtLeast(0),
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

    private fun establishTun(network: Network): TunDescriptor {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(VPN_MTU)
            .addAddress(VPN_IPV4_ADDRESS, VPN_IPV4_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(NativeConfig.VPN_DNS_ADDRESS)
        builder.setUnderlyingNetworks(arrayOf(network))
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

    private fun startNativeSession(
        profile: ProfileConfig,
        attempt: ConnectionAttempt,
    ): StartedSession {
        attempt.requireActive()
        val routingRules = profiles.getEnabledRoutingRules()
        val requestedRoutingPolicy = routingSettings.get()
        val preparedGeoAssets = if (requiresGeoAssets(requestedRoutingPolicy)) {
            reportAttemptStage(attempt, ConnectionStage.PREPARE_ASSETS, null, null)
            val startedAt = SystemClock.elapsedRealtime()
            geoAssets.prepare()
                .onSuccess { logStageReady(ConnectionStage.PREPARE_ASSETS, startedAt, attempt.generation) }
                .onFailure { logStageFailure(ConnectionStage.PREPARE_ASSETS, startedAt, it, attempt.generation) }
                .getOrNull()
        } else {
            null
        }
        val routingPolicy = if (
            requestedRoutingPolicy.preset == RoutingPolicy.Preset.RUSSIA_DIRECT && preparedGeoAssets == null
        ) {
            requestedRoutingPolicy.copy(preset = RoutingPolicy.Preset.ALL_VPN)
        } else {
            requestedRoutingPolicy
        }
        val dns = sessionDns(profile, routingSettings.getDnsServer())
        val assetDirectory = preparedGeoAssets?.absolutePath ?: noBackupFilesDir.absolutePath
        val reportStage: (ConnectionStage, Long?, Throwable?) -> Unit = { stage, elapsed, error ->
            reportAttemptStage(attempt, stage, elapsed, error)
        }
        val reportStop: (Long, Long) -> Unit = { routeReleased, total ->
            diagnostics.append(
                "info",
                "VPN stop attempt=${attempt.generation} routeReleased=${routeReleased}ms total=${total}ms",
            )
        }

        val xraySocksPort: Int
        val xrayConfig: String
        val olcrtcConfig: NativeOlcrtcConfig?
        when (profile) {
            is ProfileConfig.Olcrtc -> {
                olcrtcConfig = NativeOlcrtcConfig.from(profile.value, freeLoopbackPort(), checkNotNull(dns.carrier))
                xraySocksPort = freeLoopbackPort(olcrtcConfig.socksPort)
                xrayConfig = NativeConfig.xray(
                    socksPort = xraySocksPort,
                    olcrtcSocksPort = olcrtcConfig.socksPort,
                    dns = dns.tunnel,
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
                    dns = dns.tunnel,
                    routingRules = routingRules,
                    routingPolicy = routingPolicy,
                )
            }
        }
        val verifyDatapath = { verifyDatapath(xraySocksPort, dns.tunnel) }
        val session = NativeSession(
            nativeCore = GomobileCore,
            hevTunnel = HevTunnel(),
            establishTun = { establishTun(attempt.network) },
            verifyDatapath = verifyDatapath,
            reportStage = reportStage,
            reportStop = reportStop,
        )
        attempt.session = session
        try {
            attempt.requireActive()
            session.start(
                socksPort = xraySocksPort,
                assetDirectory = assetDirectory,
                xrayConfig = xrayConfig,
                hevConfig = NativeConfig.hev(xraySocksPort),
                olcrtcConfig = olcrtcConfig,
            )
            attempt.requireActive()
            return StartedSession(session, xraySocksPort, dns.tunnel)
        } catch (error: Throwable) {
            runCatching { session.close() }
            throw error
        }
    }

    private fun verifyDatapath(socksPort: Int, dns: DnsEndpoint) {
        try {
            queryDnsThroughTunnel(socksPort, dns, DATAPATH_TIMEOUT_MILLIS)
        } catch (error: SocketTimeoutException) {
            throw IllegalStateException(
                "VPN datapath timed out",
                error,
            )
        }
    }

    private fun queryDnsThroughTunnel(socksPort: Int, dns: DnsEndpoint, timeoutMillis: Int) {
        val queryId = ThreadLocalRandom.current().nextInt(0x10000)
        val query = byteArrayOf(
            (queryId ushr 8).toByte(), queryId.toByte(),
            1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 'a'.code.toByte(), 'n'.code.toByte(), 'd'.code.toByte(), 'r'.code.toByte(),
            'o'.code.toByte(), 'i'.code.toByte(), 'd'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0, 0, 1, 0, 1,
        )
        tunnelSocket(socksPort, dns, timeoutMillis).use { socket ->
            val output = DataOutputStream(socket.getOutputStream())
            output.writeShort(query.size)
            output.write(query)
            output.flush()
            val input = DataInputStream(socket.getInputStream())
            val response = ByteArray(input.readUnsignedShort().also { length ->
                require(length in 12..MAX_DNS_RESPONSE_BYTES) { "invalid VPN datapath response length" }
            })
            input.readFully(response)
            check(
                response[0] == query[0] &&
                    response[1] == query[1] &&
                    response[2].toInt() and 0x80 != 0
            ) {
                "invalid VPN datapath response"
            }
        }
    }

    private fun closeNativeSession() {
        val startedAt = SystemClock.elapsedRealtime()
        activeSocksPort = null
        activeDnsEndpoint = null
        activeSessionGeneration = 0L
        healthProbeFailures = 0
        healthProbeInFlight = false
        healthProbeToken++
        val session = nativeSession
        nativeSession = null
        session?.close()
        if (session != null) {
            diagnostics.append("info", "VPN native session stopped in ${SystemClock.elapsedRealtime() - startedAt}ms")
        }
    }

    private fun transitionToError(error: String) {
        transition(VpnState.ERROR, error)
    }

    private fun publishStage(stage: ConnectionStage) {
        publishedStage = stage
        updateNotification()
        notifyCallbacks()
    }

    private fun reportAttemptStage(
        attempt: ConnectionAttempt,
        stage: ConnectionStage,
        elapsedMillis: Long?,
        error: Throwable?,
    ) {
        when {
            elapsedMillis == null -> {
                diagnostics.append("info", "VPN stage $stage started attempt=${attempt.generation}")
                runCatching {
                    commands.execute {
                        if (connectionAttempt === attempt && !attempt.cancelled.get()) publishStage(stage)
                    }
                }
            }
            error == null -> diagnostics.append(
                "info",
                "VPN stage $stage ready attempt=${attempt.generation} elapsed=${elapsedMillis}ms",
            )
            else -> diagnostics.append(
                "error",
                "VPN stage $stage failed attempt=${attempt.generation} elapsed=${elapsedMillis}ms",
                error,
            )
        }
    }

    private fun logStageReady(stage: ConnectionStage, startedAt: Long, generation: Long? = null) {
        diagnostics.append(
            "info",
            "VPN stage $stage ready${generation?.let { " attempt=$it" }.orEmpty()} elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
        )
    }

    private fun logStageFailure(
        stage: ConnectionStage,
        startedAt: Long,
        error: Throwable,
        generation: Long? = null,
    ) {
        diagnostics.append(
            "error",
            "VPN stage $stage failed${generation?.let { " attempt=$it" }.orEmpty()} elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
            error,
        )
    }

    private fun transition(state: VpnState, error: String? = null) {
        stateMachine.transition(state)
        publishedState = state
        publishedError = error
        if (state == VpnState.NO_PROFILE || state == VpnState.DISCONNECTED) publishedStage = ConnectionStage.IDLE
        if (::diagnostics.isInitialized) {
            diagnostics.append(
                if (state == VpnState.ERROR) "error" else "info",
                "VPN state: $state${error?.let { ": $it" } ?: ""}",
            )
        }
        if (state == VpnState.CONNECTED) startNotificationTicker() else stopNotificationTicker()
        updateNotification()
        VpnTileService.update(this, state)
        notifyCallbacks()
    }

    private fun notifyCallbacks() {
        val count = callbacks.beginBroadcast()
        try {
            repeat(count) { index ->
                try {
                    callbacks.getBroadcastItem(index).onStateChanged(
                        publishedState.ordinal,
                        publishedError,
                        publishedStage.ordinal,
                        reconnectAttemptCount,
                    )
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
        lastHealthProbeAt = lastTrafficSampleAt
        notificationTicker = commands.scheduleAtFixedRate(
            { sampleTrafficAndNotify() },
            NOTIFICATION_SAMPLE_INTERVAL_SECONDS,
            NOTIFICATION_SAMPLE_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private fun stopNotificationTicker() {
        notificationTicker?.cancel(false)
        notificationTicker = null
        trafficSpeedText = ""
        lastUploadBytesPerSecond = 0
        lastDownloadBytesPerSecond = 0
    }

    private fun sampleTrafficAndNotify() {
        if (publishedState != VpnState.CONNECTED) return
        if (nativeSession?.isRunning() != true) {
            diagnostics.append("error", "Native VPN tunnel stopped while connected")
            requestNetworkReconnect(0)
            return
        }
        val now = System.currentTimeMillis()
        val counters = runCatching { nativeSession?.trafficCounters() ?: lastTrafficCounters }.getOrDefault(lastTrafficCounters)
        val elapsedMillis = (now - lastTrafficSampleAt).coerceAtLeast(1)
        val uploaded = (counters.bytesUp - lastTrafficCounters.bytesUp).coerceAtLeast(0)
        val downloaded = (counters.bytesDown - lastTrafficCounters.bytesDown).coerceAtLeast(0)
        val upPerSecond = (uploaded * 1000) / elapsedMillis
        val downPerSecond = (downloaded * 1000) / elapsedMillis
        lastUploadBytesPerSecond = upPerSecond
        lastDownloadBytesPerSecond = downPerSecond
        if (uploaded > 0 || downloaded > 0) {
            healthProbeFailures = 0
            lastHealthProbeAt = now
        }
        lastTrafficCounters = counters
        lastTrafficSampleAt = now
        trafficSpeedText = getString(
            R.string.vpn_notification_speed,
            formatBytesPerSecond(upPerSecond),
            formatBytesPerSecond(downPerSecond),
        )
        scheduleTunnelHealthProbe(now)
        updateNotification()
    }

    private fun scheduleTunnelHealthProbe(now: Long) {
        if (healthProbeInFlight || now - lastHealthProbeAt < TUNNEL_HEALTH_INTERVAL_MILLIS) return
        val session = nativeSession ?: return
        val socksPort = activeSocksPort ?: return
        val dns = activeDnsEndpoint ?: return
        val generation = activeSessionGeneration
        val token = ++healthProbeToken
        healthProbeInFlight = true
        lastHealthProbeAt = now
        healthChecks.execute {
            val failure = runCatching { probeTunnel(socksPort, dns) }.exceptionOrNull()
            runCatching {
                commands.execute healthResult@{
                    if (token != healthProbeToken) return@healthResult
                    healthProbeInFlight = false
                    if (
                        publishedState == VpnState.CONNECTED && nativeSession === session &&
                        activeSocksPort == socksPort && activeSessionGeneration == generation
                    ) {
                        if (failure == null) {
                            healthProbeFailures = 0
                        } else {
                            healthProbeFailures++
                            diagnostics.append(
                                "error",
                                "VPN tunnel health probe failed ($healthProbeFailures/$HEALTH_FAILURES_BEFORE_RECONNECT)",
                                failure,
                            )
                            if (shouldReconnectAfterHealthFailures(healthProbeFailures)) requestNetworkReconnect(0)
                        }
                    }
                }
            }
        }
    }

    private fun probeTunnel(socksPort: Int, dns: DnsEndpoint) {
        queryDnsThroughTunnel(socksPort, dns, TUNNEL_HEALTH_TIMEOUT_MILLIS)
    }

    private fun tunnelSocket(socksPort: Int, dns: DnsEndpoint, timeoutMillis: Int): Socket {
        val proxy = Proxy(
            Proxy.Type.SOCKS,
            InetSocketAddress.createUnresolved("127.0.0.1", socksPort),
        )
        val socket = Socket(proxy)
        try {
            socket.soTimeout = timeoutMillis
            socket.connect(
                InetSocketAddress.createUnresolved(dns.address, dns.port),
                timeoutMillis,
            )
            return socket
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
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
            .setOnlyAlertOnce(true)
            .setContentTitle(profile?.name ?: getString(R.string.vpn_notification_title))
            .setContentText(
                if (profile == null) notificationStateText() else getString(
                    R.string.vpn_notification_profile,
                    profile.protocol,
                    listOf(notificationStateText(), trafficSpeedText).filter(String::isNotBlank).joinToString(" · "),
                ),
            )
            .setOngoing(true)
            .setContentIntent(mainActivityPendingIntent())
        if (publishedState == VpnState.CONNECTED || publishedState == VpnState.ERROR) {
            builder.addAction(0, getString(R.string.vpn_notification_choose_profile), profileChooserPendingIntent())
        }
        if (publishedState == VpnState.CONNECTED || publishedState == VpnState.ERROR) {
            builder.addAction(0, getString(R.string.vpn_notification_reconnect), reconnectPendingIntent())
        }
        builder.addAction(0, getString(R.string.vpn_notification_disconnect), stopPendingIntent())
        return builder.build()
    }

    private fun disconnectedNotification(): android.app.Notification {
        val profile = activeProfileInfo
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(profile?.name ?: getString(R.string.vpn_notification_title))
            .setContentText(
                profile?.let {
                    getString(R.string.vpn_notification_profile, it.protocol, notificationStateText())
                } ?: notificationStateText(),
            )
            .setAutoCancel(true)
            .setContentIntent(mainActivityPendingIntent())
            .addAction(0, getString(R.string.vpn_notification_choose_profile), profileChooserPendingIntent())
            .addAction(0, getString(R.string.vpn_notification_connect), togglePendingIntent())
            .build()
    }

    private fun notificationStateText(): String = when (publishedState) {
        VpnState.NO_PROFILE, VpnState.DISCONNECTED -> getString(R.string.vpn_notification_disconnected)
        VpnState.PREPARING, VpnState.CONNECTING -> connectionStageText()
        VpnState.CONNECTED -> getString(R.string.vpn_notification_connected)
        VpnState.RECONNECTING -> if (reconnectAttemptCount > 0) {
            getString(R.string.vpn_reconnecting_attempt, reconnectAttemptCount)
        } else {
            connectionStageText()
        }
        VpnState.STOPPING -> getString(R.string.vpn_notification_stopping)
        VpnState.ERROR -> getString(R.string.vpn_notification_error)
    }

    private fun connectionStageText(): String = getString(
        when (publishedStage) {
            ConnectionStage.LOAD_PROFILE -> R.string.vpn_stage_load_profile
            ConnectionStage.WAIT_NETWORK -> R.string.vpn_stage_wait_network
            ConnectionStage.PREPARE_ASSETS -> R.string.vpn_stage_prepare_assets
            ConnectionStage.CREATE_TUN -> R.string.vpn_stage_create_tun
            ConnectionStage.START_CARRIER -> R.string.vpn_stage_start_carrier
            ConnectionStage.START_XRAY -> R.string.vpn_stage_start_xray
            ConnectionStage.START_HEV -> R.string.vpn_stage_start_hev
            ConnectionStage.VERIFY_DATAPATH -> R.string.vpn_stage_verify_datapath
            ConnectionStage.READY -> R.string.vpn_notification_connected
            ConnectionStage.STOPPING -> R.string.vpn_notification_stopping
            ConnectionStage.IDLE -> R.string.vpn_notification_connecting
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

    private fun togglePendingIntent(): PendingIntent = servicePendingIntent(ACTION_TOGGLE, TOGGLE_REQUEST_CODE)

    private fun mainActivityPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        MAIN_ACTIVITY_REQUEST_CODE,
        Intent(this, io.github.oleglog.olcrtc.client.MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun profileChooserPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        PROFILE_CHOOSER_REQUEST_CODE,
        Intent(this, ProfileChooserActivity::class.java)
            .putExtra(ProfileChooserActivity.EXTRA_ACTIVE_PROFILE, activeProfile?.sessionId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

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

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:vpn")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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

    private data class StartedSession(
        val session: NativeSession,
        val socksPort: Int,
        val dns: DnsEndpoint,
    )

    private class ConnectionAttempt(
        val generation: Long,
        val reference: ProfileReference,
        val network: Network,
        val startedAt: Long,
    ) {
        val cancelled = AtomicBoolean(false)
        @Volatile var session: NativeSession? = null

        fun requireActive() {
            if (cancelled.get()) throw CancellationException("connection attempt cancelled")
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
        private const val TOGGLE_REQUEST_CODE = 4
        private const val MAIN_ACTIVITY_REQUEST_CODE = 5
        private const val PROFILE_CHOOSER_REQUEST_CODE = 6
        const val VPN_MTU = 1500
        const val VPN_IPV4_ADDRESS = "10.0.0.2"
        const val VPN_IPV4_PREFIX = 32
        const val DATAPATH_TIMEOUT_MILLIS = 10_000
        const val MAX_DNS_RESPONSE_BYTES = 65_535
        private const val CONNECTION_TEST_URL = "https://www.google.com/generate_204"
        private const val CONNECTION_TEST_TIMEOUT_MILLIS = 5_000
        private const val NOTIFICATION_SAMPLE_INTERVAL_SECONDS = 5L
        private const val TUNNEL_HEALTH_INTERVAL_MILLIS = 60_000L
        private const val TUNNEL_HEALTH_TIMEOUT_MILLIS = 30_000
        private val RECONNECTABLE_STATES = setOf(
            VpnState.PREPARING,
            VpnState.CONNECTING,
            VpnState.CONNECTED,
            VpnState.RECONNECTING,
        )
    }
}

internal fun requiresGeoAssets(policy: RoutingPolicy): Boolean =
    policy.preset == RoutingPolicy.Preset.RUSSIA_DIRECT

internal fun shouldReconnectAfterHealthFailures(failures: Int): Boolean =
    failures >= HEALTH_FAILURES_BEFORE_RECONNECT

internal const val HEALTH_FAILURES_BEFORE_RECONNECT = 2

internal fun networkReconnectDelay(replacingExistingNetwork: Boolean): Long =
    if (replacingExistingNetwork) NETWORK_CHANGE_DEBOUNCE_MILLIS else 0L

internal const val NETWORK_CHANGE_DEBOUNCE_MILLIS = 400L

internal fun shouldAcceptConnectionResult(
    currentGeneration: Long?,
    resultGeneration: Long,
    cancelled: Boolean,
): Boolean = !cancelled && currentGeneration == resultGeneration

internal fun isFatalConnectionError(
    error: Throwable,
    nativeFatal: (Throwable) -> Boolean,
): Boolean = error is IllegalArgumentException ||
    error.message?.startsWith("profile ") == true ||
    nativeFatal(error)

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
