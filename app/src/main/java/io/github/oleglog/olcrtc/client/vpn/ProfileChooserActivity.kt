package io.github.oleglog.olcrtc.client.vpn

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ProfileRepository
import io.github.oleglog.olcrtc.client.profile.orderProfiles
import io.github.oleglog.olcrtc.client.routing.RoutingSettings
import java.util.concurrent.Executors

class ProfileChooserActivity : AppCompatActivity() {
    private val storage = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage.execute {
            val result = runCatching { loadChoices() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess(::showChoices).onFailure {
                    Toast.makeText(this, R.string.vpn_notification_profile_chooser_empty, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        storage.shutdownNow()
        super.onDestroy()
    }

    private fun loadChoices(): List<Choice> {
        val repository = ProfileRepository.open(applicationContext)
        val settings = RoutingSettings.open(applicationContext)
        val localFavorites = settings.getFavoriteLocalProfileIds()
        val local = repository.listLocal().map {
            Choice(
                label = "${it.name}\n${getString(R.string.profile_group_local)} · ${it.type}",
                reference = "local:${it.id}",
                localId = it.id,
                favorite = it.id in localFavorites,
            )
        }
        val subscriptions = repository.listSubscriptions().flatMap { subscription ->
            repository.listSubscriptionProfiles(subscription.id).map { profile ->
                Choice(
                    label = "${profile.name}\n${subscription.name} · ${profile.type}",
                    reference = "subscription:${profile.id}",
                    subscriptionId = profile.id,
                    favorite = profile.favorite,
                )
            }
        }
        return orderProfiles(
            local + subscriptions,
            settings.getLastSuccessfulProfileReference(),
            Choice::reference,
            Choice::favorite,
        )
    }

    private fun showChoices(choices: List<Choice>) {
        if (choices.isEmpty()) {
            Toast.makeText(this, R.string.vpn_notification_profile_chooser_empty, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val active = intent.getStringExtra(EXTRA_ACTIVE_PROFILE)
        val selected = choices.indexOfFirst { it.reference == active }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vpn_notification_profile_chooser_title)
            .setSingleChoiceItems(choices.map(Choice::label).toTypedArray(), selected) { dialog, index ->
                connect(choices[index])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        dialog.setOnDismissListener { finish() }
        dialog.show()
    }

    private fun connect(choice: Choice) {
        val service = Intent(this, OlcrtcVpnService::class.java).setAction(OlcrtcVpnService.ACTION_START)
        choice.localId?.let { service.putExtra(OlcrtcVpnService.EXTRA_PROFILE_ID, it) }
        choice.subscriptionId?.let { service.putExtra(OlcrtcVpnService.EXTRA_SUBSCRIPTION_PROFILE_ID, it) }
        ContextCompat.startForegroundService(this, service)
    }

    private data class Choice(
        val label: String,
        val reference: String,
        val localId: Long? = null,
        val subscriptionId: String? = null,
        val favorite: Boolean = false,
    )

    companion object {
        const val EXTRA_ACTIVE_PROFILE = "active_profile"
    }
}
