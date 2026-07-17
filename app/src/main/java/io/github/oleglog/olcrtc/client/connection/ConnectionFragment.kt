package io.github.oleglog.olcrtc.client.connection

import android.app.Activity
import android.content.res.ColorStateList
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
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
import io.github.oleglog.olcrtc.client.vpn.ConnectionStage
import java.util.Locale
import java.util.concurrent.Executors

class ConnectionFragment : Fragment() {
    private var _binding: FragmentConnectionBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val profiles by lazy { ProfileRepository.open(requireContext().applicationContext) }
    private val storage = Executors.newSingleThreadExecutor()
    private val latency = Executors.newSingleThreadExecutor()
    private val bundleImports = BundleImportDispatcher()
    private var currentState = VpnState.NO_PROFILE
    private var selectedProfileId: Long? = null
    private var selectedSubscriptionProfileId: String? = null
    private var connectedProfileId: Long? = null
    private var connectedSubscriptionProfileId: String? = null
    private var currentStage = ConnectionStage.IDLE
    private var currentReconnectAttempt = 0
    private var vpnStateRevision = 0L
    private var latencyRequestId = 0L
    private var latencyInProgress = false
    private var profilesLoaded = false

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
                ?.let { if (_binding != null) showStatus(it) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentConnectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding.connect.isEnabled = false
        binding.testSelected.isEnabled = false
        renderContentState(ConnectionContentState.LOADING)
        binding.connect.setOnClickListener {
            if (currentState == VpnState.CONNECTED || currentState in BUSY_STATES) activityHost.stopVpn() else connectSelected()
        }
        binding.addProfile.setOnClickListener { showAddConnectionMenu() }
        binding.contentStateAction.setOnClickListener { showAddConnectionMenu() }
        binding.testSelected.setOnClickListener { testSelectedProfile() }
    }

    override fun onStart() {
        super.onStart()
        activityHost.setVpnStateListener(::showVpnState)
        activityHost.setImportListener { validatePreview(it, R.string.source_deep_link) }
        loadProfiles()
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
        latency.shutdownNow()
        super.onDestroy()
    }


    private fun loadProfiles() {
        if (storage.isShutdown) return
        if (!profilesLoaded) renderContentState(ConnectionContentState.LOADING)
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
                    if (currentState != VpnState.CONNECTED) {
                        selectedProfileId = selectedProfileId?.takeIf { selected ->
                            model.local.any { it.id == selected }
                        }
                        selectedSubscriptionProfileId = selectedSubscriptionProfileId?.takeIf { selected ->
                            subscriptionItems.any { it.id == selected }
                        }
                        if (selectedProfileId == null && selectedSubscriptionProfileId == null) {
                            selectedProfileId = model.local.firstOrNull()?.id
                            if (selectedProfileId == null) {
                                selectedSubscriptionProfileId = subscriptionItems.firstOrNull()?.id
                            }
                        }
                    }
                    val hasProfiles = model.local.isNotEmpty() || subscriptionItems.isNotEmpty()
                    if (hasProfiles) {
                        if (model.local.isNotEmpty()) {
                            binding.profileList.addView(sectionTitle(R.string.connection_manual_profiles))
                            model.local.forEach { profile ->
                                binding.profileList.addView(
                                    profileCard(
                                        profile.id,
                                        profile.name,
                                        connectionTypeLabel(profile.type, profile.endpoint),
                                    ),
                                )
                            }
                        }
                        if (subscriptionItems.isNotEmpty()) {
                            binding.profileList.addView(sectionTitle(R.string.connection_subscription_profiles))
                            model.subscriptions.filter { it.profiles.isNotEmpty() }.forEach { section ->
                                binding.profileList.addView(subscriptionTitle(section.subscription))
                                section.profiles.forEach { profile ->
                                    binding.profileList.addView(
                                        subscriptionProfileCard(
                                            profile.id,
                                            profile.name,
                                            connectionTypeLabel(profile.type, profile.endpoint),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    profilesLoaded = true
                    renderContentState(
                        connectionContentState(loading = false, hasProfiles = hasProfiles, failed = false),
                    )
                    updateActionAvailability()
                }.onFailure { error ->
                    if (profilesLoaded && binding.profileList.childCount > 0) {
                        showStatus(error.message ?: getString(R.string.connection_error_message))
                    } else {
                        renderContentState(
                            connectionContentState(loading = false, hasProfiles = false, failed = true),
                        )
                    }
                }
            }
        }
    }

    private fun sectionTitle(textRes: Int): TextView = TextView(requireContext()).apply {
        setText(textRes)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        setPadding(0, dimen(R.dimen.space_5), 0, dimen(R.dimen.space_1))
    }

    private fun subscriptionTitle(subscription: SubscriptionSummary): TextView = TextView(requireContext()).apply {
        text = subscription.name
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        setPadding(dimen(R.dimen.space_1), dimen(R.dimen.space_3), 0, 0)
    }

    private fun profileCard(id: Long, name: String, type: String): View = connectionCard(
        reference = "local:$id",
        name = name,
        type = type,
        onEdit = { editLocalProfile(id) },
        onDelete = { confirmDeleteProfile(id, name) },
        onSelect = { selectProfile(id) },
    )

    private fun subscriptionProfileCard(id: String, name: String, type: String): View = connectionCard(
        reference = "subscription:$id",
        name = name,
        type = type,
        onEdit = { editSubscriptionProfile(id) },
        onDelete = { confirmDeleteSubscriptionProfile(id, name) },
        onSelect = { selectSubscriptionProfile(id) },
    )

    private fun connectionCard(
        reference: String,
        name: String,
        type: String,
        onEdit: () -> Unit,
        onDelete: () -> Unit,
        onSelect: () -> Unit,
    ): MaterialCardView = MaterialCardView(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dimen(R.dimen.space_2) }
        isClickable = true
        isFocusable = true
        radius = dimen(R.dimen.corner_card).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface))

        val detail = TextView(requireContext()).apply {
            text = type
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            maxLines = 2
        }
        val progress = CircularProgressIndicator(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(22.dp, 22.dp).apply {
                marginStart = dimen(R.dimen.space_2)
                marginEnd = dimen(R.dimen.space_1)
            }
            indicatorSize = 20.dp
            trackThickness = 2.dp
            isIndeterminate = true
            isVisible = false
        }
        addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dimen(R.dimen.space_4),
                dimen(R.dimen.space_3),
                dimen(R.dimen.space_1),
                dimen(R.dimen.space_3),
            )
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(requireContext()).apply {
                    text = name
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                    maxLines = 2
                })
                addView(detail, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dimen(R.dimen.space_1) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(progress)
            addView(iconButton(R.drawable.ic_edit_20, R.string.edit, onEdit))
            addView(iconButton(R.drawable.ic_delete_20, R.string.delete, onDelete))
        })
        tag = ConnectionCardViews(reference, name, type, detail, progress)
        setOnClickListener { onSelect() }
        applyCardAppearance(this, isReferenceSelected(reference), isReferenceConnected(reference))
    }

    private fun iconButton(iconRes: Int, descriptionRes: Int, action: () -> Unit): AppCompatImageButton =
        AppCompatImageButton(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                dimen(R.dimen.icon_touch_target),
                dimen(R.dimen.icon_touch_target),
            )
            val backgroundValue = TypedValue()
            requireContext().theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                backgroundValue,
                true,
            )
            setBackgroundResource(backgroundValue.resourceId)
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(
                resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant),
            )
            scaleType = ImageView.ScaleType.CENTER
            val iconPadding = (dimen(R.dimen.icon_touch_target) - dimen(R.dimen.icon_action_size)) / 2
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            contentDescription = getString(descriptionRes)
            setOnClickListener { action() }
        }

    private fun applyCardAppearance(card: MaterialCardView, selected: Boolean, connected: Boolean) {
        val state = connectionCardState(
            selected = selected,
            connected = connected,
            hasConnectedProfile = connectedProfileId != null || connectedSubscriptionProfileId != null,
        )
        val strokeAttr = when (state) {
            ConnectionCardState.CONNECTED, ConnectionCardState.SELECTED -> com.google.android.material.R.attr.colorPrimary
            ConnectionCardState.INACTIVE -> com.google.android.material.R.attr.colorOutline
        }
        card.strokeColor = resolveColor(strokeAttr)
        card.strokeWidth = when (state) {
            ConnectionCardState.CONNECTED -> dimen(R.dimen.card_border_active)
            ConnectionCardState.SELECTED, ConnectionCardState.INACTIVE -> dimen(R.dimen.card_border)
        }
        card.cardElevation = 0f
        card.alpha = 1f
        card.setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface))
        updateCardContent(card, state)
    }

    private fun resolveColor(attribute: Int): Int {
        val values = requireContext().obtainStyledAttributes(intArrayOf(attribute))
        return values.getColor(0, 0).also { values.recycle() }
    }

    private fun refreshCardAppearance() {
        for (i in 0 until binding.profileList.childCount) {
            (binding.profileList.getChildAt(i) as? MaterialCardView)?.let { card ->
                val views = card.tag as? ConnectionCardViews ?: return@let
                applyCardAppearance(
                    card,
                    isReferenceSelected(views.reference),
                    isReferenceConnected(views.reference),
                )
            }
        }
    }

    private fun updateCardContent(card: MaterialCardView, state: ConnectionCardState) {
        val views = card.tag as? ConnectionCardViews ?: return
        val status = when {
            state == ConnectionCardState.CONNECTED && currentState == VpnState.CONNECTED ->
                getString(R.string.connection_card_connected)
            (state == ConnectionCardState.CONNECTED || state == ConnectionCardState.SELECTED) &&
                currentState in CARD_PROGRESS_STATES -> vpnStateText(currentState, currentStage, currentReconnectAttempt)
            state == ConnectionCardState.SELECTED -> getString(R.string.connection_card_selected)
            else -> null
        }
        views.detail.text = status?.let {
            getString(R.string.connection_card_detail_status, views.type, it)
        } ?: views.type
        views.progress.isVisible = status != null && currentState in CARD_PROGRESS_STATES
        card.contentDescription = listOfNotNull(views.name, views.type, status).joinToString(", ")
    }

    private fun isReferenceSelected(reference: String): Boolean = when {
        reference.startsWith("local:") -> reference.removePrefix("local:").toLongOrNull() == selectedProfileId
        reference.startsWith("subscription:") -> reference.removePrefix("subscription:") == selectedSubscriptionProfileId
        else -> false
    }

    private fun isReferenceConnected(reference: String): Boolean = when {
        reference.startsWith("local:") -> reference.removePrefix("local:").toLongOrNull() == connectedProfileId
        reference.startsWith("subscription:") -> reference.removePrefix("subscription:") == connectedSubscriptionProfileId
        else -> false
    }

    private fun selectSubscriptionProfile(id: String) {
        if (currentState == VpnState.CONNECTED || currentState in BUSY_STATES) return
        selectedProfileId = null
        selectedSubscriptionProfileId = id
        showStatus(null)
        updateActionAvailability()
        refreshCardAppearance()
    }

    private fun selectProfile(id: Long) {
        if (currentState == VpnState.CONNECTED || currentState in BUSY_STATES) return
        selectedSubscriptionProfileId = null
        selectedProfileId = id
        showStatus(null)
        updateActionAvailability()
        refreshCardAppearance()
    }

    private fun connectSelected() {
        selectedProfileId?.let { activityHost.requestVpnPermission(it); return }
        selectedSubscriptionProfileId?.let { activityHost.requestSubscriptionVpnPermission(it); return }
        showStatus(getString(R.string.profile_select_required))
    }

    private fun testSelectedProfile() {
        if (currentState != VpnState.CONNECTED) {
            showStatus(getString(R.string.profile_latency_no_active_session))
            return
        }
        val host = activityHost
        val requestId = ++latencyRequestId
        val stateRevision = vpnStateRevision
        latencyInProgress = true
        updateActionAvailability()
        showStatus(getString(R.string.profile_latency_checking))
        latency.execute {
            val result = runCatching { host.testConnectionLatency().coerceAtLeast(1) }
            activity?.runOnUiThread {
                if (_binding == null || requestId != latencyRequestId) return@runOnUiThread
                latencyInProgress = false
                updateActionAvailability()
                if (!shouldShowLatencyResult(stateRevision, vpnStateRevision, currentState == VpnState.CONNECTED)) {
                    return@runOnUiThread
                }
                result.onSuccess { showStatus(getString(R.string.profile_latency_result, it)) }
                    .onFailure { showStatus(getString(latencyErrorText(latencyErrorKind(it)))) }
            }
        }
    }

    private fun showAddConnectionMenu() {
        val dialog = BottomSheetDialog(requireContext())
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dimen(R.dimen.space_6),
                dimen(R.dimen.space_5),
                dimen(R.dimen.space_6),
                dimen(R.dimen.space_6),
            )
            addView(TextView(requireContext()).apply {
                setText(R.string.add_connection)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                setPadding(0, 0, 0, dimen(R.dimen.space_4))
            })
            addView(
                addConnectionAction(
                    R.drawable.ic_scan_qr_24,
                    R.string.scan_qr,
                    R.string.scan_qr_description,
                ) {
                    dialog.dismiss()
                    requestScanner()
                },
            )
            addView(
                addConnectionAction(
                    R.drawable.ic_paste_24,
                    R.string.paste_clipboard,
                    R.string.paste_clipboard_description,
                ) {
                    dialog.dismiss()
                    pasteClipboard()
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dimen(R.dimen.space_2) },
            )
        }
        dialog.setContentView(content)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background =
                MaterialShapeDrawable(
                    ShapeAppearanceModel.builder()
                        .setTopLeftCorner(CornerFamily.ROUNDED, dimen(R.dimen.corner_sheet).toFloat())
                        .setTopRightCorner(CornerFamily.ROUNDED, dimen(R.dimen.corner_sheet).toFloat())
                        .build(),
                ).apply {
                    fillColor = ColorStateList.valueOf(resolveColor(com.google.android.material.R.attr.colorSurface))
                }
        }
        dialog.show()
    }

    private fun addConnectionAction(
        iconRes: Int,
        titleRes: Int,
        descriptionRes: Int,
        action: () -> Unit,
    ): MaterialCardView = MaterialCardView(requireContext()).apply {
        isClickable = true
        isFocusable = true
        radius = dimen(R.dimen.corner_card).toFloat()
        strokeColor = resolveColor(com.google.android.material.R.attr.colorOutline)
        strokeWidth = dimen(R.dimen.card_border)
        cardElevation = 0f
        contentDescription = "${getString(titleRes)}, ${getString(descriptionRes)}"
        addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dimen(R.dimen.space_4),
                dimen(R.dimen.space_4),
                dimen(R.dimen.space_4),
                dimen(R.dimen.space_4),
            )
            addView(ImageView(requireContext()).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(resolveColor(com.google.android.material.R.attr.colorPrimary))
                contentDescription = null
            }, LinearLayout.LayoutParams(24.dp, 24.dp))
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(requireContext()).apply {
                    setText(titleRes)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                })
                addView(TextView(requireContext()).apply {
                    setText(descriptionRes)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dimen(R.dimen.space_1) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dimen(R.dimen.space_4)
            })
        })
        setOnClickListener { action() }
    }

    private fun showVpnState(
        state: VpnState,
        error: String?,
        stage: ConnectionStage,
        reconnectAttempt: Int,
    ) {
        if (_binding == null) return
        vpnStateRevision++
        currentState = state
        currentStage = stage
        currentReconnectAttempt = reconnectAttempt
        if (state != VpnState.CONNECTED && latencyInProgress) {
            latencyRequestId++
            latencyInProgress = false
        }
        when (state) {
            VpnState.CONNECTED -> {
                val active = activityHost.activeProfileReference()
                connectedProfileId = active?.removePrefix("local:")?.takeIf { it != active }?.toLongOrNull()
                connectedSubscriptionProfileId = active?.removePrefix("subscription:")?.takeIf { it != active }
                selectedProfileId = connectedProfileId
                selectedSubscriptionProfileId = connectedSubscriptionProfileId
            }
            VpnState.DISCONNECTED, VpnState.ERROR, VpnState.NO_PROFILE -> {
                connectedProfileId = null
                connectedSubscriptionProfileId = null
            }
            else -> {}
        }
        binding.connectionSummary.text = vpnStateText(state, stage, reconnectAttempt)
        showStatus(error ?: if (state == VpnState.ERROR) getString(R.string.vpn_notification_error) else null)
        binding.connect.text = getString(
            when (state) {
                VpnState.PREPARING, VpnState.CONNECTING -> R.string.cancel_connection
                VpnState.CONNECTED, VpnState.RECONNECTING -> R.string.disconnect
                VpnState.STOPPING -> R.string.disconnecting
                VpnState.ERROR -> R.string.retry
                VpnState.NO_PROFILE, VpnState.DISCONNECTED -> R.string.connect
            },
        )
        updateActionAvailability()
        refreshCardAppearance()
    }

    private fun vpnStateText(state: VpnState, stage: ConnectionStage, reconnectAttempt: Int): String = when (state) {
        VpnState.NO_PROFILE -> getString(R.string.vpn_notification_disconnected)
        VpnState.DISCONNECTED -> getString(R.string.vpn_notification_disconnected)
        VpnState.PREPARING, VpnState.CONNECTING -> connectionStageText(stage)
        VpnState.CONNECTED -> getString(R.string.vpn_notification_connected)
        VpnState.RECONNECTING -> if (reconnectAttempt > 0) {
            getString(R.string.vpn_reconnecting_attempt, reconnectAttempt)
        } else {
            connectionStageText(stage)
        }
        VpnState.STOPPING -> getString(R.string.vpn_notification_stopping)
        VpnState.ERROR -> getString(R.string.vpn_notification_error)
    }

    private fun connectionStageText(stage: ConnectionStage): String = getString(
        when (stage) {
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

    private fun renderContentState(state: ConnectionContentState) {
        if (_binding == null) return
        val contentVisible = state == ConnectionContentState.CONTENT
        binding.contentState.isVisible = !contentVisible
        binding.profileList.isVisible = contentVisible
        binding.actionArea.isVisible = contentVisible
        binding.contentLoading.isVisible = state == ConnectionContentState.LOADING
        binding.contentStateAction.isVisible = state == ConnectionContentState.EMPTY ||
            state == ConnectionContentState.ERROR
        when (state) {
            ConnectionContentState.LOADING -> {
                binding.contentStateTitle.setText(R.string.connection_loading_title)
                binding.contentStateMessage.setText(R.string.connection_loading_message)
            }
            ConnectionContentState.EMPTY -> {
                binding.contentStateTitle.setText(R.string.connection_empty_title)
                binding.contentStateMessage.setText(R.string.connection_empty_message)
                binding.contentStateAction.setText(R.string.add_connection)
                binding.contentStateAction.setIconResource(R.drawable.ic_add_24)
                binding.contentStateAction.setOnClickListener { showAddConnectionMenu() }
            }
            ConnectionContentState.ERROR -> {
                binding.contentStateTitle.setText(R.string.connection_error_title)
                binding.contentStateMessage.setText(R.string.connection_error_message)
                binding.contentStateAction.setText(R.string.retry)
                binding.contentStateAction.icon = null
                binding.contentStateAction.setOnClickListener { loadProfiles() }
            }
            ConnectionContentState.CONTENT -> Unit
        }
    }

    private fun updateActionAvailability() {
        if (_binding == null) return
        binding.connect.isEnabled = currentState != VpnState.STOPPING && (
            currentState == VpnState.CONNECTED || currentState in BUSY_STATES ||
                selectedProfileId != null || selectedSubscriptionProfileId != null
            )
        binding.testSelected.isEnabled = currentState == VpnState.CONNECTED && !latencyInProgress
        binding.pingProgress.isVisible = latencyInProgress
        if (latencyInProgress) {
            binding.testSelected.text = ""
            binding.testSelected.icon = null
        } else {
            binding.testSelected.setText(R.string.profile_test_latency)
            binding.testSelected.setIconResource(R.drawable.ic_ping_24)
        }
    }

    private fun showStatus(message: CharSequence?) {
        val status = _binding?.status ?: return
        status.text = message
        status.isVisible = !message.isNullOrBlank()
    }

    private fun latencyErrorText(kind: LatencyErrorKind): Int = when (kind) {
        LatencyErrorKind.TIMEOUT -> R.string.profile_latency_timeout
        LatencyErrorKind.DNS -> R.string.profile_latency_dns_error
        LatencyErrorKind.NO_ACTIVE_SESSION -> R.string.profile_latency_no_active_session
        LatencyErrorKind.OTHER -> R.string.profile_latency_failed
    }

    private fun requestScanner() {
        qrScanner.launch(
            Intent(requireContext(), QrScannerActivity::class.java),
        )
    }

    private fun pasteClipboard() {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString()
        if (text.isNullOrBlank()) showStatus(getString(R.string.clipboard_empty))
        else validatePreview(text, R.string.source_clipboard)
    }

    private fun validatePreview(raw: String, source: Int) {
        storage.execute {
            val result = runCatching { parsePreview(raw) }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                showPreview(result, R.string.invalid_profile, source)
            }
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
            multipartReceived = bundle.received,
            multipartTotal = bundle.total,
        )
        is BundleImportResult.Complete -> {
            val subscriptionId = profiles.insertSubscription(bundle.bundle)
            val refresh = (activity as? MainActivity)?.refreshSubscription(subscriptionId)
                ?: SubscriptionRefresher(profiles).refreshWithChanges(subscriptionId)
            ImportPreview(
                subscriptionImported = true,
                subscriptionProfileId = profiles.listSubscriptionProfiles(subscriptionId).firstOrNull()?.id,
                subscriptionRefresh = refresh,
            )
        }
    }

    private fun saveProfile(raw: String): ImportPreview = when (val profile = ProfileUri.parse(raw)) {
        is ImportedProfile.Olcrtc -> ImportPreview(
            localProfileId = profiles.findDuplicate(profile.value) ?: profiles.insert(profile.value),
        )
        is ImportedProfile.Standard -> ImportPreview(
            localProfileId = profiles.findDuplicate(profile.value) ?: profiles.insert(profile.value),
        )
    }

    private fun showPreview(result: Result<ImportPreview>, fallbackError: Int, source: Int) {
        if (_binding == null) return
        result.onSuccess { preview ->
            if (preview.multipartReceived != null && preview.multipartTotal != null) {
                showStatus(
                    "${getString(R.string.import_source, getString(source))}\n${getString(
                        R.string.subscription_qr_progress,
                        preview.multipartReceived,
                        preview.multipartTotal,
                    )}",
                )
                return@onSuccess
            }
            selectedProfileId = preview.localProfileId
            selectedSubscriptionProfileId = preview.subscriptionProfileId
            showStatus(null)
            updateActionAvailability()
            Snackbar.make(
                binding.root,
                if (preview.subscriptionImported) R.string.subscription_imported else R.string.profile_saved,
                Snackbar.LENGTH_SHORT,
            ).show()
            loadProfiles()
            preview.subscriptionRefresh?.let(::showSubscriptionRefreshResult)
        }.onFailure { showStatus(it.message ?: getString(fallbackError)) }
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
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching { requireNotNull(profiles.get(profileId)) }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess { profile ->
                    ProfileEditorDialog.show(
                        fragment = this,
                        profile = profile,
                        onSave = { updated, dialog -> saveLocalProfile(profileId, updated, dialog) },
                        onError = { showStatus(it.message ?: getString(R.string.invalid_profile)) },
                    )
                }.onFailure { showStatus(it.message ?: getString(R.string.invalid_profile)) }
            }
        }
    }

    private fun saveLocalProfile(profileId: Long, profile: ProfileConfig, dialog: AlertDialog) {
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching {
                when (profile) {
                    is ProfileConfig.Olcrtc -> profiles.update(profileId, profile.value)
                    is ProfileConfig.Standard -> profiles.update(profileId, profile.value)
                }
            }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess {
                    dialog.dismiss()
                    loadProfiles()
                }.onFailure { showStatus(it.message ?: getString(R.string.invalid_profile)) }
            }
        }
    }

    private fun editSubscriptionProfile(profileId: String) {
        if (storage.isShutdown) return
        storage.execute {
            val result = runCatching { requireNotNull(profiles.getSubscriptionProfile(profileId)) }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess { profile ->
                    ProfileEditorDialog.show(
                        fragment = this,
                        profile = profile,
                        onSave = { updated, dialog -> saveSubscriptionProfile(profileId, updated, dialog) },
                        onError = { showStatus(it.message ?: getString(R.string.invalid_profile)) },
                    )
                }.onFailure { showStatus(it.message ?: getString(R.string.invalid_profile)) }
            }
        }
    }

    private fun saveSubscriptionProfile(profileId: String, profile: ProfileConfig, dialog: AlertDialog) {
        if (storage.isShutdown) return
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
                if (_binding == null) return@runOnUiThread
                result.onSuccess {
                    dialog.dismiss()
                    loadProfiles()
                }.onFailure { showStatus(it.message ?: getString(R.string.invalid_profile)) }
            }
        }
    }

    private fun confirmDeleteSubscriptionProfile(profileId: String, name: String) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_delete_title)
            .setMessage(getString(R.string.profile_delete_message, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (storage.isShutdown) return@setPositiveButton
                storage.execute {
                    val result = runCatching { profiles.deleteSubscriptionProfile(profileId) }
                    activity?.runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        result.onSuccess {
                            if (selectedSubscriptionProfileId == profileId) {
                                selectedSubscriptionProfileId = null
                                updateActionAvailability()
                            }
                            loadProfiles()
                        }.onFailure { showStatus(it.message ?: getString(R.string.invalid_profile)) }
                    }
                }
            }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(resolveColor(com.google.android.material.R.attr.colorError))
    }

    private fun confirmDeleteProfile(profileId: Long, name: String) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_delete_title)
            .setMessage(getString(R.string.profile_delete_message, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (storage.isShutdown) return@setPositiveButton
                storage.execute {
                    val result = runCatching { profiles.deleteLocal(profileId) }
                    activity?.runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        result.onSuccess {
                            if (selectedProfileId == profileId) {
                                selectedProfileId = null
                                updateActionAvailability()
                            }
                            loadProfiles()
                        }.onFailure { showStatus(it.message ?: getString(R.string.invalid_profile)) }
                    }
                }
            }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(resolveColor(com.google.android.material.R.attr.colorError))
    }

    private val activityHost get() = requireActivity() as MainActivity
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
    private fun dimen(resource: Int) = resources.getDimensionPixelSize(resource)

    private data class ImportPreview(
        val localProfileId: Long? = null,
        val subscriptionProfileId: String? = null,
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

    private data class ConnectionCardViews(
        val reference: String,
        val name: String,
        val type: String,
        val detail: TextView,
        val progress: CircularProgressIndicator,
    )

    companion object {
        private val BUSY_STATES = setOf(
            VpnState.PREPARING,
            VpnState.CONNECTING,
            VpnState.RECONNECTING,
            VpnState.STOPPING,
        )
        private val CARD_PROGRESS_STATES = BUSY_STATES
    }
}

internal enum class ConnectionCardState {
    CONNECTED,
    SELECTED,
    INACTIVE,
}

internal fun connectionCardState(
    selected: Boolean,
    connected: Boolean,
    hasConnectedProfile: Boolean,
): ConnectionCardState = when {
    connected -> ConnectionCardState.CONNECTED
    selected && !hasConnectedProfile -> ConnectionCardState.SELECTED
    else -> ConnectionCardState.INACTIVE
}

internal enum class ConnectionContentState {
    LOADING,
    CONTENT,
    EMPTY,
    ERROR,
}

internal fun connectionContentState(
    loading: Boolean,
    hasProfiles: Boolean,
    failed: Boolean,
): ConnectionContentState = when {
    failed -> ConnectionContentState.ERROR
    loading -> ConnectionContentState.LOADING
    hasProfiles -> ConnectionContentState.CONTENT
    else -> ConnectionContentState.EMPTY
}

internal enum class LatencyErrorKind {
    TIMEOUT,
    DNS,
    NO_ACTIVE_SESSION,
    OTHER,
}

internal fun latencyErrorKind(error: Throwable): LatencyErrorKind {
    val message = generateSequence(error) { it.cause }
        .mapNotNull(Throwable::message)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return when {
        "not connected" in message || "active session" in message || "service is not connected" in message ->
            LatencyErrorKind.NO_ACTIVE_SESSION
        "dns" in message || "lookup" in message || "no such host" in message -> LatencyErrorKind.DNS
        "timeout" in message || "timed out" in message || "deadline" in message -> LatencyErrorKind.TIMEOUT
        else -> LatencyErrorKind.OTHER
    }
}

internal fun connectionTypeLabel(type: String, endpoint: String): String = when (type.lowercase(Locale.ROOT)) {
    "olcrtc" -> when (endpoint.substringBefore('·').trim().lowercase(Locale.ROOT)) {
        "wbstream" -> "WBStream"
        "telemost" -> "Telemost"
        "jitsi" -> "Jitsi"
        else -> "olcRTC"
    }
    "vless" -> "VLESS"
    "vmess" -> "VMess"
    "trojan" -> "Trojan"
    else -> type
}

internal fun shouldShowLatencyResult(
    requestStateRevision: Long,
    currentStateRevision: Long,
    connected: Boolean,
): Boolean = connected && requestStateRevision == currentStateRevision
