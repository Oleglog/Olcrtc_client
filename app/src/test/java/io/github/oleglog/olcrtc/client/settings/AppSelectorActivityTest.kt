package io.github.oleglog.olcrtc.client.settings

import io.github.oleglog.olcrtc.client.routing.AppRoutingItem
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSelectorActivityTest {
    private val items = listOf(
        AppRoutingItem("org.telegram.messenger", "Telegram", selected = false, system = false),
        AppRoutingItem("com.android.settings", "Settings", selected = false, system = true),
    )

    @Test
    fun filtersByAppTypeAndLabelOrPackage() {
        assertEquals(listOf(items[0]), filterAppItems(items, " tele ", system = false))
        assertEquals(listOf(items[0]), filterAppItems(items, "ORG.TELEGRAM", system = false))
        assertEquals(listOf(items[1]), filterAppItems(items, "", system = true))
        assertEquals(emptyList<AppRoutingItem>(), filterAppItems(items, "telegram", system = true))
    }
}
