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
        pattern = "(?i)([\\\"'](?:keyHex|key|k|auth_token|auth\\.token|authToken|a|clientId|client_id|c|roomId|room_id|roomPassword|room_password|rp|password|pass|uuid|id|mk|mirror_key|mirrorKey|token|access_token)[\\\"']\\s*:\\s*[\\\"'])[^\\\"']*([\\\"'])",
    )
    private val querySecret = Regex(
        pattern = "(?i)(^|[?&\\s])((?:keyHex|key|k|auth_token|auth\\.token|authToken|a|clientId|client_id|c|roomId|room_id|roomPassword|room_password|rp|password|pass|uuid|id|mk|mirror_key|mirrorKey|token|access_token)=)[^&#\\s]+",
    )
    // Bare "name=value" form from the Go core logs (mobile.Config), where the
    // field names are capitalized: RunWithReady args mismatch:
    // transport=".." carrier=".." room=".." client="..". The separator must
    // contain = or : so ordinary prose ("device ready", "token refreshed")
    // is left intact. The leading quote (if any) is consumed into the prefix
    // and re-emitted.
    private val coreField = Regex(
        pattern = "(?i)\\b((?:RoomURL|room|roomId|room_id|DeviceID|device|deviceId|client|clientId|client_id|keyHex|key|password|token)\\s*[=:]\\s*\"?)[^\\s,}\"]+\"?",
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
        .replace(coreField) { match -> match.groupValues[1] + REDACTED }
        .replace(httpsUrlWithQuery) { match -> match.groupValues[1] + REDACTED_QUERY }
        .replace(uuid, REDACTED)
        .replace(hexKey, REDACTED)
}
