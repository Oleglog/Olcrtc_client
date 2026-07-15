package io.github.oleglog.olcrtc.client.statistics

import android.content.Context
import io.github.oleglog.olcrtc.client.data.ClientDatabase
import io.github.oleglog.olcrtc.client.data.ConnectionSessionDao
import io.github.oleglog.olcrtc.client.data.ConnectionSessionEntity
import java.time.Instant
import java.time.ZoneId

internal data class StatisticsSummary(
    val current: ConnectionSessionEntity?,
    val today: StatisticsTotals,
    val month: StatisticsTotals,
    val recent: List<ConnectionSessionEntity>,
)

internal data class StatisticsTotals(
    val sessions: Int,
    val durationMillis: Long,
    val bytesUp: Long,
    val bytesDown: Long,
)

internal class ConnectionSessionRepository(
    private val sessions: ConnectionSessionDao,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun start(
        profileId: String?,
        profileName: String,
        protocol: String,
        networkType: String,
        now: Long = clockMillis(),
    ): Long = sessions.insert(
        ConnectionSessionEntity(
            profileId = profileId,
            profileNameSnapshot = profileName,
            protocolSnapshot = protocol,
            startedAt = now,
            endedAt = null,
            bytesUp = 0,
            bytesDown = 0,
            disconnectReason = null,
            networkType = networkType,
        ),
    )

    fun finish(
        id: Long,
        reason: String?,
        bytesUp: Long = 0,
        bytesDown: Long = 0,
        endedAt: Long = clockMillis(),
    ): Boolean = sessions.finish(
        id = id,
        endedAt = endedAt,
        bytesUp = bytesUp.coerceAtLeast(0),
        bytesDown = bytesDown.coerceAtLeast(0),
        disconnectReason = reason,
    ) == 1

    fun summary(now: Long = clockMillis(), recentLimit: Int = 20): StatisticsSummary {
        val currentDate = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val todayStart = currentDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val monthStart = currentDate
            .withDayOfMonth(1)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        return StatisticsSummary(
            current = sessions.getActive(),
            today = sessions.totalsSince(todayStart, now).toTotals(),
            month = sessions.totalsSince(monthStart, now).toTotals(),
            recent = sessions.getRecent(recentLimit.coerceIn(1, 100)),
        )
    }

    fun clear(): Int = sessions.clear()

    companion object {
        fun open(context: Context): ConnectionSessionRepository = ConnectionSessionRepository(
            ClientDatabase.open(context).connectionSessions(),
        )
    }
}

private fun io.github.oleglog.olcrtc.client.data.ConnectionSessionTotals.toTotals(): StatisticsTotals =
    StatisticsTotals(
        sessions = count,
        durationMillis = durationMillis,
        bytesUp = bytesUp,
        bytesDown = bytesDown,
    )
