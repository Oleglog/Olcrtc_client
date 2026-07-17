package io.github.oleglog.olcrtc.client.vpn

class VpnStateMachine(initialState: VpnState = VpnState.NO_PROFILE) {
    var state: VpnState = initialState
        private set

    @Synchronized
    fun transition(next: VpnState) {
        require(next in allowedTransitions.getValue(state)) {
            "Invalid VPN state transition: $state -> $next"
        }
        state = next
    }

    @Synchronized
    fun canStart(): Boolean = state in startableStates

    private companion object {
        val startableStates = setOf(VpnState.DISCONNECTED, VpnState.ERROR)

        val allowedTransitions = mapOf(
            VpnState.NO_PROFILE to setOf(VpnState.DISCONNECTED, VpnState.ERROR),
            VpnState.DISCONNECTED to setOf(VpnState.NO_PROFILE, VpnState.PREPARING, VpnState.ERROR),
            VpnState.PREPARING to setOf(
                VpnState.CONNECTING,
                VpnState.RECONNECTING,
                VpnState.STOPPING,
                VpnState.ERROR,
            ),
            VpnState.CONNECTING to setOf(
                VpnState.CONNECTED,
                VpnState.RECONNECTING,
                VpnState.STOPPING,
                VpnState.ERROR,
            ),
            VpnState.CONNECTED to setOf(VpnState.RECONNECTING, VpnState.STOPPING, VpnState.ERROR),
            VpnState.RECONNECTING to setOf(VpnState.CONNECTED, VpnState.STOPPING, VpnState.ERROR),
            VpnState.STOPPING to setOf(VpnState.DISCONNECTED, VpnState.NO_PROFILE, VpnState.ERROR),
            VpnState.ERROR to setOf(VpnState.PREPARING, VpnState.STOPPING, VpnState.DISCONNECTED),
        )
    }
}
