package io.github.oleglog.olcrtc.client

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import io.github.oleglog.olcrtc.client.databinding.ActivityMainBinding
import io.github.oleglog.olcrtc.client.vpn.IOlcrtcVpnService
import io.github.oleglog.olcrtc.client.vpn.IVpnStateCallback
import io.github.oleglog.olcrtc.client.vpn.OlcrtcVpnService
import io.github.oleglog.olcrtc.client.vpn.VpnState

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var vpn: IOlcrtcVpnService? = null
    private var bound = false
    private var pendingProfileId: Long? = null
    private var pendingImport: String? = null
    private var importListener: ((String) -> Unit)? = null
    private var stateListener: ((VpnState, String?) -> Unit)? = null

    private val callback = object : IVpnStateCallback.Stub() {
        override fun onStateChanged(state: Int, error: String?) {
            runOnUiThread {
                stateListener?.invoke(VpnState.entries.getOrNull(state) ?: VpnState.ERROR, error)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            vpn = IOlcrtcVpnService.Stub.asInterface(service).also { remote ->
                remote.registerCallback(callback)
                stateListener?.invoke(VpnState.entries.getOrNull(remote.state) ?: VpnState.ERROR, null)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpn = null
        }
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            pendingProfileId?.let(::startVpn)
        } else {
            pendingProfileId = null
            stateListener?.invoke(VpnState.ERROR, getString(R.string.vpn_permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pendingProfileId = savedInstanceState?.getLong(KEY_PENDING_PROFILE_ID)?.takeIf { it > 0 }
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        binding.bottomNavigation.setupWithNavController(navHost.navController)
        acceptExternalIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptExternalIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, OlcrtcVpnService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
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
        super.onSaveInstanceState(outState)
    }

    fun setImportListener(listener: ((String) -> Unit)?) {
        importListener = listener
        if (listener != null) consumeExternalImport()?.let(listener)
    }

    fun setVpnStateListener(listener: ((VpnState, String?) -> Unit)?) {
        stateListener = listener
        if (listener != null) {
            vpn?.let { remote ->
                listener(VpnState.entries.getOrNull(remote.state) ?: VpnState.ERROR, null)
            }
        }
    }

    fun stopVpn() {
        vpn?.stop()
    }

    fun requestVpnPermission(profileId: Long) {
        pendingProfileId = profileId
        val intent = VpnService.prepare(this)
        if (intent == null) startVpn(profileId) else vpnPermission.launch(intent)
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

    companion object {
        private const val KEY_PENDING_PROFILE_ID = "pending_profile_id"
        private const val MAX_EXTERNAL_IMPORT_CHARS = 16 * 1024
        private val EXTERNAL_PROFILE_SCHEMES = setOf("olcrtc", "vless", "vmess", "trojan")
    }
}
