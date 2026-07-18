package io.github.oleglog.olcrtc.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalImportRoutingTest {
    @Test
    fun importIsDeliveredImmediatelyWhenDestinationIsAlreadyOpen() {
        assertTrue(shouldDeliverExternalImportImmediately(2, 2, true))
        assertFalse(shouldDeliverExternalImportImmediately(2, 1, true))
        assertFalse(shouldDeliverExternalImportImmediately(2, 2, false))
    }
}
