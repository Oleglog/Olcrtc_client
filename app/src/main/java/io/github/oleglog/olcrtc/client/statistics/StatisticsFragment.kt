package io.github.oleglog.olcrtc.client.statistics

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import io.github.oleglog.olcrtc.client.MainActivity
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ConnectionSessionEntity
import io.github.oleglog.olcrtc.client.databinding.FragmentStatisticsBinding
import io.github.oleglog.olcrtc.client.vpn.VpnState
import java.util.Locale
import java.util.concurrent.Executors

class StatisticsFragment : Fragment() {
    private var _binding: FragmentStatisticsBinding? = null
    private val statistics by lazy { ConnectionSessionRepository.open(requireContext().applicationContext) }
    private val storage = Executors.newSingleThreadExecutor()
    private val ticker = Handler(Looper.getMainLooper())
    private var currentSession: ConnectionSessionEntity? = null
    private var summarySnapshot: StatisticsSummary? = null
    @Volatile private var loadInFlight = false
    @Volatile private var trafficRangeIsToday = true
    private val refreshCurrent = object : Runnable {
        override fun run() {
            val b = _binding ?: return
            val connected = (activity as? MainActivity)?.currentVpnState() == VpnState.CONNECTED
            if (!connected) {
                if (currentSession != null) {
                    currentSession = null
                    renderCurrentSession(null)
                    loadStatistics()
                } else {
                    renderCurrentSession(null)
                }
            } else {
                if (currentSession == null) {
                    loadStatistics()
                } else {
                    renderCurrentSession(currentSession)
                }
            }
            ticker.postDelayed(this, CURRENT_SESSION_REFRESH_MILLIS)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentStatisticsBinding.inflate(inflater, container, false)
        binding.rangeToggle.check(binding.rangeToday.id)
        binding.rangeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            trafficRangeIsToday = checkedId == binding.rangeToday.id
            summarySnapshot?.let { renderTotals(selected(it)) }
        }
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        loadStatistics()
        ticker.removeCallbacks(refreshCurrent)
        ticker.post(refreshCurrent)
    }

    override fun onStop() {
        ticker.removeCallbacks(refreshCurrent)
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        storage.shutdownNow()
        super.onDestroy()
    }

    private fun loadStatistics() {
        if (loadInFlight || storage.isShutdown) return
        loadInFlight = true
        storage.execute {
            val result = runCatching { statistics.summary() }
            val host = activity
            if (host == null) {
                loadInFlight = false
                return@execute
            }
            host.runOnUiThread {
                loadInFlight = false
                val b = _binding ?: return@runOnUiThread
                result.onSuccess { summary ->
                    summarySnapshot = summary
                    val connected = (activity as? MainActivity)?.currentVpnState() == VpnState.CONNECTED
                    currentSession = if (connected) summary.current else null
                    renderCurrentSession(currentSession)
                    renderTotals(selected(summary))
                }.onFailure {
                    currentSession = null
                    renderCurrentSession(null)
                    clearTotals()
                }
            }
        }
    }

    private fun selected(summary: StatisticsSummary): StatisticsTotals =
        if (trafficRangeIsToday) summary.today else summary.month

    private val binding get() = requireNotNull(_binding)

    private fun renderCurrentSession(session: ConnectionSessionEntity?) {
        val b = _binding ?: return
        val connected = (activity as? MainActivity)?.currentVpnState() == VpnState.CONNECTED
        if (!connected || session == null) {
            b.activeProfile.setText(R.string.statistics_no_active_session)
            b.activeMeta.isVisible = false
            b.activeMetrics.isVisible = false
            b.activeCard.setCardBackgroundColor(
                com.google.android.material.color.MaterialColors.getColor(
                    b.root,
                    com.google.android.material.R.attr.colorSurfaceContainer,
                )
            )
            return
        }
        b.activeCard.setCardBackgroundColor(
            com.google.android.material.color.MaterialColors.getColor(
                b.root,
                com.google.android.material.R.attr.colorPrimaryContainer,
            )
        )
        val traffic = (activity as? MainActivity)?.trafficSnapshot()?.takeIf { it.size >= 4 }
        val bytesUp = traffic?.get(0) ?: session.bytesUp
        val bytesDown = traffic?.get(1) ?: session.bytesDown
        val upSpeed = traffic?.get(2) ?: 0
        val downSpeed = traffic?.get(3) ?: 0
        b.activeProfile.text = session.profileNameSnapshot
        b.activeMeta.text = getString(
            R.string.statistics_active_meta,
            session.protocolSnapshot,
            networkTypeLabel(session.networkType),
        )
        b.activeDuration.text = formatDuration(System.currentTimeMillis() - session.startedAt)
        b.activeTotal.text = formatBytes(bytesUp + bytesDown)
        b.activeSpeed.text = getString(
            R.string.statistics_speed_value,
            formatBytesPerSecond(downSpeed),
            formatBytesPerSecond(upSpeed),
        )
        b.activeMeta.isVisible = true
        b.activeMetrics.isVisible = true
    }

    private fun renderTotals(totals: StatisticsTotals) {
        val b = binding
        b.rangeSessions.text = totals.sessions.toString()
        b.rangeDuration.text = formatDuration(totals.durationMillis)
        b.rangeDown.text = "↓ ${formatBytes(totals.bytesDown)}"
        b.rangeUp.text = "↑ ${formatBytes(totals.bytesUp)}"
    }

    private fun clearTotals() {
        listOf(
            binding.rangeSessions,
            binding.rangeDuration,
            binding.rangeDown,
            binding.rangeUp,
        ).forEach { it.setText(R.string.statistics_not_available) }
    }

    private fun formatDuration(durationMillis: Long): String {
        val seconds = (durationMillis.coerceAtLeast(0) / 1_000).toInt()
        val hours = seconds / 3_600
        val minutes = (seconds % 3_600) / 60
        val remainingSeconds = seconds % 60
        return if (hours > 0) {
            getString(R.string.duration_hms, hours, minutes, remainingSeconds)
        } else {
            getString(R.string.duration_ms, minutes, remainingSeconds)
        }
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KiB", "MiB", "GiB")
        var value = bytes.coerceAtLeast(0).toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "${value.toLong()} ${units[unit]}" else "%.1f %s".format(Locale.US, value, units[unit])
    }

    private fun formatBytesPerSecond(bytes: Long): String = "${formatBytes(bytes)}/s"

    private fun networkTypeLabel(type: String): String = getString(when (type.lowercase(Locale.ROOT)) {
        "wifi" -> R.string.settings_diagnostics_network_wifi
        "mobile" -> R.string.settings_diagnostics_network_mobile
        "ethernet" -> R.string.settings_diagnostics_network_ethernet
        "unknown" -> R.string.settings_diagnostics_network_unknown
        else -> R.string.settings_diagnostics_network_other
    })

    private companion object {
        const val CURRENT_SESSION_REFRESH_MILLIS = 1_000L
    }
}
