package io.github.oleglog.olcrtc.client.vpn

internal fun nextProfileIndex(currentReference: String?, profileReferences: List<String>): Int? {
    if (profileReferences.isEmpty()) return null
    val currentIndex = profileReferences.indexOf(currentReference)
    repeat(profileReferences.size) { offset ->
        val candidateIndex = (currentIndex + offset + 1) % profileReferences.size
        if (profileReferences[candidateIndex] != currentReference) return candidateIndex
    }
    return null
}
