package io.github.oleglog.olcrtc.client.support

import android.os.Build
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class IssueReportInfo(
    val appVersion: String,
    val protocol: String?,
    val xrayVersion: String? = null,
    val olcrtcCoreVersion: String? = null,
)

internal object IssueReportBuilder {
    fun buildUrl(info: IssueReportInfo): String {
        val body = buildString {
            appendLine("### Environment")
            appendLine("- App version: ${info.appVersion}")
            appendLine("- Android API: ${Build.VERSION.SDK_INT}")
            appendLine("- Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("- ABI: ${Build.SUPPORTED_ABIS.orEmpty().joinToString()}")
            appendLine("- Protocol: ${info.protocol ?: "not selected"}")
            info.xrayVersion?.let { appendLine("- Xray: $it") }
            info.olcrtcCoreVersion?.let { appendLine("- olcRTC core: $it") }
            appendLine()
            appendLine("### What happened?")
            appendLine()
            appendLine("### Expected behavior")
            appendLine()
            appendLine("### Redacted diagnostics")
            appendLine("Attach copied redacted diagnostics manually if needed.")
        }
        return ISSUE_URL + "?title=" + encode("Bug report") + "&body=" + encode(body)
    }

    private fun encode(value: String): String = URLEncoder
        .encode(value, StandardCharsets.UTF_8.name())
        .replace("+", "%20")

    private const val ISSUE_URL = "https://github.com/Oleglog/Olcrtc_client/issues/new"
}
