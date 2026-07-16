package io.github.oleglog.olcrtc.client.importer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class QrFrameDecoderTest {
    private val source = byteArrayOf(
        1, 2, 3,
        4, 5, 6,
    )

    @Test
    fun rotatesClockwise() {
        assertArrayEquals(
            byteArrayOf(4, 1, 5, 2, 6, 3),
            QrFrameDecoder.rotate(source, 3, 2, 90),
        )
    }

    @Test
    fun rotatesHalfTurn() {
        assertArrayEquals(
            byteArrayOf(6, 5, 4, 3, 2, 1),
            QrFrameDecoder.rotate(source, 3, 2, 180),
        )
    }

    @Test
    fun rotatesCounterClockwise() {
        assertArrayEquals(
            byteArrayOf(3, 6, 2, 5, 1, 4),
            QrFrameDecoder.rotate(source, 3, 2, 270),
        )
    }

    @Test
    fun keepsUnrotatedBuffer() {
        assertSame(source, QrFrameDecoder.rotate(source, 3, 2, 0))
    }
}
