package io.github.oleglog.olcrtc.client.importer

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

internal object QrFrameDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(
            BarcodeFormat.QR_CODE,
            BarcodeFormat.DATA_MATRIX,
            BarcodeFormat.AZTEC,
        ),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    fun decode(image: ImageProxy): String? {
        require(image.format == ImageFormat.YUV_420_888) { "Unsupported camera image format" }
        val crop = image.cropRect
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val luminance = ByteArray(crop.width() * crop.height())
        val base = buffer.position()
        var output = 0
        for (y in crop.top until crop.bottom) {
            for (x in crop.left until crop.right) {
                val index = base + y * plane.rowStride + x * plane.pixelStride
                require(index < buffer.limit()) { "Invalid camera image plane" }
                luminance[output++] = buffer.get(index)
            }
        }
        val rotated = rotate(
            luminance,
            crop.width(),
            crop.height(),
            image.imageInfo.rotationDegrees,
        )
        val width = if (image.imageInfo.rotationDegrees % 180 == 0) crop.width() else crop.height()
        val height = if (image.imageInfo.rotationDegrees % 180 == 0) crop.height() else crop.width()
        val source = PlanarYUVLuminanceSource(
            rotated,
            width,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        return decode(source) ?: decode(source.invert())
    }

    internal fun rotate(
        source: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ): ByteArray {
        require(source.size == width * height) { "Invalid luminance dimensions" }
        require(rotationDegrees in setOf(0, 90, 180, 270)) { "Unsupported camera rotation" }
        if (rotationDegrees == 0) return source
        val rotated = ByteArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val destination = when (rotationDegrees) {
                    90 -> x * height + (height - 1 - y)
                    180 -> (height - 1 - y) * width + (width - 1 - x)
                    else -> (width - 1 - x) * height + y
                }
                rotated[destination] = source[y * width + x]
            }
        }
        return rotated
    }

    private fun decode(source: LuminanceSource): String? {
        val reader = MultiFormatReader().apply { setHints(hints) }
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            null
        } finally {
            reader.reset()
        }
    }
}
