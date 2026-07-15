package io.github.oleglog.olcrtc.client.statistics

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.oleglog.olcrtc.client.data.ClientDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class ConnectionSessionRepositoryTest {
    private lateinit var database: ClientDatabase
    private lateinit var repository: ConnectionSessionRepository
    private var now = Instant.parse("2026-07-15T10:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        database = ClientDatabase.inMemory(ApplicationProvider.getApplicationContext())
        repository = ConnectionSessionRepository(
            database.connectionSessions(),
            clockMillis = { now },
            zoneId = ZoneId.of("UTC"),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun recordsActiveAndFinishedSessionsInSummary() {
        val finished = repository.start(
            profileId = "local:1",
            profileName = "VLESS",
            protocol = "VLESS",
            networkType = "wifi",
        )
        now += 60_000
        assertTrue(repository.finish(finished, reason = "user stopped", bytesUp = 1024, bytesDown = 2048))
        val active = repository.start(
            profileId = "subscription:abc",
            profileName = "olcRTC",
            protocol = "olcRTC · wbstream",
            networkType = "mobile",
        )
        now += 120_000

        val summary = repository.summary(recentLimit = 10)

        assertEquals(active, summary.current?.id)
        assertEquals(2, summary.today.sessions)
        assertEquals(180_000L, summary.today.durationMillis)
        assertEquals(1024L, summary.today.bytesUp)
        assertEquals(2048L, summary.today.bytesDown)
        assertEquals(listOf(active, finished), summary.recent.map { it.id })
    }

    @Test
    fun finishIsIdempotentAndClearRemovesHistory() {
        val sessionId = repository.start("local:1", "Test", "VLESS", "wifi")
        now += 1_000

        assertTrue(repository.finish(sessionId, reason = "done"))
        assertFalse(repository.finish(sessionId, reason = "again"))
        assertEquals(1, repository.clear())
        assertEquals(0, repository.summary().today.sessions)
    }
}
