package io.github.oleglog.olcrtc.client.vpn

import java.net.ServerSocket

internal fun freeLoopbackPort(excludedPort: Int? = null): Int {
    var port: Int
    do {
        port = ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            socket.localPort
        }
    } while (port == excludedPort)
    return port
}
