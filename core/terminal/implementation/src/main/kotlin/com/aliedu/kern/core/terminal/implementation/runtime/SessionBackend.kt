package com.aliedu.kern.core.terminal.implementation.runtime

import com.aliedu.kern.core.terminal.api.ExecutionWorkspace
import com.aliedu.kern.core.terminal.api.SessionKind
import kotlinx.coroutines.CoroutineScope

/** Starts sessions for one backend (device shell, Linux guest, …). Selected per session by [SessionKind]. */
interface SessionBackend {
    val kind: SessionKind

    /** True when this backend can currently start a session on this device. */
    fun isAvailable(): Boolean = true

    suspend fun start(
        workspace: ExecutionWorkspace,
        displayName: String,
        scope: CoroutineScope
    ): ShellSession

    /**
     * The path [hostPath] has inside this backend's shell, or null when the shell sees the host
     * filesystem as-is. A proot guest rebinds directories, so a host path is meaningless there and
     * a `cd` to it would silently leave the shell somewhere else.
     */
    fun guestPath(hostPath: String): String? = null
}
