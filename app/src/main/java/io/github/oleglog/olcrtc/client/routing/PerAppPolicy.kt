package io.github.oleglog.olcrtc.client.routing

internal data class PerAppPolicy(
    val mode: Mode = Mode.ALL,
    val packages: Set<String> = emptySet(),
) {
    init {
        require(packages.none(String::isBlank)) { "Package names must not be blank" }
    }

    enum class Mode {
        ALL,
        EXCLUDE_SELECTED,
        ONLY_SELECTED,
    }
}
