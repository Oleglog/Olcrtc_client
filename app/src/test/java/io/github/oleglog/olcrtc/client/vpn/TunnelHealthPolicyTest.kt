package io.github.oleglog.olcrtc.client.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelHealthPolicyTest {
    @Test
    fun reconnectsOnlyAfterSecondConsecutiveFailure() {
        assertFalse(TunnelHealthPolicy.shouldReconnectAfterFailures(0))
        assertFalse(TunnelHealthPolicy.shouldReconnectAfterFailures(1))
        assertTrue(TunnelHealthPolicy.shouldReconnectAfterFailures(2))
        assertTrue(TunnelHealthPolicy.shouldReconnectAfterFailures(3))
    }

    @Test
    fun singleDnsSuccessKeepsTunnelHealthy() {
        assertTrue(TunnelHealthPolicy.isTunnelHealthy(dnsSuccesses = 1, dnsAttempts = 3, httpsSuccesses = 0))
        assertTrue(TunnelHealthPolicy.isTunnelHealthy(dnsSuccesses = 3, dnsAttempts = 3, httpsSuccesses = 0))
    }

    @Test
    fun httpsSuccessRescuesFailedDns() {
        assertTrue(TunnelHealthPolicy.isTunnelHealthy(dnsSuccesses = 0, dnsAttempts = 3, httpsSuccesses = 1))
    }

    @Test
    fun totalFailureOfBothFamiliesIsUnhealthy() {
        assertFalse(TunnelHealthPolicy.isTunnelHealthy(dnsSuccesses = 0, dnsAttempts = 3, httpsSuccesses = 0))
    }

    @Test
    fun probeTargetsAreNonEmpty() {
        assertTrue(TunnelHealthPolicy.DATAPATH_DNS_NAMES.size >= 2)
        assertTrue(TunnelHealthPolicy.DATAPATH_HTTPS_URLS.size >= 2)
        assertTrue(TunnelHealthPolicy.DATAPATH_HTTPS_URLS.all { it.startsWith("https://") })
    }
}
