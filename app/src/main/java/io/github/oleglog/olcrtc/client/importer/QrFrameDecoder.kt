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
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    fun decode(image: ImageProxy): String? = try {
        decodeInternal(image)
    } catch (_: Throwable) {
        null
    }

    private fun decodeInternal(image: ImageProxy): String? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val luminance = ByteArray(width * height)
        val buffer = plane.buffer
        buffer.rewind()
        if (plane.pixelStride == 1 && plane.rowStride == width) {
            buffer.get(luminance)
        } else {
            val row = ByteArray(plane.rowStride)
            for (y in 0 until height) {
                buffer.position(y * plane.rowStride)
                val length = minOf(plane.rowStride, buffer.remaining())
                buffer.get(row, 0, length)
                for (x in 0 until width) luminance[y * width + x] = row[x * plane.pixelStride]
            }
        }
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        val reader = MultiFormatReader().apply { setHints(hints) }
        var rotated: LuminanceSource = source
        repeat(image.imageInfo.rotationDegrees / 90) { rotated = rotated.rotateCounterClockwise() }
        return decode(reader, rotated)
    }

    private fun decode(reader: MultiFormatReader, source: LuminanceSource): String? = try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: NotFoundException) {
        null
    } finally {
        reader.reset()
    }
}
