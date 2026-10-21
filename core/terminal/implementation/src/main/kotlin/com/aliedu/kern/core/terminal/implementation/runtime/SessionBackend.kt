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
}
