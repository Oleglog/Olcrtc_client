package io.github.oleglog.olcrtc.client.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRedactorTest {
    @Test
    fun redactsRawProfileLinks() {
        val raw = "Imported olcrtc://jitsi@room.example/room?k=${"a".repeat(64)}&t=datachannel&c=client&a=token and vless://00000000-0000-0000-0000-000000000001@example.com:443?security=tls#Name"

        val redacted = DiagnosticsRedactor.redact(raw)

        assertTrue(redacted.contains("olcrtc://<redacted-profile-link>"))
        assertTrue(redacted.contains("vless://<redacted-profile-link>"))
        assertFalse(redacted.contains("room.example"))
        assertFalse(redacted.contains("client"))
        assertFalse(redacted.contains("token"))
        assertFalse(redacted.contains("00000000-0000-0000-0000-000000000001"))
    }

    @Test
    fun redactsJsonSecretsAndUuid() {
        val raw = """
            {"key":"${"b".repeat(64)}","auth_token":"secret","client_id":"client-1","password":"pass","uuid":"00000000-0000-0000-0000-000000000001","mk":"mirror"}
        """.trimIndent()

        val redacted = DiagnosticsRedactor.redact(raw)

        assertFalse(redacted.contains("${"b".repeat(64)}"))
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("client-1"))
        assertFalse(redacted.contains("\"pass\""))
        assertFalse(redacted.contains("00000000-0000-0000-0000-000000000001"))
        assertFalse(redacted.contains("mirror"))
        assertTrue(redacted.contains("\"auth_token\":\"<redacted>\""))
    }

    @Test
    fun redactsSubscriptionUrlQueryAndHeaders() {
        val raw = "GET https://example.com/sub/demo?token=secret&user=oleg Authorization: Bearer abc.def X-Api-Key: key-value"

        val redacted = DiagnosticsRedactor.redact(raw)

        assertTrue(redacted.contains("https://example.com/sub/demo?<redacted-query>"))
        assertTrue(redacted.contains("Authorization: Bearer <redacted>"))
        assertTrue(redacted.contains("X-Api-Key: <redacted>"))
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("abc.def"))
        assertFalse(redacted.contains("key-value"))
    }

    @Test
    fun redactsLooseHexKeysAndQueryAliases() {
        val raw = "k=${"c".repeat(64)}&c=client&a=token&mk=mirror"

        val redacted = DiagnosticsRedactor.redact(raw)

        assertFalse(redacted.contains("${"c".repeat(64)}"))
        assertFalse(redacted.contains("client"))
        assertFalse(redacted.contains("token"))
        assertFalse(redacted.contains("mirror"))
    }

    @Test
    fun redactsCamelCaseJsonAndQuerySecrets() {
        val raw = """{"keyHex":"${"d".repeat(64)}","clientId":"my-client","roomId":"my-room","roomPassword":"s3cret","rp":"alias-secret"}?roomId=my-room&rp=alias-secret&keyHex=${"d".repeat(64)}"""

        val redacted = DiagnosticsRedactor.redact(raw)

        assertFalse(redacted.contains("${"d".repeat(64)}"))
        assertFalse(redacted.contains("my-client"))
        assertFalse(redacted.contains("my-room"))
        assertFalse(redacted.contains("s3cret"))
        assertFalse(redacted.contains("alias-secret"))
    }

    @Test
    fun redactsGoCoreFieldForm() {
        val raw = """RunWithReady args mismatch: transport="vp8channel" carrier="wbstream" room="secret-room" client="secret-client" keyHex=${"e".repeat(64)}"""

        val redacted = DiagnosticsRedactor.redact(raw)

        assertTrue(redacted.contains("transport=\"vp8channel\""))
        assertTrue(redacted.contains("carrier=\"wbstream\""))
        assertFalse(redacted.contains("secret-room"))
        assertFalse(redacted.contains("secret-client"))
        assertFalse(redacted.contains("${"e".repeat(64)}"))
    }

    @Test
    fun leavesProseWithoutSeparatorIntact() {
        val raw = "device ready, token refreshed, client connected"

        assertEquals(raw, DiagnosticsRedactor.redact(raw))
    }
}
