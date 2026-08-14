package io.github.oleglog.olcrtc.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRefreshOnEntryTest {
    @Test
    fun refreshesOnFirstColdStartWhenEnabled() {
        assertTrue(shouldRefreshOnColdStart(alreadyRefreshedThisSession = false, autoRefreshEnabled = true))
    }

    @Test
    fun skipsWhenAlreadyRefreshedThisSession() {
        assertFalse(shouldRefreshOnColdStart(alreadyRefreshedThisSession = true, autoRefreshEnabled = true))
    }

    @Test
    fun skipsWhenToggleDisabled() {
        assertFalse(shouldRefreshOnColdStart(alreadyRefreshedThisSession = false, autoRefreshEnabled = false))
    }

    @Test
    fun skipsWhenBothAlreadyRefreshedAndDisabled() {
        assertFalse(shouldRefreshOnColdStart(alreadyRefreshedThisSession = true, autoRefreshEnabled = false))
    }
}
