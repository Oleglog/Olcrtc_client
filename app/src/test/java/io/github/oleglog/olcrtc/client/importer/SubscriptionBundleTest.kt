package io.github.oleglog.olcrtc.client.importer

import io.github.oleglog.olcrtc.client.profile.ImportedProfile
import io.github.oleglog.olcrtc.client.profile.ProfileUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

class SubscriptionBundleTest {
    private val profile = "olcrtc://jitsi@room.example/room?k=${"a".repeat(64)}&t=datachannel&c=client"

    @Test
    fun parsesCurrentManagerSubscriptionBundle() {
        val wbstream = "olcrtc://wbstream@r/room?k=${"b".repeat(64)}&t=vp8channel&f=120&b=64&c=client&a=token#WB"
        val bundle = SubscriptionBundleParser.parse(
            """{"type":"olcrtc-sub","v":2,"n":"Manager","s":"manager","u":"https://example.com/sub/manager","m":[{"t":"yandex_disk","u":"https://example.com/mirror","e":true,"a":"AES-256-GCM"}],"mk":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","uc":true,"d":true,"p":["$profile","$wbstream"]}""",
        )

        assertEquals("Manager", bundle.name)
        assertEquals("manager", bundle.slug)
        assertEquals(2, bundle.profiles.size)
        assertEquals(true, bundle.deduplication)
        assertEquals(true, bundle.updateWhenConnectedOnly)
        assertEquals("yandex_disk", bundle.mirrors.single().type)
        assertEquals(true, bundle.mirrors.single().encrypted)
        assertEquals("AES-256-GCM", bundle.mirrors.single().algorithm)
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", bundle.mirrorKey)
    }

    @Test
    fun parsesUrlOnlyManagerSubscriptionBundle() {
        val bundle = SubscriptionBundleParser.parse(
            """{"type":"olcrtc-sub","v":2,"n":"Manager","s":"manager","u":"https://example.com/sub/manager","m":[],"uc":true,"d":true}""",
        )

        assertEquals("https://example.com/sub/manager", bundle.url)
        assertTrue(bundle.profiles.isEmpty())
    }

    @Test
    fun unwrapsManagerInstanceQrResponse() {
        val uri = "olcrtc://wbstream@r/room?k=${"b".repeat(64)}&t=vp8channel&c=client&a=token"

        assertEquals(uri, ImportPayload.managerProfileUriOrNull("""{"uri":"$uri"}"""))
        assertNull(ImportPayload.managerProfileUriOrNull("""{"type":"olcrtc-sub","v":2}"""))
    }

    @Test
    fun parsesCompactBundleAndReportsRejectedProfiles() {
        val bundle = SubscriptionBundleParser.parse(
            """{"type":"olcrtc-sub","v":2,"sv":"1.9.45","n":"Test","s":"test","u":"https://example.com/sub/test","d":false,"uc":true,"p":["$profile","https://example.com/profile"]}""",
        )

        assertEquals("Test", bundle.name)
        assertEquals("1.9.45", bundle.serverVersion)
        assertEquals(1, bundle.profiles.size)
        assertTrue(bundle.profiles.single() is ImportedProfile.Olcrtc)
        assertEquals(1, bundle.rejectedProfiles.size)
        assertEquals(false, bundle.deduplication)
        assertEquals(true, bundle.updateWhenConnectedOnly)
    }

    @Test
    fun parsesVerboseLegacyAliases() {
        val bundle = SubscriptionBundleParser.parse(
            """{"type":"olcrtc_subscription_bundle","v":2,"name":"Legacy","subscription_url":"https://example.com/sub/legacy","profiles":["$profile"],"mirrors":[],"mirror_key":null,"deduplication":true,"update_when_connected_only":false}""",
        )

        assertEquals("Legacy", bundle.name)
        assertNull(bundle.serverVersion)
        assertEquals(1, bundle.profiles.size)
    }

    @Test
    fun rejectsInvalidBundleBoundaries() {
        listOf(
            """{"type":"other","v":2,"u":"https://example.com/sub","p":["$profile"]}""",
            """{"type":"olcrtc-sub","v":1,"u":"https://example.com/sub","p":["$profile"]}""",
            """{"type":"olcrtc-sub","v":2,"sv":"1.9.44","u":"https://example.com/sub","p":["$profile"]}""",
            """{"type":"olcrtc-sub","v":2,"sv":"latest","u":"https://example.com/sub","p":["$profile"]}""",
            """{"type":"olcrtc-sub","v":2,"u":"http://example.com/sub","p":["$profile"]}""",
            """{"type":"olcrtc-sub","v":2,"u":"https://example.com/sub","url":"https://example.com/other","p":["$profile"]}""",
        ).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { SubscriptionBundleParser.parse(raw) }
        }
    }

    @Test
    fun decodesBoundedGzipBundle() {
        val raw = """{"type":"olcrtc-sub","v":2,"u":"https://example.com/sub","p":["$profile"]}"""
        val packed = "olcrtc+gz:${encodeGzip(raw)}"

        assertEquals("https://example.com/sub", ImportPayload.parseBundle(packed).url)
    }

    @Test
    fun dispatchesCompressedWbstreamQrAsProfile() {
        val raw = "olcrtc://wbstream@r/room?k=${"b".repeat(64)}&t=vp8channel&c=client&a=${"token".repeat(200)}"
        val payload = ImportPayload.decode("olcrtc+gz:${encodeGzip(raw)}")

        assertTrue(payload is DecodedImportPayload.Profile)
        assertEquals(raw, payload.uri)
        assertTrue(ProfileUri.parse(payload.uri) is ImportedProfile.Olcrtc)
    }

    @Test
    fun rejectsInvalidAndTrailingGzipData() {
        val valid = gzip("{}")
        val trailing = valid + byteArrayOf(1)
        val invalid = Base64.getUrlEncoder().withoutPadding().encodeToString("not gzip".encodeToByteArray())

        assertThrows(IllegalArgumentException::class.java) { ImportPayload.decodeGzip(invalid) }
        assertThrows(IllegalArgumentException::class.java) {
            ImportPayload.decodeGzip(Base64.getUrlEncoder().withoutPadding().encodeToString(trailing))
        }
    }

    @Test
    fun rejectsDecompressedPayloadOverLimit() {
        val oversized = "a".repeat(4 * 1024 * 1024 + 1)

        assertThrows(IllegalArgumentException::class.java) { ImportPayload.decodeGzip(encodeGzip(oversized)) }
    }

    private fun encodeGzip(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(gzip(value))

    private fun gzip(value: String): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(value.encodeToByteArray()) }
        output.toByteArray()
    }
}
