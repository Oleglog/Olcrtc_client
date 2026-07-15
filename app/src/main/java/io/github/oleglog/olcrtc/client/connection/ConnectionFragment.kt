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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
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

    private val qrImagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::decodeQrImage)
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::readImportFile)
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startScanner() else binding.status.setText(R.string.camera_permission_denied)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentConnectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        binding.connect.setOnClickListener {
            if (currentState == VpnState.CONNECTED) activityHost.stopVpn() else if (currentState !in BUSY_STATES) importAndConnect()
        }
        binding.pasteClipboard.setOnClickListener { pasteClipboard() }
        binding.scanQr.setOnClickListener { if (scanning) stopScanner() else requestScanner() }
        binding.importImage.setOnClickListener {
            qrImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.importFile.setOnClickListener {
            filePicker.launch(arrayOf("text/plain", "application/octet-stream"))
        }
        storage.execute { SubscriptionRefresher(profiles).refreshStale() }
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

    private fun showVpnState(state: VpnState, error: String?) {
        if (_binding == null) return
        currentState = state
        binding.status.text = error ?: state.name
        binding.connect.text = getString(if (state == VpnState.CONNECTED) R.string.disconnect else R.string.import_and_connect)
        binding.connect.isEnabled = state !in BUSY_STATES
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
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = binding.cameraPreview.surfaceProvider }
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
                provider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                cameraProvider = provider
                scanning = true
                binding.cameraPreview.visibility = View.VISIBLE
                binding.profileUri.visibility = View.GONE
                binding.scanQr.setText(R.string.stop_scan)
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
            binding.status.text = "${getString(R.string.import_source, getString(source))}\n$description"
        }.onFailure { binding.status.text = it.message ?: getString(fallbackError) }
    }

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
                result.onSuccess(activityHost::requestVpnPermission)
                    .onFailure { binding.status.text = it.message ?: getString(R.string.invalid_profile) }
            }
        }
    }

    private val activityHost get() = requireActivity() as MainActivity

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
