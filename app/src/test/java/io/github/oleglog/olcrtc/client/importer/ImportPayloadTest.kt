package io.github.oleglog.olcrtc.client.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPayloadTest {
    @Test
    fun classifiesHttpsSubscriptionQrWithoutTreatingItAsProfile() {
        val payload = ImportPayload.decode("https://vpn.example/sub/user?token=secret")

        assertTrue(payload is DecodedImportPayload.Subscription)
        assertEquals("https://vpn.example/sub/user?token=secret", (payload as DecodedImportPayload.Subscription).url)
    }

    @Test
    fun rejectsUnsafeSubscriptionQrUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            ImportPayload.decode("http://vpn.example/sub/user")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImportPayload.decode("https://user:password@vpn.example/sub/user")
        }
    }
}
