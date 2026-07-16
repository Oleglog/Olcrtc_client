package io.github.oleglog.olcrtc.client.importer

import io.github.oleglog.olcrtc.client.profile.ImportedProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionPayloadTest {
    private val vless = "vless://00000000-0000-0000-0000-000000000001@example.com:443?encryption=none&type=tcp&security=none#VLESS"
    private val olcrtc = "olcrtc://jitsi@room.example/room?k=${"a".repeat(64)}&t=datachannel&c=client"

    @Test
    fun parsesPlainAndBase64Lists() {
        val plain = "# profiles\n\n$vless\n$olcrtc\n"
        val encoded = Base64.getEncoder().encode(plain.encodeToByteArray())

        val direct = SubscriptionPayload.parse(plain.encodeToByteArray())
        val decoded = SubscriptionPayload.parse(encoded)

        assertEquals(2, direct.profiles.size)
        assertTrue(direct.profiles[0] is ImportedProfile.Standard)
        assertTrue(direct.profiles[1] is ImportedProfile.Olcrtc)
        assertEquals(direct, decoded)
    }

    @Test
    fun reportsUnsupportedLinesAndKeepsSupportedProfiles() {
        val result = SubscriptionPayload.parse("https://example.com/profile\n$vless\n".encodeToByteArray())

        assertEquals(1, result.profiles.size)
        assertEquals(1, result.rejectedProfiles.size)
        assertTrue(result.rejectedProfiles.single().startsWith("line 1:"))
    }

    @Test
    fun acceptsEmptySubscription() {
        val result = SubscriptionPayload.parse("# no active profiles\n".encodeToByteArray())

        assertTrue(result.profiles.isEmpty())
        assertTrue(result.rejectedProfiles.isEmpty())
    }

    @Test
    fun rejectsInvalidUtf8UnsupportedOnlyAndOversizedLists() {
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionPayload.parse(byteArrayOf(0xc3.toByte(), 0x28))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionPayload.parse("https://example.com/not-a-profile\n".encodeToByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionPayload.parse((vless + "x".repeat(16 * 1024)).encodeToByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionPayload.parse(ByteArray(4 * 1024 * 1024 + 1))
        }
    }

    @Test
    fun decryptsGoCompatibleMirrorVector() {
        val envelope = """{"type":"olcrtc-sub-mirror","v":1,"alg":"AES-256-GCM","nonce":"AAECAwQFBgcICQoL","ciphertext":"MW6zaLbf7TS9cae7gdlIXa7mtwTAVm9MCFfItS1ZMJ8xIJ7Mn_EiqESUT9zI4lBZgykM6HS1zLcFox4qJ4abjYJFtg66vkhccjvEC8n7d5hdt70BBkUgDZ6I7KUMiYTjpJY8xfINjUnNFpotQnPJFF1dzjsCJdcwhgs"}"""

        val result = SubscriptionPayload.decryptMirror(
            envelope.encodeToByteArray(),
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
        )

        assertEquals(1, result.profiles.size)
        assertTrue(result.profiles.single() is ImportedProfile.Standard)
    }

    @Test
    fun rejectsInvalidMirrorEnvelopeAndAuthentication() {
        val ciphertext = "MW6zaLbf7TS9cae7gdlIXa7mtwTAVm9MCFfItS1ZMJ8xIJ7Mn_EiqESUT9zI4lBZgykM6HS1zLcFox4qJ4abjYJFtg66vkhccjvEC8n7d5hdt70BBkUgDZ6I7KUMiYTjpJY8xfINjUnNFpotQnPJFF1dzjsCJdcwhgs"
        val valid = """{"type":"olcrtc-sub-mirror","v":1,"alg":"AES-256-GCM","nonce":"AAECAwQFBgcICQoL","ciphertext":"$ciphertext"}"""
        val extra = valid.dropLast(1) + ",\"extra\":true}"

        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionPayload.decryptMirror(valid.encodeToByteArray(), "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionPayload.decryptMirror(extra.encodeToByteArray(), "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionPayload.decryptMirror(valid.encodeToByteArray(), "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
        }
    }
}
