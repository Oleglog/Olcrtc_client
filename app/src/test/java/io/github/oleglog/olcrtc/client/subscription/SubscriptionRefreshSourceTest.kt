package io.github.oleglog.olcrtc.client.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionRefreshSourceTest {
    @Test
    fun sourceCodesRemainStableAcrossVpnProcessBoundary() {
        assertEquals(
            SubscriptionRefresher.Source.PRIMARY,
            SubscriptionRefresher.Source.fromWireCode(SubscriptionRefresher.Source.PRIMARY.wireCode),
        )
        assertEquals(
            SubscriptionRefresher.Source.MIRROR,
            SubscriptionRefresher.Source.fromWireCode(SubscriptionRefresher.Source.MIRROR.wireCode),
        )
        assertNull(SubscriptionRefresher.Source.fromWireCode(0))
    }
}
