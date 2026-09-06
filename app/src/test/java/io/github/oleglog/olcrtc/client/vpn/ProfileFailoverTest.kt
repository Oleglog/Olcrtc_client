package io.github.oleglog.olcrtc.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileFailoverTest {
    private val order = listOf("local:1", "local:2", "subscription:3")

    @Test
    fun retriesSameProfileBeforeMovingOn() {
        var state = ProfileFailover.start(order, "local:1")
        assertEquals("local:1", state.current)

        val (retry, next) = ProfileFailover.onFailure(state, "local:1")
        state = retry
        assertEquals("local:1", next)
        assertEquals("local:1", state.current)

        val (moved, following) = ProfileFailover.onFailure(state, "local:1")
        state = moved
        assertEquals("local:2", following)
        assertEquals("local:2", state.current)
    }

    @Test
    fun exhaustsListAfterOneCycle() {
        var state = ProfileFailover.start(order, "local:1")
        // 2 attempts x 3 profiles = 6 failures.
        repeat(5) { step ->
            val (updated, next) = ProfileFailover.onFailure(state, state.current!!)
            state = updated
            org.junit.Assert.assertNotNull("step $step should continue", next)
        }
        val (exhausted, next) = ProfileFailover.onFailure(state, state.current!!)
        state = exhausted
        assertNull(next)
    }

    @Test
    fun startsAtUnknownReferenceFromHead() {
        val state = ProfileFailover.start(order, "local:9")
        assertEquals("local:1", state.current)
    }

    @Test
    fun emptyOrderImmediatelyExhausts() {
        val (_, next) = ProfileFailover.onFailure(ProfileFailover.FailoverState(), "local:1")
        assertNull(next)
    }

    @Test
    fun manualSwitchReanchorsWalk() {
        var state = ProfileFailover.start(order, "local:1")
        val (reanchored, next) = ProfileFailover.onFailure(state, "local:2")
        state = reanchored
        assertEquals("local:2", state.current)
        assertEquals("local:2", next)
    }

    @Test
    fun successResetsWalk() {
        assertEquals(ProfileFailover.FailoverState(), ProfileFailover.onSuccess())
    }
}
