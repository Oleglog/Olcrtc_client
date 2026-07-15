package io.github.oleglog.olcrtc.client.connection

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.appcompat.app.AlertDialog
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import io.github.oleglog.olcrtc.client.MainActivity
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ProfileRepository
import io.github.oleglog.olcrtc.client.databinding.FragmentConnectionBinding
import io.github.oleglog.olcrtc.client.importer.BundleImportDispatcher
import io.github.oleglog.olcrtc.client.importer.BundleImportResult
import io.github.oleglog.olcrtc.client.importer.QrFrameDecoder
import io.github.oleglog.olcrtc.client.importer.QrImageDecoder
import io.github.oleglog.olcrtc.client.profile.ImportedProfile
import io.github.oleglog.olcrtc.client.profile.ProfileUri
import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcProfile
import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcUri
import io.github.oleglog.olcrtc.client.subscription.SubscriptionRefresher
import io.github.oleglog.olcrtc.client.vpn.VpnState
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors

class ConnectionFragment : Fragment() {
    private var _binding: FragmentConnectionBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val profiles by lazy { ProfileRepository.open(requireContext().applicationContext) }
    private val storage = Executors.newSingleThreadExecutor()
    private val cameraAnalysis = Executors.newSingleThreadExecutor()
    private val bundleImports = BundleImportDispatcher()
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var scanning = false
    private var currentState = VpnState.NO_PROFILE
    private var selectedProfileId: Long? = null
    private var selectedSubscriptionProfileId: String? = null

    private val qrImagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::decodeQrImage)
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::readImportFile)
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startScanner() else _binding?.status?.setText(R.string.camera_permission_denied)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentConnectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding.connect.setOnClickListener {
            if (currentState == VpnState.CONNECTED) activityHost.stopVpn() else if (currentState !in BUSY_STATES) connectSelectedOrImport()
        }
        binding.pasteClipboard.setOnClickListener { pasteClipboard() }
        binding.scanQr.setOnClickListener { if (scanning) stopScanner() else requestScanner() }
        binding.importImage.setOnClickListener {
            qrImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.importFile.setOnClickListener {
            filePicker.launch(arrayOf("text/plain", "application/octet-stream"))
        }
        binding.manualOlcrtc.setOnClickListener { showManualOlcrtcDialog() }
        binding.manualStandard.setOnClickListener { showManualStandardDialog() }
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
        stopScanner()
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
        cameraAnalysis.shutdownNow()
        super.onDestroy()
    }


    private fun loadProfiles() {
        storage.execute {
            val result = runCatching { profiles.listLocal() to profiles.listSubscriptions().flatMap { profiles.listSubscriptionProfiles(it.id) } }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess { (localItems, subscriptionItems) ->
                    binding.profileList.removeAllViews()
                    if (localItems.isEmpty() && subscriptionItems.isEmpty()) {
                        binding.profileList.addView(TextView(requireContext()).apply {
                            setText(R.string.profiles_placeholder)
                            setPadding(0, 12.dp, 0, 12.dp)
                        })
                    } else {
                        if (selectedProfileId == null && selectedSubscriptionProfileId == null && localItems.isNotEmpty()) selectProfile(localItems.first().id, localItems.first().name, localItems.first().type, localItems.first().endpoint)
                        localItems.forEach { profile -> binding.profileList.addView(profileCard(profile.id, profile.name, profile.type, profile.endpoint)) }
                        subscriptionItems.forEach { profile -> binding.profileList.addView(subscriptionProfileCard(profile.id, profile.name, profile.type, profile.endpoint)) }
                    }
                }.onFailure { binding.status.text = it.message }
            }
        }
    }

    private fun profileCard(id: Long, name: String, type: String, endpoint: String): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dp, 14.dp, 16.dp, 14.dp)
        isClickable = true
        isFocusable = true
        addView(TextView(requireContext()).apply {
            text = name
            textSize = 18f
        })
        addView(TextView(requireContext()).apply { text = "$type - $endpoint" })
        setOnClickListener { selectProfile(id, name, type, endpoint) }
    }


    private fun subscriptionProfileCard(id: String, name: String, type: String, endpoint: String): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dp, 14.dp, 16.dp, 14.dp)
        isClickable = true
        isFocusable = true
        addView(TextView(requireContext()).apply {
            text = name
            textSize = 18f
        })
        addView(TextView(requireContext()).apply { text = "$type - $endpoint" })
        setOnClickListener { selectSubscriptionProfile(id, name, type, endpoint) }
    }

    private fun selectSubscriptionProfile(id: String, name: String, type: String, endpoint: String) {
        selectedProfileId = null
        selectedSubscriptionProfileId = id
        binding.selectedProfile.text = "$name\n$type - $endpoint"
        binding.status.text = getString(R.string.connection_selected_profile, name)
        binding.connect.isEnabled = currentState !in BUSY_STATES
    }

    private fun selectProfile(id: Long, name: String, type: String, endpoint: String) {
        selectedSubscriptionProfileId = null
        selectedProfileId = id
        binding.selectedProfile.text = "$name\n$type - $endpoint"
        binding.status.text = getString(R.string.connection_selected_profile, name)
        binding.connect.isEnabled = currentState !in BUSY_STATES
    }

    private fun connectSelectedOrImport() {
        selectedProfileId?.let { activityHost.requestVpnPermission(it); return }
        selectedSubscriptionProfileId?.let { activityHost.requestSubscriptionVpnPermission(it); return }
        importAndConnect()
    }

    private fun testSelectedProfile() {
        val localId = selectedProfileId
        val subscriptionId = selectedSubscriptionProfileId
        storage.execute {
            val result = runCatching {
                when {
                    localId != null -> profiles.testLocalProfileLatency(localId)
                    subscriptionId != null -> profiles.testSubscriptionProfileLatency(subscriptionId)
                    else -> throw IllegalArgumentException(getString(R.string.no_profile))
                }
            }
            activity?.runOnUiThread {
                result.onSuccess { binding.status.text = getString(R.string.profile_latency_result, it) }
                    .onFailure { binding.status.text = it.message ?: getString(R.string.invalid_profile) }
            }
        }
    }

    private fun showAddConnectionMenu() {
        val actions = arrayOf(
            getString(R.string.paste_clipboard),
            getString(R.string.scan_qr),
            getString(R.string.import_qr_image),
            getString(R.string.import_file),
            getString(R.string.manual_olcrtc),
            getString(R.string.manual_standard),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.add_connection)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> pasteClipboard()
                    1 -> requestScanner()
                    2 -> qrImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    3 -> filePicker.launch(arrayOf("text/plain", "application/octet-stream"))
                    4 -> showManualOlcrtcDialog()
                    5 -> showManualStandardDialog()
                }
            }
            .show()
    }    private fun showVpnState(state: VpnState, error: String?) {
        if (_binding == null) return
        currentState = state
        binding.status.text = error ?: state.name
        binding.connect.text = getString(if (state == VpnState.CONNECTED) R.string.disconnect else R.string.connect)
        binding.connect.isEnabled = state !in BUSY_STATES && (state == VpnState.CONNECTED || selectedProfileId != null || !binding.profileUri.text.isNullOrBlank())
    }

    private fun requestScanner() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScanner()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startScanner() {
        val providerFuture = ProcessCameraProvider.getInstance(requireContext())
        providerFuture.addListener({
            val currentBinding = _binding ?: return@addListener
            runCatching {
                val provider = providerFuture.get()
                val cameraSelector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                val preview = Preview.Builder().build().also { it.surfaceProvider = currentBinding.cameraPreview.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraAnalysis) { image ->
                    try {
                        if (scanning) QrFrameDecoder.decode(image)?.let { raw ->
                            scanning = false
                            activity?.runOnUiThread {
                                stopScanner()
                                validatePreview(raw, R.string.source_camera)
                            }
                        }
                    } finally {
                        image.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, analysis)
                cameraProvider = provider
                scanning = true
                currentBinding.cameraPreview.visibility = View.VISIBLE
                currentBinding.profileUri.visibility = View.GONE
                currentBinding.scanQr.setText(R.string.stop_scan)
            }.onFailure {
                stopScanner()
                binding.status.text = it.message ?: getString(R.string.camera_error)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun stopScanner() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        scanning = false
        _binding?.let {
            it.cameraPreview.visibility = View.GONE
            it.profileUri.visibility = View.VISIBLE
            it.scanQr.setText(R.string.scan_qr)
        }
    }

    private fun pasteClipboard() {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString()
        if (text.isNullOrBlank()) binding.status.setText(R.string.clipboard_empty)
        else validatePreview(text, R.string.source_clipboard)
    }

    private fun decodeQrImage(uri: Uri) {
        val resolver = requireContext().applicationContext.contentResolver
        storage.execute {
            val result = runCatching { parsePreview(QrImageDecoder.decode(resolver, uri)) }
            activity?.runOnUiThread { showPreview(result, R.string.qr_image_error, R.string.source_qr_image) }
        }
    }

    private fun readImportFile(uri: Uri) {
        val resolver = requireContext().applicationContext.contentResolver
        val fileError = getString(R.string.import_file_error)
        val tooLargeError = getString(R.string.import_file_too_large)
        storage.execute {
            val result = runCatching { parsePreview(readText(resolver, uri, fileError, tooLargeError)) }
            activity?.runOnUiThread { showPreview(result, R.string.import_file_error, R.string.source_file) }
        }
    }

    private fun validatePreview(raw: String, source: Int) {
        storage.execute {
            val result = runCatching { parsePreview(raw) }
            activity?.runOnUiThread { showPreview(result, R.string.invalid_profile, source) }
        }
    }

    private fun parsePreview(raw: String): ImportPreview {
        val value = raw.trim()
        return if (value.startsWith("{") || value.startsWith("olcrtc+gz:", true) || value.startsWith("olcrtc+part:", true)) {
            when (val bundle = bundleImports.accept(value)) {
                is BundleImportResult.Pending -> ImportPreview("Multipart QR ${bundle.received}/${bundle.total}", null)
                is BundleImportResult.Complete -> {
                    profiles.insertSubscription(bundle.bundle)
                    ImportPreview("${bundle.bundle.name}: ${bundle.bundle.profiles.size} profiles", null)
                }
            }
        } else {
            val description = when (val profile = ProfileUri.parse(value)) {
                is ImportedProfile.Olcrtc -> "olcRTC: ${profile.value.name}"
                is ImportedProfile.Standard -> "${profile.value.protocol.name}: ${profile.value.name}"
            }
            ImportPreview(description, value)
        }
    }

    private fun showPreview(result: Result<ImportPreview>, fallbackError: Int, source: Int) {
        if (_binding == null) return
        result.onSuccess { preview ->
            preview.profileUri?.let(binding.profileUri::setText)
            val description = if (preview.profileUri == null) preview.description
            else "${preview.description}\n${getString(R.string.import_preview)}"
            selectedProfileId = null
            selectedSubscriptionProfileId = null
            binding.selectedProfile.text = preview.description
            binding.connect.isEnabled = preview.profileUri != null && currentState !in BUSY_STATES
            binding.status.text = "${getString(R.string.import_source, getString(source))}\n$description"
        }.onFailure { binding.status.text = it.message ?: getString(fallbackError) }
    }

    private fun showManualOlcrtcDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.manual_olcrtc_hint)
            setSingleLine(false)
            minLines = 8
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manual_profile_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runCatching { buildManualOlcrtcUri(input.text?.toString().orEmpty()) }
                    .onSuccess { raw ->
                        binding.profileUri.setText(raw)
                        validatePreview(raw, R.string.manual_olcrtc)
                    }
                    .onFailure { binding.status.text = it.message }
            }
            .show()
    }

    private fun showManualStandardDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.manual_standard_hint)
            setSingleLine(false)
            minLines = 3
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manual_profile_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val raw = input.text?.toString().orEmpty().trim()
                binding.profileUri.setText(raw)
                validatePreview(raw, R.string.manual_standard)
            }
            .show()
    }

    private fun buildManualOlcrtcUri(raw: String): String {
        val values = raw.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .associate { line ->
                val parts = line.split('=', limit = 2)
                require(parts.size == 2) { "Invalid line: $line" }
                parts[0].trim().lowercase() to parts[1].trim()
            }
        val profile = OlcrtcProfile(
            name = values["name"].orEmpty().ifBlank { "olcRTC" },
            provider = OlcrtcProfile.Provider.parse(values.required("provider")),
            transport = OlcrtcProfile.Transport.parse(values.required("transport")),
            roomId = values.required("room"),
            roomPassword = values["password"]?.takeIf(String::isNotBlank),
            clientId = values.required("client"),
            keyHex = values.required("key"),
            authToken = values["auth"]?.takeIf(String::isNotBlank),
            dnsServer = values["dns"]?.takeIf(String::isNotBlank),
        )
        return OlcrtcUri.serialize(profile, includeAuthToken = true)
    }

    private fun Map<String, String>.required(name: String): String =
        get(name)?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("$name is required")

    private fun readText(
        resolver: ContentResolver,
        uri: Uri,
        fileError: String,
        tooLargeError: String,
    ): String {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_IMPORT_FILE_BYTES) { tooLargeError }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IOException(fileError)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun importAndConnect() {
        val raw = binding.profileUri.text?.toString().orEmpty().trim()
        storage.execute {
            val result = runCatching {
                when (val profile = ProfileUri.parse(raw)) {
                    is ImportedProfile.Olcrtc -> profiles.findDuplicate(profile.value) ?: profiles.insert(profile.value)
                    is ImportedProfile.Standard -> profiles.findDuplicate(profile.value) ?: profiles.insert(profile.value)
                }
            }
            activity?.runOnUiThread {
                result.onSuccess { id ->
                    loadProfiles()
                    activityHost.requestVpnPermission(id)
                }
                    .onFailure { binding.status.text = it.message ?: getString(R.string.invalid_profile) }
            }
        }
    }

    private val activityHost get() = requireActivity() as MainActivity
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private data class ImportPreview(val description: String, val profileUri: String?)

    companion object {
        private const val MAX_IMPORT_FILE_BYTES = 4 * 1024 * 1024
        private val BUSY_STATES = setOf(
            VpnState.PREPARING,
            VpnState.CONNECTING,
            VpnState.RECONNECTING,
            VpnState.STOPPING,
        )
    }
}
