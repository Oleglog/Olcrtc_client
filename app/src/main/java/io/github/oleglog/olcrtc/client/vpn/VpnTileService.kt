package io.github.oleglog.olcrtc.client.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.routing.RoutingSettings

class VpnTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val intent = RoutingSettings.open(this).getVpnIntent()
        val hasProfile = intent.localProfileId != null || intent.subscriptionProfileId != null
        val state = currentState
        qsTile?.apply {
            this.state = when {
                !hasProfile -> Tile.STATE_UNAVAILABLE
                state == VpnState.CONNECTED -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            label = if (state in CONNECTING_STATES) {
                getString(R.string.vpn_tile_connecting)
            } else {
                getString(R.string.vpn_tile_label)
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            ContextCompat.startForegroundService(
                this,
                Intent(this, OlcrtcVpnService::class.java).setAction(OlcrtcVpnService.ACTION_TOGGLE),
            )
        }
    }

    companion object {
        @Volatile
        private var currentState: VpnState? = null
        private val CONNECTING_STATES = setOf(
            VpnState.PREPARING,
            VpnState.CONNECTING,
            VpnState.RECONNECTING,
        )

        fun update(context: Context, state: VpnState) {
            currentState = state
            requestListeningState(context, ComponentName(context, VpnTileService::class.java))
        }
    }
}
