package io.github.oleglog.olcrtc.client

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.oleglog.olcrtc.client.databinding.ActivityMainBinding
import io.github.oleglog.olcrtc.client.importer.SubscriptionDeepLinkParser
import io.github.oleglog.olcrtc.client.routing.RoutingSettings
import io.github.oleglog.olcrtc.client.subscription.SubscriptionRefresher
import io.github.oleglog.olcrtc.client.updater.ApkUpdateInstaller
import io.github.oleglog.olcrtc.client.updater.GitHubRelease
import io.github.oleglog.olcrtc.client.updater.GitHubUpdateClient
import io.github.oleglog.olcrtc.client.updater.UpdateCheckResult
import io.github.oleglog.olcrtc.client.updater.UpdateInstallAction
import io.github.oleglog.olcrtc.client.updater.shouldRunAutomaticUpdateCheck
import io.github.oleglog.olcrtc.client.updater.shouldShowUpdatePrompt
import io.github.oleglog.olcrtc.client.updater.updateInstallAction
import io.github.oleglog.olcrtc.client.vpn.ConnectionStage
import io.github.oleglog.olcrtc.client.vpn.IOlcrtcVpnService
import io.github.oleglog.olcrtc.client.vpn.IVpnStateCallback
import io.github.oleglog.olcrtc.client.vpn.OlcrtcVpnService
import io.github.oleglog.olcrtc.client.vpn.VpnState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var pendingInstall: PendingInstall? = null
    private var pendingUpdatePrompt: UpdateCheckResult? = null
    private var updateInstallInProgress = false
    private var updateCheckInProgress = false
    private var lastAutomaticUpdateCheckElapsed = 0L
    private val updatePreferences by lazy { getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE) }
    private val systemPreferences by lazy { getSharedPreferences(SYSTEM_PREFERENCES, Context.MODE_PRIVATE) }

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

    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val pending = pendingInstall ?: return@registerForActivityResult
        if (ApkUpdateInstaller(applicationContext).canRequestPackageInstalls()) {
            pendingInstall = null
            installUpdate(pending.release, pending.asset)
        } else {
            pendingInstall = null
            Toast.makeText(this, R.string.settings_update_install_permission, Toast.LENGTH_LONG).show()
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
        if (showBatteryOptimizationRecommendation()) return
        if (!showPendingUpdatePrompt()) checkForUpdateOnEntry()
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
        stateListener?.invoke(state, error, stage, reconnectAttempt)
    }

    fun refreshBackgroundEffects() {
        backgroundEffects = RoutingSettings.open(applicationContext).getBackgroundEffects()
        binding.backgroundEffects.configure(backgroundEffects)
        binding.backgroundEffects.isVisible = backgroundEffects.enabled
        binding.backgroundEffects.setActive(backgroundEffects.enabled)
    }

    private fun showBatteryOptimizationRecommendation(): Boolean {
        val ignoringOptimizations = getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
        val promptHandled = systemPreferences.getBoolean(KEY_BATTERY_PROMPT_HANDLED, false)
        if (!shouldShowBatteryOptimizationPrompt(ignoringOptimizations, promptHandled)) return false
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_optimization_prompt_title)
            .setMessage(R.string.battery_optimization_prompt_message)
            .setNegativeButton(R.string.battery_optimization_prompt_never) { _, _ ->
                markBatteryOptimizationPromptHandled()
            }
            .setPositiveButton(R.string.battery_optimization_prompt_open) { _, _ ->
                markBatteryOptimizationPromptHandled()
                openBatteryOptimizationSettings()
            }
            .show()
        return true
    }

    private fun markBatteryOptimizationPromptHandled() {
        systemPreferences.edit().putBoolean(KEY_BATTERY_PROMPT_HANDLED, true).apply()
    }

    internal fun openBatteryOptimizationSettings() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, R.string.battery_optimization_already_disabled, Toast.LENGTH_LONG).show()
            return
        }
        val packageUri = Uri.parse("package:$packageName")
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))
        }.recoverCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }.onFailure {
            Toast.makeText(this, R.string.settings_system_screen_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun checkForUpdateOnEntry() {
        val now = SystemClock.elapsedRealtime()
        if (!shouldRunAutomaticUpdateCheck(
                checkInProgress = updateCheckInProgress,
                lastCheckElapsedMillis = lastAutomaticUpdateCheckElapsed,
                nowElapsedMillis = now,
                minimumIntervalMillis = AUTOMATIC_UPDATE_CHECK_INTERVAL_MILLIS,
            )
        ) return
        updateCheckInProgress = true
        lastAutomaticUpdateCheckElapsed = now
        lifecycleScope.launch {
            val update = try {
                withContext(Dispatchers.IO) {
                    GitHubUpdateClient(currentVersion = BuildConfig.VERSION_NAME).check()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            } finally {
                updateCheckInProgress = false
            } ?: return@launch
            if (!shouldShowUpdatePrompt(update, lastPromptedUpdateTag())) return@launch
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                showUpdatePrompt(update)
            } else {
                pendingUpdatePrompt = update
            }
        }
    }

    private fun showPendingUpdatePrompt(): Boolean {
        val update = pendingUpdatePrompt ?: return false
        pendingUpdatePrompt = null
        return showUpdatePrompt(update)
    }

    private fun showUpdatePrompt(update: UpdateCheckResult): Boolean {
        val asset = update.selectedAsset ?: return false
        if (!shouldShowUpdatePrompt(update, lastPromptedUpdateTag())) return false
        updatePreferences.edit().putString(KEY_LAST_PROMPTED_UPDATE_TAG, update.release.tagName).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_update_available_title)
            .setMessage(getString(R.string.settings_update_available, update.release.tagName, asset.name))
            .setNegativeButton(R.string.settings_update_later, null)
            .setPositiveButton(R.string.settings_update_download_install) { _, _ ->
                installUpdate(update.release, asset)
            }
            .show()
        return true
    }

    private fun lastPromptedUpdateTag(): String? =
        updatePreferences.getString(KEY_LAST_PROMPTED_UPDATE_TAG, null)

    internal fun installUpdate(release: GitHubRelease, asset: GitHubRelease.ReleaseAsset) {
        if (updateInstallInProgress) {
            Toast.makeText(this, R.string.settings_update_downloading, Toast.LENGTH_SHORT).show()
            return
        }
        val installer = ApkUpdateInstaller(applicationContext)
        if (updateInstallAction(installer.canRequestPackageInstalls()) == UpdateInstallAction.REQUEST_PERMISSION) {
            pendingInstall = PendingInstall(release, asset)
            Toast.makeText(this, R.string.settings_update_install_permission, Toast.LENGTH_LONG).show()
            runCatching {
                installPermission.launch(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }.onFailure {
                pendingInstall = null
                showUpdateFailure(it)
            }
            return
        }

        updateInstallInProgress = true
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_check_update)
            .setMessage(R.string.settings_update_downloading)
            .setCancelable(false)
            .create()
            .also { it.show() }
        lifecycleScope.launch {
            val update = try {
                withContext(Dispatchers.IO) { installer.downloadAndVerify(release, asset) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showUpdateFailure(error)
                return@launch
            } finally {
                updateInstallInProgress = false
                runCatching { progress.dismiss() }
            }
            stopVpn()
            runCatching { startActivity(installer.installIntent(update)) }
                .onFailure(::showUpdateFailure)
        }
    }

    private fun showUpdateFailure(error: Throwable) {
        val message = error.message ?: getString(R.string.settings_update_install_failed)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_check_update)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
        val subscriptionLink = runCatching { SubscriptionDeepLinkParser.parseOrNull(raw) }
        if (subscriptionLink.isFailure) {
            Toast.makeText(this, R.string.invalid_subscription_link, Toast.LENGTH_LONG).show()
            return
        }
        when {
            raw.length > MAX_EXTERNAL_IMPORT_CHARS -> Toast.makeText(this, R.string.external_import_too_large, Toast.LENGTH_LONG).show()
            subscriptionLink.getOrNull() != null -> {
                pendingImport = raw
                if (::binding.isInitialized) binding.bottomNavigation.selectedItemId = R.id.profilesFragment
            }
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
        private const val AUTOMATIC_UPDATE_CHECK_INTERVAL_MILLIS = 5 * 60 * 1000L
        private const val UPDATE_PREFERENCES = "updates"
        private const val KEY_LAST_PROMPTED_UPDATE_TAG = "last_prompted_tag"
        private const val SYSTEM_PREFERENCES = "system"
        private const val KEY_BATTERY_PROMPT_HANDLED = "battery_prompt_handled"
        private val EXTERNAL_PROFILE_SCHEMES = setOf("olcrtc", "vless", "vmess", "trojan")
    }

    private data class PendingInstall(
        val release: GitHubRelease,
        val asset: GitHubRelease.ReleaseAsset,
    )
}

internal fun shouldShowBatteryOptimizationPrompt(
    ignoringOptimizations: Boolean,
    promptHandled: Boolean,
): Boolean = !ignoringOptimizations && !promptHandled
