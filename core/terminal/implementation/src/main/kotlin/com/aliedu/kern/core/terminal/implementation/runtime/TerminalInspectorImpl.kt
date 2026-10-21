package com.aliedu.kern.core.terminal.implementation.runtime

import com.aliedu.kern.core.terminal.api.SessionKind
import com.aliedu.kern.core.terminal.api.TerminalRuntime
import com.aliedu.kern.core.tools.TerminalInspector
import com.aliedu.kern.core.tools.TerminalStatus
import javax.inject.Inject
import javax.inject.Singleton

/** Reports the agent's own terminal: the session it holds, its directory, and its command history. */
@Singleton
class TerminalInspectorImpl @Inject constructor(
    private val runtime: TerminalRuntime,
    private val tracker: AgentTerminalTracker
) : TerminalInspector {

    override suspend fun status(workspaceId: String): TerminalStatus {
        val sessions = runCatching { runtime.listSessions() }.getOrDefault(emptyList())
        val trackedId = tracker.sessionId(workspaceId)
        val tracked = trackedId?.let { id -> sessions.firstOrNull { it.id == id } }
        val backend = runCatching { runtime.preferredSessionKind() }
            .getOrDefault(SessionKind.DEVICE_SHELL)
        return TerminalStatus(
            sessionId = tracked?.id ?: trackedId,
            sessionName = tracked?.displayName,
            cwd = tracker.cwd(workspaceId) ?: tracked?.cwd,
            liveSessions = sessions.size,
            backend = when (backend) {
                SessionKind.LINUX_PROOT -> "Linux (proot)"
                SessionKind.DEVICE_SHELL -> "Android shell"
            },
            recent = tracker.records(workspaceId)
        )
    }
}
