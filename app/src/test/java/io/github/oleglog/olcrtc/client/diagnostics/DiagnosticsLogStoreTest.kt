package io.github.oleglog.olcrtc.client.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DiagnosticsLogStoreTest {
    @Test
    fun appendsRedactedMessagesAndExportsLogs() {
        val dir = Files.createTempDirectory("olcrtc-logs").toFile()
        val store = DiagnosticsLogStore(dir) { 1_784_000_000_000L }

        store.append("info", "Connected olcrtc://jitsi@room.example/room?k=${"a".repeat(64)}&c=client")

        val exported = store.readRedacted()
        assertTrue(exported.contains("INFO"))
        assertTrue(exported.contains("olcrtc://<redacted-profile-link>"))
        assertFalse(exported.contains("room.example"))
        assertFalse(exported.contains("client"))
        assertFalse(exported.contains("${"a".repeat(64)}"))
    }

    @Test
    fun prunesOldAndOversizedLogs() {
        var now = 10L * 24 * 60 * 60 * 1000
        val dir = Files.createTempDirectory("olcrtc-logs").toFile()
        val old = dir.resolve("old.log").apply {
            writeText("old")
            setLastModified(0)
        }
        val fresh = dir.resolve("fresh.log").apply {
            writeText("fresh-data")
            setLastModified(now)
        }
        val store = DiagnosticsLogStore(dir) { now }

        store.prune(maxAgeMillis = 7L * 24 * 60 * 60 * 1000, maxTotalBytes = 1024)
        assertFalse(old.exists())
        assertTrue(fresh.exists())

        val large = dir.resolve("large.log").apply {
            writeText("x".repeat(100))
            setLastModified(now + 1)
        }
        store.prune(maxAgeMillis = Long.MAX_VALUE, maxTotalBytes = 20)
        assertFalse(fresh.exists())
        assertTrue(large.exists())
    }
}
