package io.github.oleglog.olcrtc.client.connection

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle

/**
 * Issue #50: profile links embed connection secrets (key, client id,
 * room password), so copying one warns the user, tags the clipboard
 * content as sensitive, and wipes it after a delay.
 *
 * [containsSecrets] and [shouldClearClipboard] are pure string logic
 * covered by JVM tests; the ClipData helpers below are thin Android glue.
 */
internal object ProfileSharePolicy {
    /** Clipboard label used when copying profile links. */
    const val CLIP_LABEL = "olcrtc-profile-link"

    /** Delay after which a copied profile link is wiped, unless replaced. */
    const val CLEAR_DELAY_MILLIS = 60_000L

    /** True when the text looks like a profile URI carrying secrets. */
    fun containsSecrets(text: String): Boolean {
        val lower = text.lowercase()
        if (!lower.contains("://")) return false
        val scheme = lower.substringBefore("://")
        if (scheme != "olcrtc" && scheme != "vless" && scheme != "vmess" &&
            scheme != "trojan" && scheme != "ss"
        ) {
            return false
        }
        val query = text.substringAfter('?', "")
        val params = query.substringBefore('#').split('&')
            .map { it.substringBefore('=').lowercase() }
            .toSet()
        if (params.any { it in SECRET_QUERY_PARAMS }) return true
        // Standard URIs carry secrets outside the query (uuid@host, password).
        if (scheme != "olcrtc") return true
        // olcrtc links without recognised secret params: fall back to key shape.
        return SECRET_KEY_SHAPE.containsMatchIn(text)
    }

    /**
     * True when the current clipboard content is still the payload we copied
     * (same label and same text), so the timed wipe may clear it. Anything
     * the user copied afterwards must be left alone.
     */
    fun shouldClearClipboard(
        expectedLabel: String,
        expectedText: String,
        actualLabel: CharSequence?,
        actualText: CharSequence?,
    ): Boolean {
        if (actualLabel?.toString() != expectedLabel) return false
        return actualText?.toString() == expectedText
    }

    private val SECRET_QUERY_PARAMS = setOf(
        "k", "key", "keyhex",
        "c", "client_id", "clientid",
        "rp", "room_password", "roompassword",
        "password", "pass", "uuid", "id", "token",
        "auth_token", "authtoken", "auth.token",
        "a", "mk", "mirror_key", "mirrorkey", "access_token", "accesstoken",
    )
    private val SECRET_KEY_SHAPE = Regex("[0-9a-fA-F]{64}")
}

/** Hides the content from non-system autofill/keyboard suggestions (API 33+). */
internal fun ClipData.markSensitive() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    description.extras = (description.extras ?: PersistableBundle()).apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
}

internal fun ClipboardManager.copySensitive(label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    clip.markSensitive()
    setPrimaryClip(clip)
}
