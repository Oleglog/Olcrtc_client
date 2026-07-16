package io.github.oleglog.olcrtc.client.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionCardStateTest {
    @Test
    fun connectedCardIsTheOnlyHighlightedCard() {
        assertEquals(
            ConnectionCardState.CONNECTED,
            connectionCardState(selected = true, connected = true, hasConnectedProfile = true),
        )
        assertEquals(
            ConnectionCardState.INACTIVE,
            connectionCardState(selected = true, connected = false, hasConnectedProfile = true),
        )
    }

    @Test
    fun selectionIsVisibleOnlyWhileDisconnected() {
        assertEquals(
            ConnectionCardState.SELECTED,
            connectionCardState(selected = true, connected = false, hasConnectedProfile = false),
        )
    }
}
