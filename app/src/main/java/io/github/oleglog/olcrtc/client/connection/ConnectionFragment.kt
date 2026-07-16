package io.github.oleglog.olcrtc.client.connection

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.oleglog.olcrtc.client.MainActivity
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ProfileConfig
import io.github.oleglog.olcrtc.client.data.ProfileRepository
import io.github.oleglog.olcrtc.client.data.SubscriptionProfileSummary
import io.github.oleglog.olcrtc.client.data.SubscriptionSummary
import io.github.oleglog.olcrtc.client.databinding.FragmentConnectionBinding
import io.github.oleglog.olcrtc.client.importer.BundleImportDispatcher
import io.github.oleglog.olcrtc.client.importer.BundleImportResult
import io.github.oleglog.olcrtc.client.importer.DecodedImportPayload
import io.github.oleglog.olcrtc.client.importer.ImportPayload
import io.github.oleglog.olcrtc.client.importer.QrScannerActivity
import io.github.oleglog.olcrtc.client.profile.ImportedProfile
import io.github.oleglog.olcrtc.client.profile.ProfileUri
import io.github.oleglog.olcrtc.client.subscription.SubscriptionRefresher
import io.github.oleglog.olcrtc.client.vpn.VpnState
import java.util.concurrent.Executors

class ConnectionFragment : Fragment() {
    private var _binding: FragmentConnectionBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val profiles by lazy { ProfileRepository.open(requireContext().applicationContext) }
    private val storage = Executors.newSingleThreadExecutor()
    private val bundleImports = BundleImportDispatcher()
    private var currentState = VpnState.NO_PROFILE
    private var selectedProfileId: Long? = null
    private var selectedSubscriptionProfileId: String? = null
    private var connectedProfileId: Long? = null
    private var connectedSubscriptionProfileId: String? = null

    private val qrScanner = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringExtra(QrScannerActivity.EXTRA_RESULT)
                ?.let { validatePreview(it, R.string.source_camera) }
        } else {
            result.data
                ?.getStringExtra(QrScannerActivity.EXTRA_ERROR)
                ?.let { _binding?.status?.text = it }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentConnectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding.connect.setOnClickListener {
            if (currentState == VpnState.CONNECTED || currentState in BUSY_STATES) activityHost.stopVpn() else connectSelected()
        }
        binding.addProfile.setOnClickListener { showAddConnectionMenu() }
        binding.testSelected.setOnClickListener { testSelectedProfile() }
        storage.execute { SubscriptionRefresher(profiles).refreshStale() }
        loadProfiles()
    }

    override fun onStart() {
        super.onStart()
        activityHost.setVpnStateListener(::showVpnState)
        activityHost.setImportListener { validatePreview(it, R.string.source_deep_link) }
    }

    override fun onStop() {
        activityHost.setImportListener(null)
        activityHost.setVpnStateListener(null)
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        storage.shutdownNow()
        super.onDestroy()
    }


    private fun loadProfiles() {
        storage.execute {
            val result = runCatching {
                ConnectionScreenModel(
                    local = profiles.listLocal(),
                    subscriptions = profiles.listSubscriptions().map { subscription ->
                        SubscriptionSection(subscription, profiles.listSubscriptionProfiles(subscription.id))
                    },
                )
            }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess { model ->
                    binding.profileList.removeAllViews()
                    val subscriptionItems = model.subscriptions.flatMap(SubscriptionSection::profiles)
                    if (model.local.isEmpty() && subscriptionItems.isEmpty()) {
                        binding.profileList.addView(TextView(requireContext()).apply {
                            setText(R.string.profiles_placeholder)
                            setPadding(0, 12.dp, 0, 12.dp)
                        })
                    } else {
                        if (selectedProfileId == null && selectedSubscriptionProfileId == null) {
                            model.local.firstOrNull()?.let {
                                selectProfile(it.id, it.name, it.type, it.endpoint)
                            } ?: subscriptionItems.firstOrNull()?.let {
                                selectSubscriptionProfile(it.id, it.name, it.type, it.endpoint)
                            }
                        }
                        if (model.local.isNotEmpty()) {
                            binding.profileList.addView(sectionTitle(R.string.connection_manual_profiles))
                            model.local.forEach { profile ->
                                binding.profileList.addView(profileCard(profile.id, profile.name, profile.type, profile.endpoint))
                            }
                        }
                        if (subscriptionItems.isNotEmpty()) {
                            binding.profileList.addView(sectionTitle(R.string.connection_subscription_profiles))
                            model.subscriptions.filter { it.profiles.isNotEmpty() }.forEach { section ->
                                binding.profileList.addView(subscriptionTitle(section.subscription))
                                section.profiles.forEach { profile ->
                                    binding.profileList.addView(
                                        subscriptionProfileCard(profile.id, profile.name, profile.type, profile.endpoint),
                                    )
                                }
                            }
                        }
                    }
                }.onFailure { binding.status.text = it.message }
            }
        }
    }

    private fun sectionTitle(textRes: Int): TextView = TextView(requireContext()).apply {
        setText(textRes)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        setPadding(0, 18.dp, 0, 4.dp)
    }

    private fun subscriptionTitle(subscription: SubscriptionSummary): TextView = TextView(requireContext()).apply {
        text = subscription.name
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        setPadding(4.dp, 12.dp, 0, 0)
    }

    private fun profileCard(id: Long, name: String, type: String, endpoint: String): View =
        MaterialCardView(requireContext()).apply {
            tag = "local:$id"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8.dp
            }
            isClickable = true
            isFocusable = true
            strokeColor = cardStrokeState(id == selectedProfileId, id == connectedProfileId)
            strokeWidth = cardStrokeWidth(id == selectedProfileId, id == connectedProfileId)
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(16.dp, 14.dp, 16.dp, 14.dp)
                addView(LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(requireContext()).apply {
                        text = name
                        textSize = 18f
                    })
                    addView(TextView(requireContext()).apply { text = "$type - $endpoint" })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(iconButton(android.R.drawable.ic_menu_edit, R.string.edit) { editLocalProfile(id) })
                addView(iconButton(android.R.drawable.ic_menu_delete, R.string.delete) { confirmDeleteProfile(id, name) })
            })
            setOnClickListener { selectProfile(id, name, type, endpoint) }
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
            minWidth = 48.dp
            minimumWidth = 48.dp
            setOnClickListener { action() }
        }


    private fun subscriptionProfileCard(id: String, name: String, type: String, endpoint: String): View =
        MaterialCardView(requireContext()).apply {
            tag = "sub:$id"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8.dp
            }
            isClickable = true
            isFocusable = true
            strokeColor = cardStrokeState(id == selectedSubscriptionProfileId, id == connectedSubscriptionProfileId)
            strokeWidth = cardStrokeWidth(id == selectedSubscriptionProfileId, id == connectedSubscriptionProfileId)
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(16.dp, 14.dp, 16.dp, 14.dp)
                addView(LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(requireContext()).apply {
                        text = name
                        textSize = 18f
                    })
                    addView(TextView(requireContext()).apply { text = "$type - $endpoint" })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(iconButton(android.R.drawable.ic_menu_edit, R.string.edit) { editSubscriptionProfile(id) })
                addView(iconButton(android.R.drawable.ic_menu_delete, R.string.delete) {
                    confirmDeleteSubscriptionProfile(id, name)
                })
            })
            setOnClickListener { selectSubscriptionProfile(id, name, type, endpoint) }
        }

    private fun cardStrokeState(selected: Boolean, connected: Boolean): Int {
        val attr = when {
            connected -> android.R.attr.colorPrimary
            selected -> com.google.android.material.R.attr.colorSecondary
            else -> com.google.android.material.R.attr.colorOutline
        }
        val ta = requireContext().obtainStyledAttributes(intArrayOf(attr))
        return ta.getColor(0, 0).also { ta.recycle() }
    }

    private fun cardStrokeWidth(selected: Boolean, connected: Boolean): Int =
        (if (connected) 3.dp else if (selected) 2.dp else 1.dp)

    private fun refreshAllCardStrokes() {
        for (i in 0 until binding.profileList.childCount) {
            (binding.profileList.getChildAt(i) as? MaterialCardView)?.let { card ->
                val (sel, conn) = when (val tag = card.tag?.toString()) {
                    null -> false to false
                    else -> {
                        val parts = tag.split(":", limit = 2)
                        when (parts.firstOrNull()) {
                            "local" -> (parts.getOrNull(1)?.toLongOrNull() == selectedProfileId) to (parts.getOrNull(1)?.toLongOrNull() == connectedProfileId)
                            "sub" -> (parts.getOrNull(1) == selectedSubscriptionProfileId) to (parts.getOrNull(1) == connectedSubscriptionProfileId)
                            else -> false to false
                        }
                    }
                }
                card.strokeColor = cardStrokeState(sel, conn)
                card.strokeWidth = cardStrokeWidth(sel, conn)
            }
        }
    }

    private fun selectSubscriptionProfile(id: String, name: String, type: String, endpoint: String) {
        selectedProfileId = null
        selectedSubscriptionProfileId = id
        binding.selectedProfile.text = "$name\n$type - $endpoint"
        binding.status.text = getString(R.string.connection_selected_profile, name)
        binding.connect.isEnabled = currentState in BUSY_STATES || currentState == VpnState.CONNECTED || selectedSubscriptionProfileId != null
        refreshAllCardStrokes()
    }

    private fun selectProfile(id: Long, name: String, type: String, endpoint: String) {
        selectedSubscriptionProfileId = null
        selectedProfileId = id
        binding.selectedProfile.text = "$name\n$type - $endpoint"
        binding.status.text = getString(R.string.connection_selected_profile, name)
        binding.connect.isEnabled = currentState in BUSY_STATES || currentState == VpnState.CONNECTED || selectedProfileId != null
        refreshAllCardStrokes()
    }

    private fun connectSelected() {
        selectedProfileId?.let { activityHost.requestVpnPermission(it); return }
        selectedSubscriptionProfileId?.let { activityHost.requestSubscriptionVpnPermission(it); return }
        binding.status.setText(R.string.no_profile)
    }

    private fun testSelectedProfile() {
        val localId = selectedProfileId
        val subscriptionId = selectedSubscriptionProfileId
        storage.execute {
            val result = runCatching {
                val profile = when {
                    localId != null -> profiles.get(localId)
                    subscriptionId != null -> profiles.getSubscriptionProfile(subscriptionId)
                    else -> null
                } ?: throw IllegalArgumentException(getString(R.string.no_profile))
                when (profile) {
                    is ProfileConfig.Standard -> when {
                        localId != null -> profiles.testLocalProfileLatency(localId)
                        subscriptionId != null -> profiles.testSubscriptionProfileLatency(subscriptionId)
                        else -> error("unreachable")
                    }
                    is ProfileConfig.Olcrtc -> {
                        val selectedIsConnected = currentState == VpnState.CONNECTED && when {
                            localId != null -> localId == connectedProfileId
                            subscriptionId != null -> subscriptionId == connectedSubscriptionProfileId
                            else -> false
                        }
                        require(selectedIsConnected) { getString(R.string.profile_latency_connect_first) }
                        activityHost.testConnectionLatency()
                    }
                }
            }
            activity?.runOnUiThread {
                result.onSuccess { binding.status.text = getString(R.string.profile_latency_result, it) }
                    .onFailure { binding.status.text = it.message ?: getString(R.string.invalid_profile) }
            }
        }
    }

    private fun showAddConnectionMenu() {
        val dialog = BottomSheetDialog(requireContext())
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 20.dp, 24.dp, 24.dp)
            addView(TextView(requireContext()).apply {
                setText(R.string.add_connection)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
                setPadding(0, 0, 0, 16.dp)
            })
            addView(addConnectionAction(R.string.scan_qr) {
                dialog.dismiss()
                requestScanner()
            })
            addView(addConnectionAction(R.string.paste_clipboard) {
                dialog.dismiss()
                pasteClipboard()
            })
        }
        dialog.setContentView(content)
        dialog.show()
    }

    private fun addConnectionAction(label: Int, action: () -> Unit): MaterialButton = MaterialButton(
        requireContext(),
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        setText(label)
        isAllCaps = false
        minHeight = 56.dp
        setOnClickListener { action() }
    }

    private fun showVpnState(state: VpnState, error: String?) {
        if (_binding == null) return
        currentState = state
        when (state) {
            VpnState.CONNECTED -> {
                val active = activityHost.activeProfileReference()
                connectedProfileId = active?.removePrefix("local:")?.takeIf { it != active }?.toLongOrNull()
                connectedSubscriptionProfileId = active?.removePrefix("subscription:")?.takeIf { it != active }
            }
            VpnState.DISCONNECTED, VpnState.ERROR, VpnState.NO_PROFILE -> {
                connectedProfileId = null
                connectedSubscriptionProfileId = null
            }
            else -> {}
        }
        binding.status.text = error ?: state.name
        binding.connect.text = getString(
            if (state == VpnState.CONNECTED || state in BUSY_STATES) R.string.disconnect else R.string.connect
        )
        binding.connect.isEnabled = state == VpnState.CONNECTED || state in BUSY_STATES || selectedProfileId != null || selectedSubscriptionProfileId != null
        refreshAllCardStrokes()
    }

    private fun requestScanner() {
        qrScanner.launch(
            Intent(requireContext(), QrScannerActivity::class.java),
        )
    }

    private fun pasteClipboard() {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString()
        if (text.isNullOrBlank()) binding.status.setText(R.string.clipboard_empty)
        else validatePreview(text, R.string.source_clipboard)
    }

    private fun validatePreview(raw: String, source: Int) {
        storage.execute {
            val result = runCatching { parsePreview(raw) }
            activity?.runOnUiThread { showPreview(result, R.string.invalid_profile, source) }
        }
    }

    private fun parsePreview(raw: String): ImportPreview {
        return when (val payload = ImportPayload.decode(raw)) {
            is DecodedImportPayload.Profile -> saveProfile(payload.uri)
            is DecodedImportPayload.Bundle -> importBundle(payload.raw)
            is DecodedImportPayload.Multipart -> importBundle(payload.raw)
        }
    }

    private fun importBundle(raw: String): ImportPreview = when (val bundle = bundleImports.accept(raw)) {
        is BundleImportResult.Pending -> ImportPreview(
            description = "",
            multipartReceived = bundle.received,
            multipartTotal = bundle.total,
        )
        is BundleImportResult.Complete -> {
            val subscriptionId = profiles.insertSubscription(bundle.bundle)
            val refresh = (activity as? MainActivity)?.refreshSubscription(subscriptionId)
                ?: SubscriptionRefresher(profiles).refreshWithChanges(subscriptionId)
            ImportPreview(
                description = "${bundle.bundle.name}: ${refresh.total} profiles",
                subscriptionImported = true,
                subscriptionRefresh = refresh,
            )
        }
    }

    private fun saveProfile(raw: String): ImportPreview = when (val profile = ProfileUri.parse(raw)) {
        is ImportedProfile.Olcrtc -> ImportPreview(
            description = "olcRTC: ${profile.value.name}",
            localProfileId = profiles.findDuplicate(profile.value) ?: profiles.insert(profile.value),
        )
        is ImportedProfile.Standard -> ImportPreview(
            description = "${profile.value.protocol.name}: ${profile.value.name}",
            localProfileId = profiles.findDuplicate(profile.value) ?: profiles.insert(profile.value),
        )
    }

    private fun showPreview(result: Result<ImportPreview>, fallbackError: Int, source: Int) {
        if (_binding == null) return
        result.onSuccess { preview ->
            if (preview.multipartReceived != null && preview.multipartTotal != null) {
                binding.status.text = "${getString(R.string.import_source, getString(source))}\n${getString(
                    R.string.subscription_qr_progress,
                    preview.multipartReceived,
                    preview.multipartTotal,
                )}"
                return@onSuccess
            }
            selectedProfileId = preview.localProfileId
            selectedSubscriptionProfileId = null
            binding.selectedProfile.text = preview.description
            binding.connect.isEnabled = preview.localProfileId != null || currentState in BUSY_STATES || currentState == VpnState.CONNECTED
            binding.status.text = "${getString(R.string.import_source, getString(source))}\n${getString(
                if (preview.subscriptionImported) R.string.subscription_imported else R.string.profile_saved,
            )}"
            loadProfiles()
            preview.subscriptionRefresh?.let(::showSubscriptionRefreshResult)
        }.onFailure { binding.status.text = it.message ?: getString(fallbackError) }
    }

    private fun showSubscriptionRefreshResult(result: SubscriptionRefresher.Result) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (result.success) R.string.subscription_updated else R.string.subscription_update_failed)
            .setMessage(
                if (result.success) {
                    getString(R.string.subscription_update_summary, result.added, result.removed, result.total)
                } else {
                    getString(R.string.subscription_update_preserved)
                },
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun editLocalProfile(profileId: Long) {
        storage.execute {
            val result = runCatching { requireNotNull(profiles.get(profileId)) }
            activity?.runOnUiThread {
                result.onSuccess { profile ->
                    ProfileEditorDialog.show(
                        fragment = this,
                        profile = profile,
                        onSave = { updated, dialog -> saveLocalProfile(profileId, updated, dialog) },
                        onError = { binding.status.text = it.message ?: getString(R.string.invalid_profile) },
                    )
                }.onFailure { binding.status.text = it.message ?: getString(R.string.invalid_profile) }
            }
        }
    }

    private fun saveLocalProfile(profileId: Long, profile: ProfileConfig, dialog: AlertDialog) {
        storage.execute {
            val result = runCatching {
                when (profile) {
                    is ProfileConfig.Olcrtc -> profiles.update(profileId, profile.value)
                    is ProfileConfig.Standard -> profiles.update(profileId, profile.value)
                }
            }
            activity?.runOnUiThread {
                result.onSuccess {
                    dialog.dismiss()
                    loadProfiles()
                }.onFailure { binding.status.text = it.message ?: getString(R.string.invalid_profile) }
            }
        }
    }

    private fun editSubscriptionProfile(profileId: String) {
        storage.execute {
            val result = runCatching { requireNotNull(profiles.getSubscriptionProfile(profileId)) }
            activity?.runOnUiThread {
                result.onSuccess { profile ->
                    ProfileEditorDialog.show(
                        fragment = this,
                        profile = profile,
                        onSave = { updated, dialog -> saveSubscriptionProfile(profileId, updated, dialog) },
                        onError = { binding.status.text = it.message ?: getString(R.string.invalid_profile) },
                    )
                }.onFailure { binding.status.text = it.message ?: getString(R.string.invalid_profile) }
            }
        }
    }

    private fun saveSubscriptionProfile(profileId: String, profile: ProfileConfig, dialog: AlertDialog) {
        storage.execute {
            val result = runCatching {
                profiles.updateSubscriptionProfile(
                    profileId,
                    when (profile) {
                        is ProfileConfig.Olcrtc -> ImportedProfile.Olcrtc(profile.value)
                        is ProfileConfig.Standard -> ImportedProfile.Standard(profile.value)
                    },
                )
            }
            activity?.runOnUiThread {
                result.onSuccess {
                    dialog.dismiss()
                    loadProfiles()
                }.onFailure { binding.status.text = it.message ?: getString(R.string.invalid_profile) }
            }
        }
    }

    private fun confirmDeleteSubscriptionProfile(profileId: String, name: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_delete_title)
            .setMessage(getString(R.string.profile_delete_message, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                storage.execute {
                    val result = runCatching { profiles.deleteSubscriptionProfile(profileId) }
                    activity?.runOnUiThread {
                        result.onSuccess {
                            if (selectedSubscriptionProfileId == profileId) {
                                selectedSubscriptionProfileId = null
                                binding.selectedProfile.setText(R.string.no_profile)
                                binding.connect.isEnabled = currentState in BUSY_STATES || currentState == VpnState.CONNECTED
                            }
                            loadProfiles()
                        }.onFailure { binding.status.text = it.message ?: getString(R.string.invalid_profile) }
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteProfile(profileId: Long, name: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_delete_title)
            .setMessage(getString(R.string.profile_delete_message, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                storage.execute {
                    val result = runCatching { profiles.deleteLocal(profileId) }
                    activity?.runOnUiThread {
                        result.onSuccess {
                            if (selectedProfileId == profileId) selectedProfileId = null
                            loadProfiles()
                        }.onFailure { binding.status.text = it.message ?: getString(R.string.invalid_profile) }
                    }
                }
            }
            .show()
    }

    private val activityHost get() = requireActivity() as MainActivity
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private data class ImportPreview(
        val description: String,
        val localProfileId: Long? = null,
        val subscriptionImported: Boolean = false,
        val multipartReceived: Int? = null,
        val multipartTotal: Int? = null,
        val subscriptionRefresh: SubscriptionRefresher.Result? = null,
    )

    private data class ConnectionScreenModel(
        val local: List<io.github.oleglog.olcrtc.client.data.ProfileSummary>,
        val subscriptions: List<SubscriptionSection>,
    )

    private data class SubscriptionSection(
        val subscription: SubscriptionSummary,
        val profiles: List<SubscriptionProfileSummary>,
    )

    companion object {
        private val BUSY_STATES = setOf(
            VpnState.PREPARING,
            VpnState.CONNECTING,
            VpnState.RECONNECTING,
            VpnState.STOPPING,
        )
    }
}
