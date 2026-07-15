package io.github.oleglog.olcrtc.client.diagnostics

internal object DiagnosticsRedactor {
    private const val REDACTED = "<redacted>"
    private const val REDACTED_QUERY = "<redacted-query>"
    private const val REDACTED_PROFILE_LINK = "<redacted-profile-link>"

    private val shareLink = Regex(
        pattern = "(?i)\\b(olcrtc|vless|vmess|trojan)://[^\\s]+",
    )
    private val authorizationHeader = Regex(
        pattern = "(?i)\\b(authorization\\s*[:=]\\s*(?:bearer|basic)\\s+)[^\\s,;]+",
    )
    private val sensitiveHeader = Regex(
        pattern = "(?i)\\b((?:x-api-key|x-auth-token|cookie|set-cookie)\\s*[:=]\\s*)[^\\r\\n;]+",
    )
    private val jsonSecret = Regex(
        pattern = "(?i)([\\\"'](?:key|k|auth_token|auth\\.token|a|client_id|c|password|pass|uuid|id|mk|mirror_key|token|access_token)[\\\"']\\s*:\\s*[\\\"'])[^\\\"']*([\\\"'])",
    )
    private val querySecret = Regex(
        pattern = "(?i)(^|[?&\\s])((?:key|k|auth_token|auth\\.token|a|client_id|c|password|pass|uuid|id|mk|mirror_key|token|access_token)=)[^&#\\s]+",
    )
    private val httpsUrlWithQuery = Regex(
        pattern = "(?i)\\b(https://[^\\s?#]+[^\\s#?]*\\?)[^\\s#]+",
    )
    private val uuid = Regex(
        pattern = "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b",
    )
    private val hexKey = Regex(
        pattern = "(?i)\\b[0-9a-f]{64}\\b",
    )

    fun redact(value: String): String = value
        .replace(shareLink) { match -> "${match.groupValues[1].lowercase()}://$REDACTED_PROFILE_LINK" }
        .replace(authorizationHeader) { match -> match.groupValues[1] + REDACTED }
        .replace(sensitiveHeader) { match -> match.groupValues[1] + REDACTED }
        .replace(jsonSecret) { match -> match.groupValues[1] + REDACTED + match.groupValues[2] }
        .replace(querySecret) { match -> match.groupValues[1] + match.groupValues[2] + REDACTED }
        .replace(httpsUrlWithQuery) { match -> match.groupValues[1] + REDACTED_QUERY }
        .replace(uuid, REDACTED)
        .replace(hexKey, REDACTED)
}
