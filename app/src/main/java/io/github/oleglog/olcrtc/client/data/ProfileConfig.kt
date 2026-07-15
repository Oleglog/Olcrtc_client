package io.github.oleglog.olcrtc.client.data

import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcProfile
import io.github.oleglog.olcrtc.client.profile.standard.StandardProfile

internal sealed interface ProfileConfig {
    data class Olcrtc(val value: OlcrtcProfile) : ProfileConfig
    data class Standard(val value: StandardProfile) : ProfileConfig
}
