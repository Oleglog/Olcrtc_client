package io.github.oleglog.olcrtc.client.statistics

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ConnectionSessionEntity
import io.github.oleglog.olcrtc.client.databinding.FragmentStatisticsBinding
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class StatisticsFragment : Fragment() {
    private var _binding: FragmentStatisticsBinding? = null
    private val statistics by lazy { ConnectionSessionRepository.open(requireContext().applicationContext) }
    private val storage = Executors.newSingleThreadExecutor()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentStatisticsBinding.inflate(inflater, container, false)
        binding.clearHistory.setOnClickListener { confirmClearHistory() }
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        loadStatistics()
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
        storage.execute {
            val result = runCatching { statistics.summary() }
            activity?.runOnUiThread {
                val b = _binding ?: return@runOnUiThread
                result.onSuccess(::showSummary).onFailure {
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
            session.disconnectReason ?: getString(R.string.statistics_disconnect_unknown),
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
            text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            setOnClickListener { showReasonDialog(session) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun disconnectColor(reason: String?): Int = when {
        reason == null -> Color.parseColor("#4CAF50")
        reason.startsWith("error", ignoreCase = true) || reason.contains("fail", ignoreCase = true) -> Color.parseColor("#E53935")
        reason.startsWith("manual", ignoreCase = true) || reason.equals("user", ignoreCase = true) -> Color.parseColor("#FBC02D")
        else -> Color.parseColor("#9E9E9E")
    }

    private fun showReasonDialog(session: ConnectionSessionEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle(session.profileNameSnapshot)
            .setMessage(
                buildString {
                    appendLine("Started: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.startedAt))}")
                    session.endedAt?.let { appendLine("Ended: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}") }
                    appendLine("Duration: ${formatDuration((session.endedAt ?: System.currentTimeMillis()) - session.startedAt)}")
                    appendLine("Up: ${formatBytes(session.bytesUp)}")
                    appendLine("Down: ${formatBytes(session.bytesDown)}")
                    appendLine("Reason: ${session.disconnectReason ?: getString(R.string.statistics_disconnect_unknown)}")
                    appendLine("Network: ${session.networkType}")
                },
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

    private fun formatCurrentSession(session: ConnectionSessionEntity): String = getString(
        R.string.statistics_active_session_format,
        session.profileNameSnapshot,
        session.protocolSnapshot,
        formatDuration(System.currentTimeMillis() - session.startedAt),
        session.networkType,
    )

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

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
