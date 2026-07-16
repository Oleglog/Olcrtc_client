package io.github.oleglog.olcrtc.client.profiles

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.oleglog.olcrtc.client.MainActivity
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ProfileRepository
import io.github.oleglog.olcrtc.client.data.SubscriptionSummary
import io.github.oleglog.olcrtc.client.databinding.FragmentProfilesBinding
import io.github.oleglog.olcrtc.client.importer.BundleImportDispatcher
import io.github.oleglog.olcrtc.client.importer.BundleImportResult
import io.github.oleglog.olcrtc.client.importer.DecodedImportPayload
import io.github.oleglog.olcrtc.client.importer.ImportPayload
import io.github.oleglog.olcrtc.client.importer.QrScannerActivity
import io.github.oleglog.olcrtc.client.subscription.SubscriptionRefresher
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class ProfilesFragment : Fragment() {
    private var _binding: FragmentProfilesBinding? = null
    private val profiles by lazy { ProfileRepository.open(requireContext().applicationContext) }
    private val storage = Executors.newSingleThreadExecutor()
    private val bundleImports = BundleImportDispatcher()
    private val qrScanner = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(QrScannerActivity.EXTRA_RESULT)?.let(::saveNewSubscription)
        } else {
            result.data?.getStringExtra(QrScannerActivity.EXTRA_ERROR)
                ?.let { showError(IllegalStateException(it)) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentProfilesBinding.inflate(inflater, container, false)
        binding.addSubscription.setOnClickListener {
            qrScanner.launch(Intent(requireContext(), QrScannerActivity::class.java))
        }
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        loadSubscriptions()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        storage.shutdownNow()
        super.onDestroy()
    }

    private fun loadSubscriptions() {
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching { profiles.listSubscriptions() }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess(::showSubscriptions).onFailure(::showError)
            }
        }
    }

    private fun showSubscriptions(subscriptions: List<SubscriptionSummary>) {
        val binding = _binding ?: return
        binding.empty.visibility = if (subscriptions.isEmpty()) View.VISIBLE else View.GONE
        binding.profileList.removeAllViews()
        subscriptions.forEach { binding.profileList.addView(subscriptionCard(it)) }
    }

    private fun subscriptionCard(subscription: SubscriptionSummary): View = MaterialCardView(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = 12.dp }
        radius = 10.dp.toFloat()
        addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 12.dp, 8.dp, 12.dp)
            addView(TextView(requireContext()).apply {
                text = subscription.name
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            })
            addView(TextView(requireContext()).apply {
                text = subscriptionSummary(subscription)
                setPadding(0, 4.dp, 0, 12.dp)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            })
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val progress = ProgressBar(requireContext()).apply {
                    visibility = View.GONE
                    isIndeterminate = true
                }
                val update = MaterialButton(
                    requireContext(),
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle,
                ).apply {
                    setText(R.string.subscription_update_now)
                    cornerRadius = 8.dp
                    setOnClickListener { updateSubscription(subscription.id, this, progress) }
                }
                addView(update)
                addView(progress, LinearLayout.LayoutParams(24.dp, 24.dp).apply { marginStart = 8.dp })
                addView(View(requireContext()), LinearLayout.LayoutParams(0, 0, 1f))
                addView(iconButton(R.drawable.ic_edit_20, R.string.edit) {
                    editSubscription(subscription)
                })
                addView(iconButton(R.drawable.ic_delete_20, R.string.delete) {
                    confirmDeleteSubscription(subscription)
                })
            })
        })
    }

    private fun iconButton(iconRes: Int, descriptionRes: Int, action: () -> Unit): MaterialButton =
        MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            icon = ContextCompat.getDrawable(requireContext(), iconRes)
            text = ""
            contentDescription = getString(descriptionRes)
            layoutParams = LinearLayout.LayoutParams(40.dp, 40.dp).apply { marginStart = 2.dp }
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            iconSize = 20.dp
            iconPadding = 0
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            insetTop = 0
            insetBottom = 0
            cornerRadius = 8.dp
            setPadding(0, 0, 0, 0)
            setOnClickListener { action() }
        }

    private fun subscriptionSummary(subscription: SubscriptionSummary): String = buildString {
        append(subscription.profileCount).append(' ').append(getString(R.string.profiles_count_suffix))
        subscription.serverVersion?.let { append(" · ").append(getString(R.string.subscription_server_badge, it)) }
        subscription.lastSuccessAt?.let {
            append(" · ").append(getString(R.string.subscription_last_success, formatTime(it)))
        }
        subscription.lastErrorCode?.let { append(" · ").append(it) }
        if (subscription.mirrorAvailable) append(" · ").append(getString(R.string.subscription_mirror_badge))
        if (!subscription.enabled) append(" · ").append(getString(R.string.subscription_disabled_badge))
    }

    private fun saveNewSubscription(raw: String) {
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching {
                when (val payload = ImportPayload.decode(raw)) {
                    is DecodedImportPayload.Bundle -> importSubscriptionBundle(payload.raw)
                    is DecodedImportPayload.Multipart -> importSubscriptionBundle(payload.raw)
                    is DecodedImportPayload.Profile -> {
                        val uri = java.net.URI(payload.uri)
                        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
                            getString(R.string.subscription_https_required)
                        }
                        val id = profiles.insertSubscriptionSource(
                            name = requireNotNull(uri.host),
                            url = payload.uri,
                            kind = "GENERIC",
                        )
                        refreshSubscription(id)
                    }
                }
            }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess(::showSubscriptionRefreshResult).onFailure(::showError)
                loadSubscriptions()
            }
        }
    }

    private fun importSubscriptionBundle(raw: String): SubscriptionRefresher.Result =
        when (val bundle = bundleImports.accept(raw)) {
            is BundleImportResult.Complete -> {
                val id = profiles.insertSubscription(bundle.bundle)
                refreshSubscription(id)
            }
            is BundleImportResult.Pending -> error("${bundle.received}/${bundle.total}")
        }

    private fun updateSubscription(subscriptionId: Long, button: MaterialButton, progress: ProgressBar) {
        if (storage.isShutdown) return
        button.isEnabled = false
        progress.visibility = View.VISIBLE
        storage.execute {
            val result = runCatching {
                refreshSubscription(subscriptionId).also {
                    check(it.success) {
                        getString(R.string.subscription_update_failed)
                    }
                }
            }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                button.isEnabled = true
                progress.visibility = View.GONE
                result.onSuccess(::showSubscriptionRefreshResult).onFailure(::showError)
                loadSubscriptions()
            }
        }
    }

    private fun showSubscriptionRefreshResult(result: SubscriptionRefresher.Result) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.subscription_updated)
            .setMessage(getString(R.string.subscription_update_summary, result.added, result.removed, result.total))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun refreshSubscription(subscriptionId: Long): SubscriptionRefresher.Result =
        (activity as? MainActivity)?.refreshSubscription(subscriptionId)
            ?: SubscriptionRefresher(profiles).refreshWithChanges(subscriptionId)

    private fun editSubscription(subscription: SubscriptionSummary) {
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching { requireNotNull(profiles.getSubscriptionSource(subscription.id)) }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess { source ->
                    val form = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(24.dp, 8.dp, 24.dp, 0)
                    }
                    val name = textField(form, R.string.subscription_name, subscription.name)
                    val url = textField(form, R.string.subscription_url, source.url)
                    val dialog = MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.subscription_edit)
                        .setView(form)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, null)
                        .create()
                    dialog.setOnShowListener {
                        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            saveSubscriptionEdit(
                                dialog,
                                subscription.id,
                                name.text?.toString().orEmpty(),
                                url.text?.toString().orEmpty(),
                            )
                        }
                    }
                    dialog.show()
                }.onFailure(::showError)
            }
        }
    }

    private fun textField(container: LinearLayout, hintRes: Int, value: String): TextInputEditText {
        val input = TextInputEditText(requireContext()).apply { setText(value) }
        container.addView(TextInputLayout(requireContext()).apply {
            hint = getString(hintRes)
            addView(input)
        })
        return input
    }

    private fun saveSubscriptionEdit(
        dialog: AlertDialog,
        subscriptionId: Long,
        name: String,
        url: String,
    ) {
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching { profiles.updateSubscriptionSource(subscriptionId, name, url) }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess {
                    dialog.dismiss()
                    loadSubscriptions()
                }.onFailure(::showError)
            }
        }
    }

    private fun confirmDeleteSubscription(subscription: SubscriptionSummary) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.subscription_delete_title)
            .setMessage(getString(R.string.subscription_delete_message, subscription.name))
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.subscription_keep_profiles) { _, _ -> deleteSubscription(subscription.id, true) }
            .setPositiveButton(R.string.delete) { _, _ -> deleteSubscription(subscription.id, false) }
            .show()
    }

    private fun deleteSubscription(subscriptionId: Long, retainProfiles: Boolean) {
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching { profiles.deleteSubscription(subscriptionId, retainProfiles) }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onFailure(::showError)
                loadSubscriptions()
            }
        }
    }

    private fun showError(error: Throwable) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.invalid_profile)
            .setMessage(error.message ?: error.javaClass.simpleName)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun formatTime(value: Long): String = DateFormat.getDateTimeInstance(
        DateFormat.SHORT,
        DateFormat.SHORT,
    ).format(Date(value))

    private val binding get() = requireNotNull(_binding)
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
