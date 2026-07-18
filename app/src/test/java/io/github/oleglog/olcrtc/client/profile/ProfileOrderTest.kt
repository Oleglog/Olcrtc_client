package io.github.oleglog.olcrtc.client.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileOrderTest {
    @Test
    fun favoritesThenLastSuccessfulThenStableOriginalOrder() {
        val profiles = listOf(
            Item("local:1"),
            Item("subscription:a", favorite = true),
            Item("local:2"),
            Item("subscription:b", favorite = true),
        )

        assertEquals(
            listOf("subscription:a", "subscription:b", "local:2", "local:1"),
            orderProfiles(profiles, "local:2", Item::reference, Item::favorite).map(Item::reference),
        )
    }

    private data class Item(val reference: String, val favorite: Boolean = false)
}
