package io.github.oleglog.olcrtc.client.vpn

/**
 * Issue #47: automatic failover across the user's saved profiles.
 *
 * When the active profile keeps failing, the service walks the ordered
 * profile list (favorites → last successful → rest, see orderProfiles)
 * instead of retrying the same blocked carrier forever. The orderer is a
 * pure function over profile references, unit-testable without Android.
 *
 * Policy (agreed 2026-09-06): at most [MAX_ATTEMPTS_PER_PROFILE]
 * consecutive failures on one profile before moving on, and at most one
 * full pass over the list ([MAX_CYCLES] = 1) before surfacing ERROR.
 * Any manual action (Stop, profile switch) resets the walk.
 */
internal object ProfileFailover {
    /** Consecutive failures on one profile before trying the next. */
    const val MAX_ATTEMPTS_PER_PROFILE = 2

    /** Full passes over the profile list before giving up. */
    const val MAX_CYCLES = 1

    data class FailoverState(
        val order: List<String> = emptyList(),
        val index: Int = 0,
        val attemptsOnCurrent: Int = 0,
        val cycles: Int = 0,
    ) {
        val current: String? get() = order.getOrNull(index)
    }

    /** Starts a walk over [order], beginning at [startReference]. */
    fun start(order: List<String>, startReference: String): FailoverState {
        val index = order.indexOf(startReference).takeIf { it >= 0 } ?: 0
        return FailoverState(order = order, index = index)
    }

    /**
     * Records one failure of [failedReference] and returns the updated
     * state plus the reference to try next, or null when the walk is
     * exhausted (caller should surface ERROR).
     */
    fun onFailure(state: FailoverState, failedReference: String): Pair<FailoverState, String?> {
        if (state.order.isEmpty()) return state to null
        var updated = if (state.current != failedReference) {
            // The active profile changed underneath us (manual switch):
            // re-anchor the walk instead of counting a phantom failure.
            start(state.order, failedReference)
        } else {
            state
        }
        updated = updated.copy(attemptsOnCurrent = updated.attemptsOnCurrent + 1)
        if (updated.attemptsOnCurrent < MAX_ATTEMPTS_PER_PROFILE) {
            return updated to updated.current
        }
        val nextIndex = updated.index + 1
        if (nextIndex >= updated.order.size) {
            val nextCycle = updated.cycles + 1
            if (nextCycle >= MAX_CYCLES) {
                return updated.copy(attemptsOnCurrent = 0) to null
            }
            return FailoverState(order = updated.order, index = 0, cycles = nextCycle) to updated.order.firstOrNull()
        }
        return updated.copy(index = nextIndex, attemptsOnCurrent = 0) to updated.order[nextIndex]
    }

    /** Records a success: the walk ends, and the winner becomes last-known. */
    fun onSuccess(): FailoverState = FailoverState()
}
