package io.github.oleglog.olcrtc.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnStateMachineTest {
    @Test
    fun connectionLifecycle() {
        val machine = VpnStateMachine()

        machine.transition(VpnState.DISCONNECTED)
        assertTrue(machine.canStart())
        machine.transition(VpnState.PREPARING)
        assertFalse(machine.canStart())
        machine.transition(VpnState.CONNECTING)
        machine.transition(VpnState.CONNECTED)
        machine.transition(VpnState.STOPPING)
        machine.transition(VpnState.DISCONNECTED)

        assertEquals(VpnState.DISCONNECTED, machine.state)
    }

    @Test(expected = IllegalArgumentException::class)
    fun secondStartWhileConnectingIsRejected() {
        val machine = VpnStateMachine(VpnState.CONNECTING)

        machine.transition(VpnState.PREPARING)
    }

    @Test
    fun missingProfileCanFailBeforePreparation() {
        val machine = VpnStateMachine()

        machine.transition(VpnState.ERROR)

        assertEquals(VpnState.ERROR, machine.state)
        assertTrue(machine.canStart())
    }

    @Test
    fun networkLossDuringStartupCanWaitForReconnect() {
        listOf(VpnState.PREPARING, VpnState.CONNECTING).forEach { state ->
            val machine = VpnStateMachine(state)

            machine.transition(VpnState.RECONNECTING)

            assertEquals(VpnState.RECONNECTING, machine.state)
        }
    }

    @Test
    fun errorCanRetryOrStop() {
        val retry = VpnStateMachine(VpnState.ERROR)
        val stop = VpnStateMachine(VpnState.ERROR)

        assertTrue(retry.canStart())
        retry.transition(VpnState.PREPARING)
        stop.transition(VpnState.STOPPING)

        assertEquals(VpnState.PREPARING, retry.state)
        assertEquals(VpnState.STOPPING, stop.state)
    }
}
