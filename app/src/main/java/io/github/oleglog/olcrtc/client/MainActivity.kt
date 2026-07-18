package io.github.oleglog.olcrtc.client

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import io.github.oleglog.olcrtc.client.databinding.ActivityMainBinding
import io.github.oleglog.olcrtc.client.routing.RoutingSettings
import io.github.oleglog.olcrtc.client.subscription.SubscriptionRefresher
import io.github.oleglog.olcrtc.client.vpn.ConnectionStage
import io.github.oleglog.olcrtc.client.vpn.IOlcrtcVpnService
import io.github.oleglog.olcrtc.client.vpn.IVpnStateCallback
import io.github.oleglog.olcrtc.client.vpn.OlcrtcVpnService
import io.github.oleglog.olcrtc.client.vpn.VpnState

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    @Volatile private var vpn: IOlcrtcVpnService? = null
    private var bound = false
    private var pendingProfileId: Long? = null
    private var pendingSubscriptionProfileId: String? = null
    private var pendingImport: String? = null
    private var importListener: ((String) -> Unit)? = null
    private var stateListener: ((VpnState, String?, ConnectionStage, Int) -> Unit)? = null
    private var lastVpnState = VpnState.NO_PROFILE
    private var lastVpnError: String? = null
    private var lastConnectionStage = ConnectionStage.IDLE
    private var lastReconnectAttempt = 0
    private var hasVpnState = false
    private var backgroundEffects = RoutingSettings.BackgroundEffects()

    private val callback = object : IVpnStateCallback.Stub() {
        override fun onStateChanged(state: Int, error: String?, stage: Int, reconnectAttempt: Int) {
            runOnUiThread {
                dispatchVpnState(
                    VpnState.entries.getOrNull(state) ?: VpnState.ERROR,
                    error,
                    ConnectionStage.fromOrdinal(stage),
                    reconnectAttempt,
                )
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val remote = IOlcrtcVpnService.Stub.asInterface(service)
            vpn = remote
            remote.registerCallback(callback)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpn = null
        }
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            pendingProfileId?.let(::startVpn)
            pendingSubscriptionProfileId?.let(::startSubscriptionVpn)
        } else {
            pendingProfileId = null
            pendingSubscriptionProfileId = null
            dispatchVpnState(VpnState.ERROR, getString(R.string.vpn_permission_denied), ConnectionStage.IDLE, 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pendingProfileId = savedInstanceState?.getLong(KEY_PENDING_PROFILE_ID)?.takeIf { it > 0 }
        pendingSubscriptionProfileId = savedInstanceState?.getString(KEY_PENDING_SUBSCRIPTION_PROFILE_ID)
            ?.takeIf(String::isNotBlank)
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        binding.bottomNavigation.setupWithNavController(navHost.navController)
        refreshBackgroundEffects()
        acceptExternalIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptExternalIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        refreshBackgroundEffects()
        bound = bindService(Intent(this, OlcrtcVpnService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        binding.backgroundEffects.setActive(false)
        if (bound) {
            runCatching { vpn?.unregisterCallback(callback) }
            unbindService(connection)
            bound = false
            vpn = null
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingProfileId?.let { outState.putLong(KEY_PENDING_PROFILE_ID, it) }
        pendingSubscriptionProfileId?.let { outState.putString(KEY_PENDING_SUBSCRIPTION_PROFILE_ID, it) }
        super.onSaveInstanceState(outState)
    }

    fun setImportListener(listener: ((String) -> Unit)?) {
        importListener = listener
        if (listener != null) consumeExternalImport()?.let(listener)
    }

    fun setVpnStateListener(listener: ((VpnState, String?, ConnectionStage, Int) -> Unit)?) {
        stateListener = listener
        if (listener != null && hasVpnState) {
            listener(lastVpnState, lastVpnError, lastConnectionStage, lastReconnectAttempt)
        }
    }

    private fun dispatchVpnState(
        state: VpnState,
        error: String?,
        stage: ConnectionStage,
        reconnectAttempt: Int,
    ) {
        lastVpnState = state
        lastVpnError = error
        lastConnectionStage = stage
        lastReconnectAttempt = reconnectAttempt
        hasVpnState = true
        updateBackgroundEffects()
        stateListener?.invoke(state, error, stage, reconnectAttempt)
    }

    fun refreshBackgroundEffects() {
        backgroundEffects = RoutingSettings.open(applicationContext).getBackgroundEffects()
        binding.backgroundEffects.configure(backgroundEffects)
        binding.backgroundEffects.isVisible = backgroundEffects.enabled
        updateBackgroundEffects()
    }

    private fun updateBackgroundEffects() {
        val vpnActive = lastVpnState == VpnState.CONNECTED || lastVpnState == VpnState.RECONNECTING
        binding.backgroundEffects.setActive(backgroundEffectsActive(backgroundEffects, vpnActive))
    }

    fun stopVpn() {
        runCatching { vpn?.stop() }
    }

    fun activeProfileReference(): String? = runCatching { vpn?.activeProfileReference }.getOrNull()

    fun currentVpnState(): VpnState = runCatching {
        vpn?.state?.let { VpnState.entries.getOrNull(it) }
    }.getOrNull() ?: lastVpnState

    fun currentVpnError(): String? = lastVpnError

    fun trafficSnapshot(): LongArray? = runCatching { vpn?.trafficSnapshot }.getOrNull()

    fun testConnectionLatency(): Long = requireNotNull(vpn) { "VPN service is not connected" }.testConnectionLatency()

    internal fun refreshSubscription(subscriptionId: Long): SubscriptionRefresher.Result? {
        val values = vpn?.refreshSubscription(subscriptionId) ?: return null
        require(values.size >= 4) { "Invalid subscription refresh result" }
        return SubscriptionRefresher.Result(
            success = values[0] == 1,
            added = values[1],
            removed = values[2],
            total = values[3],
            source = values.getOrNull(4)?.let { SubscriptionRefresher.Source.fromWireCode(it) },
        )
    }

    fun requestVpnPermission(profileId: Long) {
        pendingProfileId = profileId
        pendingSubscriptionProfileId = null
        val intent = VpnService.prepare(this)
        if (intent == null) startVpn(profileId) else vpnPermission.launch(intent)
    }

    fun requestSubscriptionVpnPermission(profileId: String) {
        pendingProfileId = null
        pendingSubscriptionProfileId = profileId
        val intent = VpnService.prepare(this)
        if (intent == null) startSubscriptionVpn(profileId) else vpnPermission.launch(intent)
    }

    fun consumeExternalImport(): String? = pendingImport.also { pendingImport = null }

    private fun acceptExternalIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val raw = intent.dataString ?: return
        val scheme = intent.data?.scheme?.lowercase()
        when {
            raw.length > MAX_EXTERNAL_IMPORT_CHARS -> Toast.makeText(this, R.string.external_import_too_large, Toast.LENGTH_LONG).show()
            scheme !in EXTERNAL_PROFILE_SCHEMES -> Toast.makeText(this, R.string.invalid_profile, Toast.LENGTH_LONG).show()
            else -> {
                pendingImport = raw
                if (::binding.isInitialized) binding.bottomNavigation.selectedItemId = R.id.connectionFragment
                importListener?.invoke(raw)?.also { pendingImport = null }
            }
        }
    }

    private fun startVpn(profileId: Long) {
        pendingProfileId = null
        val service = Intent(this, OlcrtcVpnService::class.java)
        ContextCompat.startForegroundService(
            this,
            Intent(service)
                .setAction(OlcrtcVpnService.ACTION_START)
                .putExtra(OlcrtcVpnService.EXTRA_PROFILE_ID, profileId),
        )
    }

    private fun startSubscriptionVpn(profileId: String) {
        pendingSubscriptionProfileId = null
        val service = Intent(this, OlcrtcVpnService::class.java)
        ContextCompat.startForegroundService(
            this,
            Intent(service)
                .setAction(OlcrtcVpnService.ACTION_START)
                .putExtra(OlcrtcVpnService.EXTRA_SUBSCRIPTION_PROFILE_ID, profileId),
        )
    }

    companion object {
        private const val KEY_PENDING_PROFILE_ID = "pending_profile_id"
        private const val KEY_PENDING_SUBSCRIPTION_PROFILE_ID = "pending_subscription_profile_id"
        private const val MAX_EXTERNAL_IMPORT_CHARS = 16 * 1024
        private val EXTERNAL_PROFILE_SCHEMES = setOf("olcrtc", "vless", "vmess", "trojan")
    }
}

internal fun backgroundEffectsActive(
    settings: RoutingSettings.BackgroundEffects,
    vpnActive: Boolean,
): Boolean = settings.enabled && (settings.always || vpnActive)
