package io.github.oleglog.olcrtc.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationProfileSelectorTest {
    private val profiles = listOf("local:1", "subscription:first", "local:2")

    @Test
    fun `selects next profile and wraps around`() {
        assertEquals(1, nextProfileIndex("local:1", profiles))
        assertEquals(0, nextProfileIndex("local:2", profiles))
        assertEquals(0, nextProfileIndex(null, profiles))
        assertEquals(0, nextProfileIndex("subscription:missing", profiles))
    }

    @Test
    fun `returns null when no different profile exists`() {
        assertNull(nextProfileIndex("local:1", listOf("local:1")))
        assertNull(nextProfileIndex(null, emptyList()))
    }
}
