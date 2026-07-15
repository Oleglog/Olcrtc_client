package io.github.oleglog.olcrtc.client.profile

import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcProfile
import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcUri
import io.github.oleglog.olcrtc.client.profile.standard.StandardProfile
import io.github.oleglog.olcrtc.client.profile.standard.StandardUri

internal sealed interface ImportedProfile {
    data class Olcrtc(val value: OlcrtcProfile) : ImportedProfile
    data class Standard(val value: StandardProfile) : ImportedProfile
}

internal object ProfileUri {
    fun parse(raw: String): ImportedProfile = when (raw.substringBefore(':').lowercase()) {
        "olcrtc" -> ImportedProfile.Olcrtc(OlcrtcUri.parse(raw))
        "vless", "vmess", "trojan" -> ImportedProfile.Standard(StandardUri.parse(raw))
        else -> throw IllegalArgumentException("Unsupported profile scheme")
    }
}
