package io.github.oleglog.olcrtc.client.profiles

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import io.github.oleglog.olcrtc.client.MainActivity
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ProfileConfig
import io.github.oleglog.olcrtc.client.data.ProfileRepository
import io.github.oleglog.olcrtc.client.data.ProfileSummary
import io.github.oleglog.olcrtc.client.data.SubscriptionProfileSummary
import io.github.oleglog.olcrtc.client.data.SubscriptionSummary
import io.github.oleglog.olcrtc.client.databinding.FragmentProfilesBinding
import io.github.oleglog.olcrtc.client.importer.BundleImportDispatcher
import io.github.oleglog.olcrtc.client.importer.BundleImportResult
import io.github.oleglog.olcrtc.client.importer.QrScannerActivity
import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcProfile
import io.github.oleglog.olcrtc.client.profile.standard.StandardProfile
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
            result.data
                ?.getStringExtra(QrScannerActivity.EXTRA_RESULT)
                ?.let(::saveNewSubscription)
        } else {
            result.data
                ?.getStringExtra(QrScannerActivity.EXTRA_ERROR)
                ?.let { showError(IllegalStateException(it)) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentProfilesBinding.inflate(inflater, container, false)
        requireNotNull(_binding).addSubscription.setOnClickListener { addSubscription() }
        return requireNotNull(_binding).root
    }

    override fun onStart() {
        super.onStart()
        loadProfiles()
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
                ProfilesScreenModel(
                    local = profiles.listLocal(),
                    subscriptions = profiles.listSubscriptions(),
                )
            }
            activity?.runOnUiThread { result.onSuccess(::showProfiles) }
        }
    }

    private fun showProfiles(model: ProfilesScreenModel) {
        val binding = _binding ?: return
        val isEmpty = model.local.isEmpty() && model.subscriptions.isEmpty()
        binding.empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.profileList.removeAllViews()
        model.local.takeIf { it.isNotEmpty() }?.let { local ->
            binding.profileList.addView(sectionTitle(getString(R.string.profile_group_local)))
            local.forEach { binding.profileList.addView(localProfileCard(it)) }
        }
        model.subscriptions.forEach { subscription ->
            binding.profileList.addView(sectionTitle(subscriptionHeader(subscription)))
            val card = materialCard().apply {
                addView(verticalContainer().apply {
                    addView(subscriptionActions(subscription))
                })
            }
            binding.profileList.addView(card)
            storage.execute {
                val result = runCatching { profiles.listSubscriptionProfiles(subscription.id) }
                activity?.runOnUiThread {
                    val current = _binding ?: return@runOnUiThread
                    val innerContainer = (card.getChildAt(0) as? LinearLayout) ?: return@runOnUiThread
                    result.getOrDefault(emptyList()).forEach { item ->
                        innerContainer.addView(subscriptionProfileRow(item))
                    }
                }
            }
        }
    }

    private fun materialCard(): com.google.android.material.card.MaterialCardView =
        com.google.android.material.card.MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 8.dp
            }
        }

    private fun verticalContainer(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dp, 12.dp, 16.dp, 12.dp)
    }

    private fun localProfileCard(profile: ProfileSummary): View = materialCard().apply {
        addView(verticalContainer().apply {
            addView(TextView(requireContext()).apply {
                text = profile.name
                setTextAppearance(android.R.style.TextAppearance_Material_Title)
            })
            addView(TextView(requireContext()).apply {
                text = "${profile.type} · ${profile.endpoint}"
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                setPadding(0, 4.dp, 0, 8.dp)
            })
            addView(horizontalRow().apply {
                addView(Button(requireContext()).apply {
                    setText(R.string.connect)
                    setOnClickListener {
                        activityHost.requestVpnPermission(profile.id)
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(requireContext()).apply {
                    setText(R.string.edit)
                    setOnClickListener { editLocalProfile(profile) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(requireContext()).apply {
                    setText(R.string.delete)
                    setOnClickListener { confirmDelete(profile) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        })
    }

    private fun subscriptionProfileRow(profile: SubscriptionProfileSummary): View =
        materialCard().apply {
            addView(verticalContainer().apply {
                val flags = listOfNotNull(
                    profile.type,
                    profile.lastLatencyMs?.let { "${it}ms" },
                    if (profile.locallyModified) {
                        getString(R.string.profile_locally_modified)
                    } else {
                        null
                    },
                ).joinToString(" · ")
                addView(TextView(requireContext()).apply {
                    text = profile.name
                    setTextAppearance(android.R.style.TextAppearance_Material_Title)
                })
                addView(TextView(requireContext()).apply {
                    text = "$flags · ${profile.endpoint}"
                    setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                    setPadding(0, 4.dp, 0, 8.dp)
                })
                addView(Button(requireContext()).apply {
                    setText(R.string.connect)
                    setOnClickListener {
                        activityHost.requestSubscriptionVpnPermission(profile.id)
                    }
                })
            })
        }

    private fun addSubscription() {
        qrScanner.launch(
            Intent(requireContext(), QrScannerActivity::class.java),
        )
    }

    private fun saveNewSubscription(raw: String) {
        storage.execute {
            val result = runCatching {
                if (raw.startsWith("{") || raw.startsWith("olcrtc+gz:", true)) {
                    val bundle = bundleImports.accept(raw) as BundleImportResult.Complete
                    profiles.insertSubscription(bundle.bundle)
                } else {
                    val uri = java.net.URI(raw)
                    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
                        getString(R.string.subscription_https_required)
                    }
                    profiles.insertSubscriptionSource(
                        name = requireNotNull(uri.host),
                        url = raw,
                        kind = "GENERIC",
                    )
                }.also { SubscriptionRefresher(profiles).refresh(it) }
            }
            activity?.runOnUiThread {
                result.onFailure(::showError)
                loadProfiles()
            }
        }
    }

    private fun subscriptionActions(subscription: SubscriptionSummary): View {
        val container = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        container.addView(horizontalRow().apply {
            setPadding(0, 4.dp, 0, 4.dp)
            addView(Button(requireContext()).apply {
                setText(R.string.subscription_update_now)
                setOnClickListener { updateSubscription(subscription.id) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(requireContext()).apply {
                setText(R.string.subscription_details)
                setOnClickListener { showSubscriptionDetails(subscription) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        container.addView(horizontalRow().apply {
            setPadding(0, 0, 0, 4.dp)
            addView(Button(requireContext()).apply {
                setText(R.string.edit)
                setOnClickListener { editSubscription(subscription) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(requireContext()).apply {
                setText(R.string.delete)
                setOnClickListener { confirmDeleteSubscription(subscription) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        return container
    }

    private fun sectionTitle(textValue: String): TextView = TextView(requireContext()).apply {
        text = textValue
        setTextAppearance(android.R.style.TextAppearance_Material_Medium)
        setPadding(0, 18.dp, 0, 6.dp)
    }

    private fun horizontalRow(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, 8.dp, 0, 8.dp)
    }

    private fun subscriptionHeader(subscription: SubscriptionSummary): String = buildString {
        append(subscription.name).append(" · ").append(subscription.kind)
        append(" · ").append(subscription.profileCount).append(' ').append(getString(R.string.profiles_count_suffix))
        subscription.serverVersion?.let {
            append(" · ").append(getString(R.string.subscription_server_badge, it))
        }
        subscription.lastErrorCode?.let { append(" · ").append(it) }
        subscription.lastSuccessAt?.let {
            append(" · ").append(getString(R.string.subscription_last_success, formatTime(it)))
        }
        if (subscription.mirrorAvailable) {
            append(" · ").append(getString(R.string.subscription_mirror_badge))
        }
        if (!subscription.enabled) {
            append(" · ").append(getString(R.string.subscription_disabled_badge))
        }
    }

    private fun showSubscriptionDetails(subscription: SubscriptionSummary) {
        storage.execute {
            val result = runCatching { profiles.getSubscriptionSource(subscription.id) }
            activity?.runOnUiThread {
                result.onSuccess { source ->
                    AlertDialog.Builder(requireContext())
                        .setTitle(subscription.name)
                        .setMessage(
                            getString(
                                R.string.subscription_details_format,
                                subscription.kind,
                                subscription.profileCount,
                                subscription.serverVersion ?: getString(R.string.value_unknown),
                                source?.url ?: getString(R.string.value_unknown),
                                source?.mirrorType ?: getString(R.string.value_none),
                                subscription.lastSuccessAt?.let(::formatTime) ?: getString(R.string.value_never),
                                subscription.lastAttemptAt?.let(::formatTime) ?: getString(R.string.value_never),
                                subscription.lastErrorCode ?: getString(R.string.value_none),
                            ),
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }.onFailure { showError(it) }
            }
        }
    }

    private fun editSubscription(subscription: SubscriptionSummary) {
        storage.execute {
            val result = runCatching { profiles.getSubscriptionSource(subscription.id) }
            activity?.runOnUiThread {
                result.onSuccess { source ->
                    val input = EditText(requireContext()).apply {
                        hint = getString(R.string.subscription_edit_hint)
                        setSingleLine(false)
                        minLines = 2
                        setText("name=${subscription.name}\nurl=${source?.url.orEmpty()}")
                    }
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.edit)
                        .setView(input)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            saveSubscriptionEdit(subscription.id, input.text?.toString().orEmpty())
                        }
                        .show()
                }.onFailure { showError(it) }
            }
        }
    }

    private fun saveSubscriptionEdit(subscriptionId: Long, raw: String) {
        storage.execute {
            val result = runCatching {
                val values = parseKeyValueLines(raw)
                profiles.updateSubscriptionSource(
                    subscriptionId,
                    values["name"].orEmpty(),
                    values["url"].orEmpty(),
                )
            }
            activity?.runOnUiThread {
                result.onFailure { showError(it) }
                loadProfiles()
            }
        }
    }

    private fun editLocalProfile(profile: ProfileSummary) {
        storage.execute {
            val result = runCatching { requireNotNull(profiles.get(profile.id)) }
            activity?.runOnUiThread {
                result.onSuccess { config ->
                    when (config) {
                        is ProfileConfig.Olcrtc -> showOlcrtcEditor(profile.id, config.value)
                        is ProfileConfig.Standard -> showStandardEditor(profile.id, config.value)
                    }
                }.onFailure(::showError)
            }
        }
    }

    private fun showOlcrtcEditor(profileId: Long, profile: OlcrtcProfile) {
        val form = verticalContainer()
        val name = formField(form, R.string.profile_field_name, profile.name)
        val provider = formSpinner(
            form,
            R.string.profile_field_provider,
            OlcrtcProfile.Provider.entries,
            profile.provider,
        )
        val transport = formSpinner(
            form,
            R.string.profile_field_transport,
            OlcrtcProfile.Transport.entries,
            profile.transport,
        )
        val roomId = formField(form, R.string.profile_field_room_id, profile.roomId)
        val roomPassword = formField(
            form,
            R.string.profile_field_room_password,
            profile.roomPassword.orEmpty(),
            secret = true,
        )
        val clientId = formField(form, R.string.profile_field_client_id, profile.clientId)
        val key = formField(form, R.string.profile_field_key, profile.keyHex)
        val auth = formField(
            form,
            R.string.profile_field_auth_token,
            profile.authToken.orEmpty(),
            secret = true,
        )
        val dns = formField(form, R.string.profile_field_dns, profile.dnsServer.orEmpty())
        val fps = formField(form, R.string.profile_field_vp8_fps, profile.vp8Fps.toString(), numeric = true)
        val batch = formField(form, R.string.profile_field_vp8_batch, profile.vp8BatchSize.toString(), numeric = true)
        val keepalive = formField(
            form,
            R.string.profile_field_keepalive,
            profile.keepaliveIntervalSeconds.toString(),
            numeric = true,
        )
        showSecureEditor(profileId, profile.name, form) {
            ProfileConfig.Olcrtc(
                OlcrtcProfile(
                    name = name.value(),
                    provider = provider.selectedItem as OlcrtcProfile.Provider,
                    transport = transport.selectedItem as OlcrtcProfile.Transport,
                    roomId = roomId.value(),
                    roomPassword = roomPassword.optionalValue(),
                    clientId = clientId.value(),
                    keyHex = key.value(),
                    authToken = auth.optionalValue(),
                    dnsServer = dns.optionalValue(),
                    vp8Fps = fps.intValue(),
                    vp8BatchSize = batch.intValue(),
                    keepaliveIntervalSeconds = keepalive.intValue(),
                ),
            )
        }
    }

    private fun showStandardEditor(profileId: Long, profile: StandardProfile) {
        val form = verticalContainer()
        val name = formField(form, R.string.profile_field_name, profile.name)
        val protocol = formSpinner(
            form,
            R.string.profile_field_protocol,
            StandardProfile.Protocol.entries,
            profile.protocol,
        )
        val address = formField(form, R.string.profile_field_address, profile.address)
        val port = formField(form, R.string.profile_field_port, profile.port.toString(), numeric = true)
        val uuid = formField(form, R.string.profile_field_uuid, profile.uuid.orEmpty())
        val password = formField(
            form,
            R.string.profile_field_password,
            profile.password.orEmpty(),
            secret = true,
        )
        val alterId = formField(form, R.string.profile_field_alter_id, profile.alterId.toString(), numeric = true)
        val cipher = formField(form, R.string.profile_field_cipher, profile.cipher)
        val transport = formSpinner(
            form,
            R.string.profile_field_transport,
            StandardProfile.Transport.entries,
            profile.transport,
        )
        val security = formSpinner(
            form,
            R.string.profile_field_security,
            StandardProfile.Security.entries,
            profile.security,
        )
        val flow = formField(form, R.string.profile_field_flow, profile.flow.orEmpty())
        val serverName = formField(form, R.string.profile_field_server_name, profile.serverName.orEmpty())
        val alpn = formField(form, R.string.profile_field_alpn, profile.alpn.joinToString(","))
        val fingerprint = formField(form, R.string.profile_field_fingerprint, profile.fingerprint.orEmpty())
        val allowInsecure = CheckBox(requireContext()).apply {
            setText(R.string.profile_field_allow_insecure)
            isChecked = profile.allowInsecure
        }.also(form::addView)
        val realityKey = formField(form, R.string.profile_field_reality_key, profile.realityPublicKey.orEmpty())
        val realityShortId = formField(form, R.string.profile_field_reality_short_id, profile.realityShortId.orEmpty())
        val realitySpiderX = formField(form, R.string.profile_field_reality_spider_x, profile.realitySpiderX.orEmpty())
        val wsHost = formField(form, R.string.profile_field_ws_host, profile.webSocketHost.orEmpty())
        val wsPath = formField(form, R.string.profile_field_ws_path, profile.webSocketPath.orEmpty())
        val grpcService = formField(form, R.string.profile_field_grpc_service, profile.grpcServiceName.orEmpty())
        val xhttpMode = formField(form, R.string.profile_field_xhttp_mode, profile.xhttpMode.orEmpty())
        val xhttpHost = formField(form, R.string.profile_field_xhttp_host, profile.xhttpHost.orEmpty())
        val xhttpPath = formField(form, R.string.profile_field_xhttp_path, profile.xhttpPath.orEmpty())
        val xhttpExtra = formField(form, R.string.profile_field_xhttp_extra, profile.xhttpExtraJson.orEmpty())
        val dns = formField(form, R.string.profile_field_dns, profile.dnsServer.orEmpty())
        val conditionalFields = listOf(
            uuid, password, alterId, cipher, flow, realityKey, realityShortId,
            realitySpiderX, wsHost, wsPath, grpcService, xhttpMode, xhttpHost,
            xhttpPath, xhttpExtra,
        )
        fun updateVisibility() {
            conditionalFields.forEach { it.layout.visibility = View.GONE }
            when (protocol.selectedItem as StandardProfile.Protocol) {
                StandardProfile.Protocol.VLESS -> uuid.show()
                StandardProfile.Protocol.VMESS -> {
                    uuid.show()
                    alterId.show()
                    cipher.show()
                }
                StandardProfile.Protocol.TROJAN -> password.show()
            }
            when (transport.selectedItem as StandardProfile.Transport) {
                StandardProfile.Transport.TCP -> Unit
                StandardProfile.Transport.WS -> {
                    wsHost.show()
                    wsPath.show()
                }
                StandardProfile.Transport.GRPC -> grpcService.show()
                StandardProfile.Transport.XHTTP -> {
                    xhttpMode.show()
                    xhttpHost.show()
                    xhttpPath.show()
                    xhttpExtra.show()
                }
            }
            if (security.selectedItem == StandardProfile.Security.REALITY) {
                realityKey.show()
                realityShortId.show()
                realitySpiderX.show()
                if (
                    protocol.selectedItem == StandardProfile.Protocol.VLESS &&
                    transport.selectedItem == StandardProfile.Transport.TCP
                ) {
                    flow.show()
                }
            }
        }
        protocol.onSelectionChanged(::updateVisibility)
        transport.onSelectionChanged(::updateVisibility)
        security.onSelectionChanged(::updateVisibility)
        updateVisibility()
        showSecureEditor(profileId, profile.name, form) {
            val selectedProtocol = protocol.selectedItem as StandardProfile.Protocol
            val selectedTransport = transport.selectedItem as StandardProfile.Transport
            val selectedSecurity = security.selectedItem as StandardProfile.Security
            ProfileConfig.Standard(
                StandardProfile(
                    name = name.value(),
                    protocol = selectedProtocol,
                    address = address.value(),
                    port = port.intValue(),
                    uuid = if (selectedProtocol == StandardProfile.Protocol.TROJAN) null else uuid.optionalValue(),
                    password = if (selectedProtocol == StandardProfile.Protocol.TROJAN) password.optionalValue() else null,
                    alterId = if (selectedProtocol == StandardProfile.Protocol.VMESS) alterId.intValue() else 0,
                    cipher = if (selectedProtocol == StandardProfile.Protocol.VMESS) cipher.value() else "auto",
                    transport = selectedTransport,
                    security = selectedSecurity,
                    flow = if (
                        selectedProtocol == StandardProfile.Protocol.VLESS &&
                        selectedTransport == StandardProfile.Transport.TCP &&
                        selectedSecurity == StandardProfile.Security.REALITY
                    ) flow.optionalValue() else null,
                    serverName = serverName.optionalValue(),
                    alpn = alpn.value().split(',').map(String::trim).filter(String::isNotEmpty),
                    fingerprint = fingerprint.optionalValue(),
                    allowInsecure = allowInsecure.isChecked,
                    realityPublicKey = if (selectedSecurity == StandardProfile.Security.REALITY) realityKey.optionalValue() else null,
                    realityShortId = if (selectedSecurity == StandardProfile.Security.REALITY) realityShortId.optionalValue() else null,
                    realitySpiderX = if (selectedSecurity == StandardProfile.Security.REALITY) realitySpiderX.optionalValue() else null,
                    webSocketHost = if (selectedTransport == StandardProfile.Transport.WS) wsHost.optionalValue() else null,
                    webSocketPath = if (selectedTransport == StandardProfile.Transport.WS) wsPath.optionalValue() else null,
                    grpcServiceName = if (selectedTransport == StandardProfile.Transport.GRPC) grpcService.optionalValue() else null,
                    xhttpMode = if (selectedTransport == StandardProfile.Transport.XHTTP) xhttpMode.optionalValue() else null,
                    xhttpHost = if (selectedTransport == StandardProfile.Transport.XHTTP) xhttpHost.optionalValue() else null,
                    xhttpPath = if (selectedTransport == StandardProfile.Transport.XHTTP) xhttpPath.optionalValue() else null,
                    xhttpExtraJson = if (selectedTransport == StandardProfile.Transport.XHTTP) xhttpExtra.optionalValue() else null,
                    dnsServer = dns.optionalValue(),
                ),
            )
        }
    }

    private fun showSecureEditor(
        profileId: Long,
        title: String,
        form: LinearLayout,
        build: () -> ProfileConfig,
    ) {
        val scroll = ScrollView(requireContext()).apply { addView(form) }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.profile_edit_title, title))
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updated = runCatching(build)
                updated.onFailure(::showError)
                updated.onSuccess { config ->
                    storage.execute {
                        val result = runCatching {
                            when (config) {
                                is ProfileConfig.Olcrtc -> profiles.update(profileId, config.value)
                                is ProfileConfig.Standard -> profiles.update(profileId, config.value)
                            }
                        }
                        activity?.runOnUiThread {
                            result.onSuccess {
                                dialog.dismiss()
                                loadProfiles()
                            }.onFailure(::showError)
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun formField(
        form: LinearLayout,
        label: Int,
        value: String,
        numeric: Boolean = false,
        secret: Boolean = false,
    ): FormField {
        val input = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            setText(value)
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT
            }
        }
        val layout = com.google.android.material.textfield.TextInputLayout(requireContext()).apply {
            hint = getString(label)
            addView(input)
        }
        form.addView(layout)
        return FormField(layout, input)
    }

    private fun <T> formSpinner(form: LinearLayout, label: Int, values: List<T>, selected: T): Spinner {
        form.addView(TextView(requireContext()).apply {
            setText(label)
            setPadding(0, 8.dp, 0, 0)
        })
        return Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, values)
            setSelection(values.indexOf(selected))
        }.also(form::addView)
    }

    private fun Spinner.onSelectionChanged(action: () -> Unit) {
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = action()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun updateSubscription(subscriptionId: Long) {
        storage.execute {
            val result = runCatching { SubscriptionRefresher(profiles).refresh(subscriptionId) }
            activity?.runOnUiThread {
                result.onFailure { showError(it) }
                loadProfiles()
            }
        }
    }

    private fun confirmDelete(profile: ProfileSummary) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_delete_title)
            .setMessage(getString(R.string.profile_delete_message, profile.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                storage.execute {
                    runCatching { profiles.deleteLocal(profile.id) }
                    activity?.runOnUiThread(::loadProfiles)
                }
            }
            .show()
    }

    private fun confirmDeleteSubscription(subscription: SubscriptionSummary) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.subscription_delete_title)
            .setMessage(getString(R.string.subscription_delete_message, subscription.name))
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.subscription_keep_profiles) { _, _ -> deleteSubscription(subscription.id, true) }
            .setPositiveButton(R.string.delete) { _, _ -> deleteSubscription(subscription.id, false) }
            .show()
    }

    private fun deleteSubscription(subscriptionId: Long, retainProfiles: Boolean) {
        storage.execute {
            val result = runCatching { profiles.deleteSubscription(subscriptionId, retainProfiles) }
            activity?.runOnUiThread {
                result.onFailure { showError(it) }
                loadProfiles()
            }
        }
    }

    private fun showError(error: Throwable) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.invalid_profile)
            .setMessage(error.message ?: error.javaClass.simpleName)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun parseKeyValueLines(raw: String): Map<String, String> = raw.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .associate { line ->
            val parts = line.split('=', limit = 2)
            require(parts.size == 2) { "Invalid line: $line" }
            parts[0].trim().lowercase() to parts[1].trim()
        }

    private fun formatTime(value: Long): String = DateFormat.getDateTimeInstance(
        DateFormat.SHORT,
        DateFormat.SHORT,
    ).format(Date(value))

    private data class FormField(
        val layout: com.google.android.material.textfield.TextInputLayout,
        val input: com.google.android.material.textfield.TextInputEditText,
    ) {
        fun value(): String = input.text?.toString()?.trim().orEmpty()
        fun optionalValue(): String? = value().takeIf(String::isNotEmpty)
        fun intValue(): Int = value().toIntOrNull()
            ?: throw IllegalArgumentException("${layout.hint} is invalid")
        fun show() {
            layout.visibility = View.VISIBLE
        }
    }

    private data class ProfilesScreenModel(
        val local: List<ProfileSummary>,
        val subscriptions: List<SubscriptionSummary>,
    )

    private val activityHost get() = requireActivity() as MainActivity
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
