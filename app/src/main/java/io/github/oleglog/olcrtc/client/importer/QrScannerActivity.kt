package io.github.oleglog.olcrtc.client.importer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.databinding.ActivityQrScannerBinding
import io.github.oleglog.olcrtc.client.ui.AppearanceTheme
import java.util.concurrent.Executors

class QrScannerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityQrScannerBinding
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val multipart = MultipartSession()
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var scanning = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCamera() else finishWithError(getString(R.string.camera_permission_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppearanceTheme.apply(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.cancel.setOnClickListener { finish() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        scanning = false
        cameraProvider?.unbindAll()
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = binding.preview.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image ->
                    try {
                        if (scanning) QrFrameDecoder.decode(image)?.let(::handleResult)
                    } catch (error: Throwable) {
                        scanning = false
                        runOnUiThread { showFatalError(error.message ?: getString(R.string.camera_error)) }
                    } finally {
                        image.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
                cameraProvider = provider
                scanning = true
            }.onFailure { finishWithError(it.message ?: getString(R.string.camera_error)) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleResult(raw: String) {
        scanning = false
        if (!raw.startsWith("olcrtc+part:", ignoreCase = true)) {
            finishWithResult(raw)
            return
        }
        runCatching { multipart.add(raw) }
            .onSuccess { progress ->
                progress.payload?.let(::finishWithResult) ?: runOnUiThread {
                    binding.progress.text = getString(
                        R.string.subscription_qr_progress,
                        progress.received,
                        progress.total,
                    )
                    binding.progress.visibility = View.VISIBLE
                    scanning = true
                }
            }
            .onFailure { runOnUiThread { showFatalError(it.message ?: getString(R.string.invalid_profile)) } }
    }

    private fun showFatalError(message: String) {
        binding.progress.text = message
        binding.progress.visibility = View.VISIBLE
        binding.hint.visibility = View.GONE
        binding.cancel.setText(android.R.string.ok)
    }

    private fun finishWithResult(raw: String) {
        runOnUiThread {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT, raw))
            finish()
        }
    }

    private fun finishWithError(message: String) {
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, message))
        finish()
    }

    companion object {
        const val EXTRA_RESULT = "qr_result"
        const val EXTRA_ERROR = "qr_error"
    }
}
