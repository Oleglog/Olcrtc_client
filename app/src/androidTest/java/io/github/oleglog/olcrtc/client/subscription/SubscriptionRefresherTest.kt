package io.github.oleglog.olcrtc.client.subscription

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.oleglog.olcrtc.client.data.ClientDatabase
import io.github.oleglog.olcrtc.client.data.ProfileRepository
import io.github.oleglog.olcrtc.client.data.SecretCipher
import io.github.oleglog.olcrtc.client.importer.SubscriptionBundle
import io.github.oleglog.olcrtc.client.profile.ImportedProfile
import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcProfile
import io.github.oleglog.olcrtc.client.profile.standard.StandardProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class SubscriptionRefresherTest {
    private lateinit var database: ClientDatabase
    private lateinit var repository: ProfileRepository

    @Before
    fun setUp() {
        database = ClientDatabase.inMemory(ApplicationProvider.getApplicationContext())
        repository = ProfileRepository(
            database.olcrtcProfiles(),
            database.standardProfiles(),
            database.subscriptions(),
            database.routingRules(),
            SecretCipher(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun refreshesOnlyStaleSubscriptions() {
        val staleId = repository.insertSubscription(bundle(), now = 1)
        val freshId = repository.insertSubscription(bundle("https://example.com/fresh-sub"), now = 2)
        repository.markSubscriptionRefresh(freshId, errorCode = null, now = 10, successful = true)
        val payload = "vless://00000000-0000-0000-0000-000000000001@example.com:443?encryption=none&type=tcp&security=none#VLESS"
        var requests = 0
        val refresher = SubscriptionRefresher(
            repository,
            userHttp = SubscriptionHttpClient {
                requests++
                FakeConnection(200, payload.encodeToByteArray())
            },
            strictHttp = SubscriptionHttpClient { FakeConnection(500) },
        )

        assertEquals(1, refresher.refreshStale(now = 11))

        assertEquals(1, requests)
        assertEquals(11L, database.subscriptions().getSubscription(staleId)!!.lastSuccessAt)
        assertEquals(10L, database.subscriptions().getSubscription(freshId)!!.lastSuccessAt)
    }

    @Test
    fun failedRefreshPreservesProfiles() {
        val subscriptionId = repository.insertSubscription(bundle(), now = 1)
        val before = repository.getSubscriptionProfiles(subscriptionId)
        val refresher = SubscriptionRefresher(
            repository,
            userHttp = SubscriptionHttpClient { FakeConnection(200, "invalid".encodeToByteArray()) },
            strictHttp = SubscriptionHttpClient { FakeConnection(500) },
        )

        assertFalse(refresher.refresh(subscriptionId, now = 2))

        val subscription = database.subscriptions().getSubscription(subscriptionId)!!
        assertEquals(before, repository.getSubscriptionProfiles(subscriptionId))
        assertEquals(2L, subscription.lastAttemptAt)
        assertEquals("INVALID_PAYLOAD", subscription.lastErrorCode)
    }

    @Test
    fun fallsBackToStrictYandexMirror() {
        val subscriptionId = repository.insertSubscription(bundle(), now = 1)
        val envelope = """{"type":"olcrtc-sub-mirror","v":1,"alg":"AES-256-GCM","nonce":"AAECAwQFBgcICQoL","ciphertext":"MW6zaLbf7TS9cae7gdlIXa7mtwTAVm9MCFfItS1ZMJ8xIJ7Mn_EiqESUT9zI4lBZgykM6HS1zLcFox4qJ4abjYJFtg66vkhccjvEC8n7d5hdt70BBkUgDZ6I7KUMiYTjpJY8xfINjUnNFpotQnPJFF1dzjsCJdcwhgs"}"""
        val strictResponses = ArrayDeque(listOf(
            FakeConnection(200, """{"href":"https://download.example.com/mirror"}""".encodeToByteArray()),
            FakeConnection(200, envelope.encodeToByteArray()),
        ))
        val refresher = SubscriptionRefresher(
            repository,
            userHttp = SubscriptionHttpClient { FakeConnection(500) },
            strictHttp = SubscriptionHttpClient { strictResponses.removeFirst() },
        )

        val result = refresher.refreshWithChanges(subscriptionId, now = 3)

        val subscription = database.subscriptions().getSubscription(subscriptionId)!!
        assertTrue(result.success)
        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        assertEquals(1, result.total)
        assertEquals(1, repository.getSubscriptionProfiles(subscriptionId).size)
        assertTrue(repository.getSubscriptionProfiles(subscriptionId).single() is ImportedProfile.Standard)
        assertEquals(3L, subscription.lastSuccessAt)
        assertEquals(3L, subscription.lastAttemptAt)
        assertEquals(null, subscription.lastErrorCode)
    }

    @Test
    fun manualRefreshIgnoresCachedValidators() {
        val subscriptionId = repository.insertSubscription(bundle(), now = 1)
        repository.replaceSubscriptionProfiles(
            subscriptionId,
            repository.getSubscriptionProfiles(subscriptionId),
            now = 2,
            etag = "stale-etag",
            lastModified = "stale-date",
        )
        val payload = "vless://00000000-0000-0000-0000-000000000001@example.com:443?encryption=none&type=tcp&security=none#VLESS"
        val connection = FakeConnection(200, payload.encodeToByteArray())
        val refresher = SubscriptionRefresher(
            repository,
            userHttp = SubscriptionHttpClient { connection },
            strictHttp = SubscriptionHttpClient { FakeConnection(500) },
        )

        assertTrue(refresher.refreshWithChanges(subscriptionId, now = 3).success)

        assertEquals(null, connection.requestHeaders["If-None-Match"])
        assertEquals(null, connection.requestHeaders["If-Modified-Since"])
    }

    @Test
    fun manualRefreshRestoresDeletedProfileAndReportsAddition() {
        val profile = ImportedProfile.Standard(StandardProfile(
            name = "VLESS",
            protocol = StandardProfile.Protocol.VLESS,
            address = "example.com",
            port = 443,
            uuid = "00000000-0000-0000-0000-000000000001",
        ))
        val subscriptionId = repository.insertSubscription(bundle(profiles = listOf(profile)), now = 1)
        val groupId = database.subscriptions().getSubscriptionGroup(subscriptionId)!!.groupId
        repository.deleteSubscriptionProfile(database.subscriptions().getProfiles(groupId).single().id, now = 2)
        val payload = "vless://00000000-0000-0000-0000-000000000001@example.com:443?encryption=none&type=tcp&security=none#VLESS"
        val refresher = SubscriptionRefresher(
            repository,
            userHttp = SubscriptionHttpClient { FakeConnection(200, payload.encodeToByteArray()) },
            strictHttp = SubscriptionHttpClient { FakeConnection(500) },
        )

        val result = refresher.refreshWithChanges(subscriptionId, now = 3)

        assertTrue(result.success)
        assertEquals(1, result.added)
        assertEquals(0, result.removed)
        assertEquals(1, result.total)
        assertEquals(1, repository.getSubscriptionProfiles(subscriptionId).size)
    }

    private fun bundle(
        url: String = "https://example.com/sub",
        profiles: List<ImportedProfile> = listOf(ImportedProfile.Olcrtc(OlcrtcProfile(
            name = "olcRTC",
            provider = OlcrtcProfile.Provider.WBSTREAM,
            transport = OlcrtcProfile.Transport.VP8CHANNEL,
            roomId = "room",
            clientId = "client",
            keyHex = "a".repeat(64),
        ))),
    ) = SubscriptionBundle(
        name = "Test",
        slug = "test",
        url = url,
        serverVersion = "1.9.45",
        mirrors = listOf(SubscriptionBundle.Mirror(
            type = "yandex_disk",
            url = "https://disk.yandex.example/public",
            encrypted = true,
            algorithm = "AES-256-GCM",
        )),
        mirrorKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }),
        deduplication = true,
        updateWhenConnectedOnly = false,
        profiles = profiles,
        rejectedProfiles = emptyList(),
    )

    private class FakeConnection(
        private val status: Int,
        private val body: ByteArray = byteArrayOf(),
    ) : HttpsURLConnection(URL("https://example.com")) {
        val requestHeaders = mutableMapOf<String, String>()

        override fun setRequestProperty(key: String, value: String) {
            requestHeaders[key] = value
        }

        override fun getResponseCode(): Int = status
        override fun getContentLengthLong(): Long = body.size.toLong()
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
        override fun getCipherSuite(): String = ""
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate>? = null
    }
}
