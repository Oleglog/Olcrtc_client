package io.github.oleglog.olcrtc.client.importer

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SubscriptionDeepLinkTest {
    @Test
    fun parsesHttpsSubscriptionLink() {
        val source = "https://myolcrtc.mooo.com/sub/example"
        val encoded = URLEncoder.encode(source, StandardCharsets.UTF_8.name())

        val link = SubscriptionDeepLinkParser.parseOrNull(
            "olcrtc://subscription?url=$encoded&name=Example",
        )

        assertEquals(source, link?.url)
        assertEquals("Example", link?.name)
        assertNull(link?.mirrorUrl)
    }

    @Test
    fun parsesEncryptedYandexMirrorBootstrap() {
        val source = encode("https://myolcrtc.mooo.com/sub/example")
        val mirror = encode("https://disk.yandex.ru/d/example")
        val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

        val link = SubscriptionDeepLinkParser.parseOrNull(
            "olcrtc://subscription?url=$source&name=Example&mirror_type=yandex_disk&mirror_url=$mirror&mirror_key=$key",
        )

        assertEquals("yandex_disk", link?.mirrorType)
        assertEquals("https://disk.yandex.ru/d/example", link?.mirrorUrl)
        assertEquals(key, link?.mirrorKey)
    }

    @Test
    fun ignoresRegularProfileLinks() {
        assertNull(SubscriptionDeepLinkParser.parseOrNull("olcrtc://wbstream@example/room"))
    }

    @Test
    fun rejectsUnsafeSubscriptionUrls() {
        val http = URLEncoder.encode("http://example.com/sub/test", StandardCharsets.UTF_8.name())
        val credentials = URLEncoder.encode("https://user:pass@example.com/sub/test", StandardCharsets.UTF_8.name())

        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionDeepLinkParser.parseOrNull("olcrtc://subscription?url=$http")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionDeepLinkParser.parseOrNull("olcrtc://subscription?url=$credentials")
        }
    }

    @Test
    fun rejectsIncompleteMirrorBootstrap() {
        val source = encode("https://example.com/sub/test")
        val mirror = encode("https://disk.yandex.ru/d/example")

        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionDeepLinkParser.parseOrNull(
                "olcrtc://subscription?url=$source&mirror_type=yandex_disk&mirror_url=$mirror",
            )
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
