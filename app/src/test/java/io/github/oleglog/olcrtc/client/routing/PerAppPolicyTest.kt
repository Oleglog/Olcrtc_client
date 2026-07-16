package io.github.oleglog.olcrtc.client.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class PerAppPolicyTest {
    @Test
    fun alwaysExcludesVpnAppFromItsOwnTunnel() {
        val ownPackage = "io.github.oleglog.olcrtc.client"

        assertEquals(
            setOf(ownPackage),
            PerAppPolicy().packagesWithVpnAppExcluded(ownPackage),
        )
        assertEquals(
            setOf("other.app", ownPackage),
            PerAppPolicy(
                mode = PerAppPolicy.Mode.EXCLUDE_SELECTED,
                packages = setOf("other.app"),
            ).packagesWithVpnAppExcluded(ownPackage),
        )
        assertEquals(
            setOf("other.app"),
            PerAppPolicy(
                mode = PerAppPolicy.Mode.ONLY_SELECTED,
                packages = setOf("other.app", ownPackage),
            ).packagesWithVpnAppExcluded(ownPackage),
        )
    }
}
