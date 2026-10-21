package com.aliedu.kern.core.terminal.implementation.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether a server is actually accepting connections yet.
 *
 * Injectable so dev-server tests never touch a real socket: readiness is the one thing about a
 * background server that cannot be faked by the runtime alone.
 */
interface PortProbe {
    suspend fun isOpen(port: Int): Boolean
}

/**
 * Probes loopback by attempting a TCP connect. The app's child processes share its network
 * namespace, so a server started inside Alpine/`proot` is reachable at `127.0.0.1` exactly as one
 * started on the device shell is.
 */
@Singleton
class SocketPortProbe @Inject constructor() : PortProbe {

    override suspend fun isOpen(port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CONNECT_TIMEOUT_MS = 250
    }
}
