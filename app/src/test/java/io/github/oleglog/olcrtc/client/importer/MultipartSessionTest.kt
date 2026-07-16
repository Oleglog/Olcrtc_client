package io.github.oleglog.olcrtc.client.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPOutputStream

class MultipartSessionTest {
    @Test
    fun dispatcherParsesManagerGzipBundleFromMultipartQr() {
        val profile = "olcrtc://wbstream@r/room?k=${"a".repeat(64)}&t=vp8channel&f=120&b=64&c=client&a=token#WB"
        val raw = """{"type":"olcrtc-sub","v":2,"n":"Manager","s":"manager","u":"https://example.com/sub/manager","m":[],"mk":"","uc":true,"d":true,"p":["$profile"]}"""
        val payload = "olcrtc+gz:${gzipBase64Url(raw)}"
        val parts = parts(payload)
        val dispatcher = BundleImportDispatcher()

        assertEquals(BundleImportResult.Pending(1, 2), dispatcher.accept(parts[0]))
        val complete = dispatcher.accept(parts[1]) as BundleImportResult.Complete
        assertEquals("Manager", complete.bundle.name)
        assertEquals(1, complete.bundle.profiles.size)
    }

    @Test
    fun assemblesPartsInAnyOrderAndIgnoresIdenticalDuplicate() {
        val payload = "olcrtc+gz:abcdef"
        val parts = parts(payload)
        val session = MultipartSession()

        assertNull(session.add(parts[1]).payload)
        assertNull(session.add(parts[1]).payload)
        assertEquals(payload, session.add(parts[0]).payload)
    }

    @Test
    fun dispatcherParsesCompletedMultipartBundle() {
        val profile = "olcrtc://jitsi@room.example/room?k=${"a".repeat(64)}&t=datachannel&c=client"
        val payload = """{"type":"olcrtc-sub","v":2,"u":"https://example.com/sub","p":["$profile"]}"""
        val parts = parts(payload)
        val dispatcher = BundleImportDispatcher()

        assertEquals(BundleImportResult.Pending(1, 2), dispatcher.accept(parts[1]))
        val complete = dispatcher.accept(parts[0]) as BundleImportResult.Complete
        assertEquals("https://example.com/sub", complete.bundle.url)
    }

    @Test
    fun dispatcherCanResetCancelledSession() {
        val first = parts("first-payload")
        val second = parts("second-payload")
        val dispatcher = BundleImportDispatcher()

        assertEquals(BundleImportResult.Pending(1, 2), dispatcher.accept(first[0]))
        dispatcher.clear()
        assertEquals(BundleImportResult.Pending(1, 2), dispatcher.accept(second[0]))
    }

    @Test
    fun rejectsConflictsOtherSessionsAndBadChecksum() {
        val first = parts("first-payload")
        val other = parts("other-payload")
        val session = MultipartSession()
        session.add(first[0])

        assertThrows(IllegalArgumentException::class.java) { session.add(other[1]) }
        val conflicting = first[0].dropLast(1) + "x"
        assertThrows(IllegalArgumentException::class.java) { session.add(conflicting) }

        val invalidHash = first.map { it.replace(Regex(":([0-9a-f]{64}):"), ":${"0".repeat(64)}:") }
        val invalidSession = MultipartSession()
        assertThrows(IllegalArgumentException::class.java) {
            invalidHash.forEach(invalidSession::add)
        }
    }

    @Test
    fun expiresIncompleteSession() {
        var now = 0L
        val session = MultipartSession { now }
        val parts = parts("payload")
        session.add(parts[0])
        now = 10 * 60 * 1000L

        assertThrows(IllegalArgumentException::class.java) { session.add(parts[1]) }
    }

    private fun gzipBase64Url(value: String): String {
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(value.encodeToByteArray()) }
            output.toByteArray()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
    }

    private fun parts(payload: String): List<String> {
        val hash = sha256(payload)
        val bundleId = hash.take(16)
        val middle = payload.length / 2
        return listOf(payload.substring(0, middle), payload.substring(middle)).mapIndexed { index, chunk ->
            "olcrtc+part:1:$bundleId:${index + 1}/2:$hash:$chunk"
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
