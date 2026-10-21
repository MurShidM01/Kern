package com.aliedu.kern.core.tools.devserver

/**
 * A long-running server the agent started, and which the preview can point at.
 *
 * Servers are the one kind of command that must NOT go through the normal command path: a server
 * never returns to the prompt, so the sentinel/exit-code machinery would always time out and then
 * kill it (PRD §4.3 bullet 8).
 */
data class DevServer(
    val id: String,
    val command: String,
    /** The TCP port it listens on, once known. Null while [DevServerState.STARTING]. */
    val port: Int?,
    /** `http://127.0.0.1:<port>`, once the port is known. */
    val url: String?,
    val state: DevServerState,
    val startedAt: Long,
    /** The PTY session hosting it, so the executor can avoid reusing (and killing) it. */
    val sessionId: String?,
    /** The tail of its startup output; carries the reason when [state] is FAILED. */
    val log: String = ""
)

enum class DevServerState {
    /** Launched, waiting for the port to accept connections. */
    STARTING,
    RUNNING,
    /** Stopped by the agent or the user. */
    STOPPED,
    /** Exited before it was ready, or never bound a port in time. */
    FAILED
}

/** The localhost URL a preview of [port] would use. */
fun localPreviewUrl(port: Int): String = "http://127.0.0.1:$port"
