package io.github.oleglog.olcrtc.client.profile

import io.github.oleglog.olcrtc.client.profile.standard.StandardProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileUriTest {
    private val uuid = "00000000-0000-0000-0000-000000000001"

    @Test
    fun dispatchesSupportedSchemes() {
        val olcrtc = ProfileUri.parse(
            "olcrtc://jitsi@room.example/room?k=${"a".repeat(64)}&t=datachannel&c=client",
        )
        val standard = ProfileUri.parse(
            "vless://$uuid@example.com:443?encryption=none&type=ws&security=tls&path=%2Fws",
        )

        assertTrue(olcrtc is ImportedProfile.Olcrtc)
        assertEquals(
            StandardProfile.Protocol.VLESS,
            (standard as ImportedProfile.Standard).value.protocol,
        )
    }

    @Test
    fun rejectsUnsupportedScheme() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileUri.parse("https://example.com/profile")
        }
    }
}
