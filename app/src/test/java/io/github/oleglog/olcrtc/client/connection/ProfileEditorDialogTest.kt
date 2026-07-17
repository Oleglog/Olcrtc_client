package io.github.oleglog.olcrtc.client.connection

import io.github.oleglog.olcrtc.client.data.ProfileConfig
import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileEditorDialogTest {
    private val original = ProfileConfig.Olcrtc(
        OlcrtcProfile(
            name = "WBStream",
            provider = OlcrtcProfile.Provider.WBSTREAM,
            transport = OlcrtcProfile.Transport.VP8CHANNEL,
            roomId = "room",
            clientId = "client",
            keyHex = "ab".repeat(32),
        ),
    )

    @Test
    fun asksBeforeClosingChangedOrInvalidDraft() {
        assertFalse(profileEditorIsDirty(original, original))
        assertTrue(profileEditorIsDirty(original, ProfileConfig.Olcrtc(original.value.copy(name = "Changed"))))
        assertTrue(profileEditorIsDirty(original, null))
    }
}
