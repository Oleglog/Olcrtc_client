package io.github.oleglog.olcrtc.client.statistics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.data.ConnectionSessionEntity
import io.github.oleglog.olcrtc.client.databinding.FragmentSectionBinding
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class StatisticsFragment : Fragment() {
    private var _binding: FragmentSectionBinding? = null
    private val statistics by lazy { ConnectionSessionRepository.open(requireContext().applicationContext) }
    private val storage = Executors.newSingleThreadExecutor()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val binding = FragmentSectionBinding.inflate(inflater, container, false)
        _binding = binding
        binding.title.setText(R.string.navigation_statistics)
        binding.content.setText(R.string.statistics_loading)
        addClearButton(binding)
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

    private fun addClearButton(binding: FragmentSectionBinding) {
        val root = binding.root as? LinearLayout ?: return
        root.addView(
            Button(requireContext()).apply {
                setText(R.string.statistics_clear_history)
                setOnClickListener { confirmClearHistory() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 16.dp },
        )
    }

    private fun loadStatistics() {
        storage.execute {
            val result = runCatching { statistics.summary() }
            activity?.runOnUiThread {
                val binding = _binding ?: return@runOnUiThread
                binding.content.text = result.fold(
                    onSuccess = ::formatSummary,
                    onFailure = { it.message ?: getString(R.string.statistics_error) },
                )
            }
        }
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

    private fun formatSummary(summary: StatisticsSummary): String = buildString {
        appendLine(getString(R.string.statistics_current_session))
        appendLine(summary.current?.let(::formatCurrentSession) ?: getString(R.string.statistics_no_active_session))
        appendLine()
        appendLine(getString(R.string.statistics_today))
        appendLine(formatTotals(summary.today))
        appendLine()
        appendLine(getString(R.string.statistics_month))
        appendLine(formatTotals(summary.month))
        appendLine()
        appendLine(getString(R.string.statistics_recent_sessions))
        if (summary.recent.isEmpty()) {
            appendLine(getString(R.string.statistics_no_history))
        } else {
            summary.recent.take(8).forEach { appendLine(formatRecentSession(it)) }
        }
    }

    private fun formatCurrentSession(session: ConnectionSessionEntity): String = getString(
        R.string.statistics_active_session_format,
        session.profileNameSnapshot,
        session.protocolSnapshot,
        formatDuration(System.currentTimeMillis() - session.startedAt),
        session.networkType,
    )

    private fun formatRecentSession(session: ConnectionSessionEntity): String {
        val started = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.startedAt))
        val endedAt = session.endedAt ?: System.currentTimeMillis()
        return getString(
            R.string.statistics_recent_session_format,
            started,
            session.profileNameSnapshot,
            formatDuration(endedAt - session.startedAt),
            formatBytes(session.bytesUp + session.bytesDown),
            session.disconnectReason ?: getString(R.string.statistics_disconnect_unknown),
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

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
