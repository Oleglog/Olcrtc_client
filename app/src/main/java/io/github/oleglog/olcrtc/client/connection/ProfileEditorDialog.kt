package io.github.oleglog.olcrtc.client.connection

import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ProfileConfig
import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcProfile
import io.github.oleglog.olcrtc.client.profile.standard.StandardProfile

internal object ProfileEditorDialog {
    fun show(
        fragment: Fragment,
        profile: ProfileConfig,
        onSave: (ProfileConfig, AlertDialog) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val context = fragment.requireContext()
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(fragment), 8.dp(fragment), 24.dp(fragment), 0)
        }
        val build = when (profile) {
            is ProfileConfig.Olcrtc -> olcrtcForm(fragment, form, profile.value)
            is ProfileConfig.Standard -> standardForm(fragment, form, profile.value)
        }
        val title = when (profile) {
            is ProfileConfig.Olcrtc -> profile.value.name
            is ProfileConfig.Standard -> profile.value.name
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(fragment.getString(R.string.profile_edit_title, title))
            .setView(ScrollView(context).apply { addView(form) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching(build).onSuccess { onSave(it, dialog) }.onFailure(onError)
            }
        }
        dialog.show()
    }

    private fun olcrtcForm(
        fragment: Fragment,
        form: LinearLayout,
        profile: OlcrtcProfile,
    ): () -> ProfileConfig {
        val name = field(fragment, form, R.string.profile_field_name, profile.name)
        val provider = choice(fragment, form, R.string.profile_field_provider, OlcrtcProfile.Provider.entries, profile.provider)
        val transport = choice(fragment, form, R.string.profile_field_transport, OlcrtcProfile.Transport.entries, profile.transport)
        val roomId = field(fragment, form, R.string.profile_field_room_id, profile.roomId)
        val roomPassword = field(fragment, form, R.string.profile_field_room_password, profile.roomPassword.orEmpty(), secret = true)
        val clientId = field(fragment, form, R.string.profile_field_client_id, profile.clientId)
        val key = field(fragment, form, R.string.profile_field_key, profile.keyHex, secret = true)
        val auth = field(fragment, form, R.string.profile_field_auth_token, profile.authToken.orEmpty(), secret = true)
        val dns = field(fragment, form, R.string.profile_field_dns, profile.dnsServer.orEmpty())
        val fps = field(fragment, form, R.string.profile_field_vp8_fps, profile.vp8Fps.toString(), numeric = true)
        val batch = field(fragment, form, R.string.profile_field_vp8_batch, profile.vp8BatchSize.toString(), numeric = true)
        val keepalive = field(
            fragment,
            form,
            R.string.profile_field_keepalive,
            profile.keepaliveIntervalSeconds.toString(),
            numeric = true,
        )
        return {
            ProfileConfig.Olcrtc(
                OlcrtcProfile(
                    name = name.value(),
                    provider = provider.selected,
                    transport = transport.selected,
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

    private fun standardForm(
        fragment: Fragment,
        form: LinearLayout,
        profile: StandardProfile,
    ): () -> ProfileConfig {
        val name = field(fragment, form, R.string.profile_field_name, profile.name)
        val protocol = choice(fragment, form, R.string.profile_field_protocol, StandardProfile.Protocol.entries, profile.protocol)
        val address = field(fragment, form, R.string.profile_field_address, profile.address)
        val port = field(fragment, form, R.string.profile_field_port, profile.port.toString(), numeric = true)
        val uuid = field(fragment, form, R.string.profile_field_uuid, profile.uuid.orEmpty())
        val password = field(fragment, form, R.string.profile_field_password, profile.password.orEmpty(), secret = true)
        val alterId = field(fragment, form, R.string.profile_field_alter_id, profile.alterId.toString(), numeric = true)
        val cipher = field(fragment, form, R.string.profile_field_cipher, profile.cipher)
        val transport = choice(fragment, form, R.string.profile_field_transport, StandardProfile.Transport.entries, profile.transport)
        val security = choice(fragment, form, R.string.profile_field_security, StandardProfile.Security.entries, profile.security)
        val flow = field(fragment, form, R.string.profile_field_flow, profile.flow.orEmpty())
        val serverName = field(fragment, form, R.string.profile_field_server_name, profile.serverName.orEmpty())
        val alpn = field(fragment, form, R.string.profile_field_alpn, profile.alpn.joinToString(","))
        val fingerprint = field(fragment, form, R.string.profile_field_fingerprint, profile.fingerprint.orEmpty())
        val allowInsecure = CheckBox(fragment.requireContext()).apply {
            setText(R.string.profile_field_allow_insecure)
            isChecked = profile.allowInsecure
        }.also(form::addView)
        val realityKey = field(fragment, form, R.string.profile_field_reality_key, profile.realityPublicKey.orEmpty())
        val realityShortId = field(fragment, form, R.string.profile_field_reality_short_id, profile.realityShortId.orEmpty())
        val realitySpiderX = field(fragment, form, R.string.profile_field_reality_spider_x, profile.realitySpiderX.orEmpty())
        val wsHost = field(fragment, form, R.string.profile_field_ws_host, profile.webSocketHost.orEmpty())
        val wsPath = field(fragment, form, R.string.profile_field_ws_path, profile.webSocketPath.orEmpty())
        val grpcService = field(fragment, form, R.string.profile_field_grpc_service, profile.grpcServiceName.orEmpty())
        val xhttpMode = field(fragment, form, R.string.profile_field_xhttp_mode, profile.xhttpMode.orEmpty())
        val xhttpHost = field(fragment, form, R.string.profile_field_xhttp_host, profile.xhttpHost.orEmpty())
        val xhttpPath = field(fragment, form, R.string.profile_field_xhttp_path, profile.xhttpPath.orEmpty())
        val xhttpExtra = field(fragment, form, R.string.profile_field_xhttp_extra, profile.xhttpExtraJson.orEmpty())
        val dns = field(fragment, form, R.string.profile_field_dns, profile.dnsServer.orEmpty())
        val conditional = listOf(
            uuid, password, alterId, cipher, flow, realityKey, realityShortId,
            realitySpiderX, wsHost, wsPath, grpcService, xhttpMode, xhttpHost,
            xhttpPath, xhttpExtra,
        )
        fun updateVisibility() {
            conditional.forEach { it.layout.visibility = View.GONE }
            when (protocol.selected) {
                StandardProfile.Protocol.VLESS -> uuid.show()
                StandardProfile.Protocol.VMESS -> {
                    uuid.show()
                    alterId.show()
                    cipher.show()
                }
                StandardProfile.Protocol.TROJAN -> password.show()
            }
            when (transport.selected) {
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
            if (security.selected == StandardProfile.Security.REALITY) {
                realityKey.show()
                realityShortId.show()
                realitySpiderX.show()
                if (
                    protocol.selected == StandardProfile.Protocol.VLESS &&
                    transport.selected == StandardProfile.Transport.TCP
                ) flow.show()
            }
        }
        protocol.onSelectionChanged(::updateVisibility)
        transport.onSelectionChanged(::updateVisibility)
        security.onSelectionChanged(::updateVisibility)
        updateVisibility()
        return {
            val selectedProtocol = protocol.selected
            val selectedTransport = transport.selected
            val selectedSecurity = security.selected
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

    private fun field(
        fragment: Fragment,
        form: LinearLayout,
        label: Int,
        value: String,
        numeric: Boolean = false,
        secret: Boolean = false,
    ): FormField {
        val input = TextInputEditText(fragment.requireContext()).apply {
            setText(value)
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT
            }
        }
        val layout = TextInputLayout(fragment.requireContext()).apply {
            hint = fragment.getString(label)
            if (secret) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(input)
        }
        form.addView(layout)
        return FormField(layout, input)
    }

    private fun <T> choice(
        fragment: Fragment,
        form: LinearLayout,
        label: Int,
        values: List<T>,
        selected: T,
    ): ChoiceField<T> {
        val input = MaterialAutoCompleteTextView(fragment.requireContext()).apply {
            inputType = InputType.TYPE_NULL
            setAdapter(ArrayAdapter(fragment.requireContext(), android.R.layout.simple_list_item_1, values))
            setText(selected.toString(), false)
        }
        val layout = TextInputLayout(fragment.requireContext()).apply {
            hint = fragment.getString(label)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            addView(input)
        }
        form.addView(layout)
        return ChoiceField(input, values, selected)
    }

    private class ChoiceField<T>(
        input: MaterialAutoCompleteTextView,
        private val values: List<T>,
        selected: T,
    ) {
        var selected: T = selected
            private set
        private var onSelectionChanged: () -> Unit = {}

        init {
            input.setOnItemClickListener { _, _, position, _ ->
                this.selected = values[position]
                onSelectionChanged()
            }
        }

        fun onSelectionChanged(action: () -> Unit) {
            onSelectionChanged = action
        }
    }

    private data class FormField(
        val layout: TextInputLayout,
        val input: TextInputEditText,
    ) {
        fun value(): String = input.text?.toString()?.trim().orEmpty()
        fun optionalValue(): String? = value().takeIf(String::isNotEmpty)
        fun intValue(): Int = value().toIntOrNull()
            ?: throw IllegalArgumentException("${layout.hint} is invalid")
        fun show() {
            layout.visibility = View.VISIBLE
        }
    }

    private fun Int.dp(fragment: Fragment): Int =
        (this * fragment.resources.displayMetrics.density).toInt()
}
