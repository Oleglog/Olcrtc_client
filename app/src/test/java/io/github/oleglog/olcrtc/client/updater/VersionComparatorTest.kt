package io.github.oleglog.olcrtc.client.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {
    @Test
    fun comparesSemanticVersionsAndTags() {
        assertTrue(VersionComparator.isNewer("v1.0.1", "1.0.0"))
        assertTrue(VersionComparator.isNewer("2.0.0-beta.1", "1.9.9"))
        assertFalse(VersionComparator.isNewer("v1.0.0", "1.0.0"))
        assertFalse(VersionComparator.isNewer("v0.9.9", "1.0.0"))
    }
}
