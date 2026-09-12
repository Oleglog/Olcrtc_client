package io.github.oleglog.olcrtc.client.vpn

import java.io.Closeable

internal interface VpnTunnelSession : Closeable {
    fun isRunning(): Boolean
    fun trafficCounters(): TrafficCounters = TrafficCounters()
    fun releaseTun() {}
}
