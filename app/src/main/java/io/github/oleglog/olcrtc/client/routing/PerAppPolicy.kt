package io.github.oleglog.olcrtc.client.routing

internal data class PerAppPolicy(
    val mode: Mode = Mode.ALL,
    val packages: Set<String> = emptySet(),
) {
    init {
        require(packages.none(String::isBlank)) { "Package names must not be blank" }
    }

    fun packagesWithVpnAppExcluded(vpnPackage: String): Set<String> = when (mode) {
        Mode.ALL, Mode.EXCLUDE_SELECTED -> packages + vpnPackage
        Mode.ONLY_SELECTED -> packages - vpnPackage
    }

    enum class Mode {
        ALL,
        EXCLUDE_SELECTED,
        ONLY_SELECTED,
    }
}
