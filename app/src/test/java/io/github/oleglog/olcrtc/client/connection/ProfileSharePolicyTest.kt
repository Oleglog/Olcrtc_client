package io.github.oleglog.olcrtc.client.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSharePolicyTest {
    @Test
    fun detectsOlcrtcLinkWithSecrets() {
        assertTrue(
            ProfileSharePolicy.containsSecrets(
                "olcrtc://wbstream@r/room?k=${"a".repeat(64)}&t=vp8channel&c=client",
            ),
        )
    }

    @Test
    fun detectsStandardLinks() {
        assertTrue(
            ProfileSharePolicy.containsSecrets(
                "vless://00000000-0000-0000-0000-000000000001@example.com:443?security=tls#Name",
            ),
        )
        assertTrue(
            ProfileSharePolicy.containsSecrets("ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@example.com:8388#Name"),
        )
    }

    @Test
    fun ignoresSubscriptionLinksAndPlainText() {
        assertFalse(ProfileSharePolicy.containsSecrets("https://example.com/sub/demo?token=secret"))
        assertFalse(ProfileSharePolicy.containsSecrets("just some text"))
        assertFalse(
            ProfileSharePolicy.containsSecrets(
                "olcrtc://jitsi@r/room?t=datachannel",
            ),
        )
    }

    @Test
    fun shouldClearOnlyOwnClipboardContent() {
        assertTrue(
            ProfileSharePolicy.shouldClearClipboard(
                ProfileSharePolicy.CLIP_LABEL,
                "payload",
                ProfileSharePolicy.CLIP_LABEL,
                "payload",
            ),
        )
        assertFalse(
            ProfileSharePolicy.shouldClearClipboard(
                ProfileSharePolicy.CLIP_LABEL,
                "payload",
                null,
                "payload",
            ),
        )
        assertFalse(
            ProfileSharePolicy.shouldClearClipboard(
                ProfileSharePolicy.CLIP_LABEL,
                "payload",
                ProfileSharePolicy.CLIP_LABEL,
                "other",
            ),
        )
        assertFalse(
            ProfileSharePolicy.shouldClearClipboard(
                ProfileSharePolicy.CLIP_LABEL,
                "payload",
                "other-app",
                "payload",
            ),
        )
    }
}
