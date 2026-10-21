package com.aliedu.kern.core.tools.devserver

import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks the long-running servers the agent has started, so the UI can offer a preview and the
 * command executor can avoid reusing (and thereby killing) a server's PTY session.
 *
 * Declared here, next to [com.aliedu.kern.core.tools.CommandExecutor], because the implementation
 * lives in the terminal layer: the same shape as the executor seam, and no dependency cycle.
 */
interface DevServerRegistry {

    /** Every server started this process, newest last. Terminal states are retained for the log. */
    val servers: StateFlow<List<DevServer>>

    /**
     * Launch [command] as a background process and wait until it accepts connections on [port].
     *
     * @param port the expected port, or null to discover it from the server's output.
     * @param onOutput receives startup output while the server is starting; it stops being called
     *   once the server is ready, so a chatty server cannot grow the caller's buffer unbounded.
     */
    suspend fun start(
        command: String,
        port: Int?,
        workspaceId: String,
        cwd: String,
        onOutput: ((String) -> Unit)? = null
    ): Result<DevServer>

    /** Stop the server with [id] and close its session. Returns false if it was already gone. */
    suspend fun stop(id: String): Boolean

    /**
     * True when [sessionId] belongs to a dev server. The command executor must skip such sessions
     * when reusing a shell, or a server would be sent the next command and then killed on timeout.
     */
    fun isOwned(sessionId: String): Boolean
}
