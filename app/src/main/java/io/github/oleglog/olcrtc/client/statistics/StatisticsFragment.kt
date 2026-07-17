package io.github.oleglog.olcrtc.client.statistics

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import io.github.oleglog.olcrtc.client.MainActivity
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ConnectionSessionEntity
import io.github.oleglog.olcrtc.client.databinding.FragmentStatisticsBinding
import io.github.oleglog.olcrtc.client.vpn.VpnState
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class StatisticsFragment : Fragment() {
    private var _binding: FragmentStatisticsBinding? = null
    private val statistics by lazy { ConnectionSessionRepository.open(requireContext().applicationContext) }
    private val storage = Executors.newSingleThreadExecutor()
    private val ticker = Handler(Looper.getMainLooper())
    private var currentSession: ConnectionSessionEntity? = null
    @Volatile private var loadInFlight = false
    private val refreshCurrent = object : Runnable {
        override fun run() {
            val b = _binding ?: return
            val state = (activity as? MainActivity)?.currentVpnState() ?: VpnState.DISCONNECTED
            when {
                currentSession != null && state in INACTIVE_STATES -> {
                    currentSession = null
                    b.activeContent.setText(R.string.statistics_no_active_session)
                    loadStatistics()
                }
                currentSession == null && state == VpnState.CONNECTED -> loadStatistics()
                else -> currentSession?.let { b.activeContent.text = formatCurrentSession(it) }
            }
            ticker.postDelayed(this, CURRENT_SESSION_REFRESH_MILLIS)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentStatisticsBinding.inflate(inflater, container, false)
        binding.clearHistory.setOnClickListener { confirmClearHistory() }
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
                result.onSuccess(::showSummary).onFailure {
                    currentSession = null
                    b.activeContent.text = it.message ?: getString(R.string.statistics_error)
                    b.todayContent.text = ""
                    b.monthContent.text = ""
                    b.historyEmpty.visibility = View.VISIBLE
                    b.historyList.removeAllViews()
                }
            }
        }
    }

    private fun showSummary(summary: StatisticsSummary) {
        val b = binding
        currentSession = summary.current
        b.activeContent.text = summary.current?.let(::formatCurrentSession) ?: getString(R.string.statistics_no_active_session)
        b.todayContent.text = formatTotals(summary.today)
        b.monthContent.text = formatTotals(summary.month)
        b.historyList.removeAllViews()
        if (summary.recent.isEmpty()) {
            b.historyEmpty.visibility = View.VISIBLE
        } else {
            b.historyEmpty.visibility = View.GONE
            summary.recent.take(8).forEach { b.historyList.addView(recentSessionRow(it)) }
        }
    }

    private fun recentSessionRow(session: ConnectionSessionEntity): View {
        val started = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.startedAt))
        val endedAt = session.endedAt ?: System.currentTimeMillis()
        val text = getString(
            R.string.statistics_recent_session_format,
            started,
            session.profileNameSnapshot,
            formatDuration(endedAt - session.startedAt),
            formatBytes(session.bytesUp + session.bytesDown),
            disconnectReasonLabel(session.disconnectReason),
        )
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 6.dp, 0, 6.dp)
        }
        row.addView(View(requireContext()).apply {
            setBackgroundColor(disconnectColor(session.disconnectReason))
            layoutParams = LinearLayout.LayoutParams(8.dp, 8.dp).apply {
                marginEnd = 12.dp
            }
        })
        row.addView(TextView(requireContext()).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            setOnClickListener { showReasonDialog(session) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun disconnectColor(reason: String?): Int = requireContext().getColor(
        when {
            reason == null -> R.color.olcrtc_primary
            reason.startsWith("error", ignoreCase = true) || reason.contains("fail", ignoreCase = true) ->
                R.color.olcrtc_error
            reason.startsWith("manual", ignoreCase = true) || reason.equals("user", ignoreCase = true) ->
                R.color.olcrtc_secondary
            else -> R.color.olcrtc_outline
        },
        )

    private fun showReasonDialog(session: ConnectionSessionEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle(session.profileNameSnapshot)
            .setMessage(
                getString(
                    R.string.statistics_session_details_format,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.startedAt)),
                    session.endedAt?.let {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
                    } ?: getString(R.string.statistics_session_active),
                    formatDuration((session.endedAt ?: System.currentTimeMillis()) - session.startedAt),
                    formatBytes(session.bytesUp),
                    formatBytes(session.bytesDown),
                    disconnectReasonLabel(session.disconnectReason),
                    networkTypeLabel(session.networkType),
                ),
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.statistics_clear_history)
            .setMessage(R.string.statistics_clear_history_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                storage.execute {
                    runCatching { statistics.clear() }
                    activity?.runOnUiThread { loadStatistics() }
                }
            }
            .show()
    }

    private val binding get() = requireNotNull(_binding)

    private fun formatCurrentSession(session: ConnectionSessionEntity): String {
        val traffic = (activity as? MainActivity)?.trafficSnapshot()?.takeIf { it.size >= 4 }
        val bytesUp = traffic?.get(0) ?: session.bytesUp
        val bytesDown = traffic?.get(1) ?: session.bytesDown
        val connected = (activity as? MainActivity)?.currentVpnState() == VpnState.CONNECTED
        val upSpeed = if (connected) traffic?.get(2) ?: 0 else 0
        val downSpeed = if (connected) traffic?.get(3) ?: 0 else 0
        return getString(
            R.string.statistics_active_session_format,
            session.profileNameSnapshot,
            session.protocolSnapshot,
            formatDuration(System.currentTimeMillis() - session.startedAt),
            networkTypeLabel(session.networkType),
            formatBytes(bytesUp),
            formatBytes(bytesDown),
            formatBytesPerSecond(upSpeed),
            formatBytesPerSecond(downSpeed),
        )
    }

    private fun formatTotals(totals: StatisticsTotals): String = getString(
        R.string.statistics_totals_format,
        totals.sessions,
        formatDuration(totals.durationMillis),
        formatBytes(totals.bytesUp),
        formatBytes(totals.bytesDown),
    )

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

    private fun disconnectReasonLabel(reason: String?): String = getString(when {
        reason == null -> R.string.statistics_disconnect_active
        reason.startsWith("manual", ignoreCase = true) || reason.contains("user", ignoreCase = true) ->
            R.string.statistics_disconnect_manual
        reason.contains("reconnect", ignoreCase = true) || reason.contains("network", ignoreCase = true) ->
            R.string.statistics_disconnect_network
        reason.startsWith("error", ignoreCase = true) || reason.contains("fail", ignoreCase = true) ->
            R.string.statistics_disconnect_error
        reason.contains("destroy", ignoreCase = true) || reason.contains("service", ignoreCase = true) ->
            R.string.statistics_disconnect_service
        else -> R.string.statistics_disconnect_unknown
    })

    private fun networkTypeLabel(type: String): String = getString(when (type.lowercase(Locale.ROOT)) {
        "wifi" -> R.string.settings_diagnostics_network_wifi
        "mobile" -> R.string.settings_diagnostics_network_mobile
        "ethernet" -> R.string.settings_diagnostics_network_ethernet
        "unknown" -> R.string.settings_diagnostics_network_unknown
        else -> R.string.settings_diagnostics_network_other
    })

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val CURRENT_SESSION_REFRESH_MILLIS = 1_000L
        val INACTIVE_STATES = setOf(VpnState.NO_PROFILE, VpnState.DISCONNECTED, VpnState.ERROR)
    }
}
