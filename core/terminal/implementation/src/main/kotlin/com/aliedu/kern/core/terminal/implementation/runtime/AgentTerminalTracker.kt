package com.aliedu.kern.core.terminal.implementation.runtime

import com.aliedu.kern.core.tools.TerminalCommandRecord
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the agent's terminal looks like, per workspace.
 *
 * The agent used to call `findSessionByCwd` and start a fresh session whenever the directory did
 * not match, so a long task could leave a pile of half-used shells behind. This tracker gives the
 * agent ONE shell per workspace and remembers where it is, so a command for another directory is a
 * `cd` inside the same session instead of a new one. It also keeps the tail of what has been run,
 * which is what `terminal_status` reports back to the model.
 */
@Singleton
class AgentTerminalTracker @Inject constructor() {

    private class State {
        @Volatile var sessionId: String? = null
        @Volatile var cwd: String? = null
    }

    private val states = ConcurrentHashMap<String, State>()
    private val logs = ConcurrentHashMap<String, ConcurrentLinkedDeque<TerminalCommandRecord>>()

    fun sessionId(workspaceId: String): String? = states[workspaceId]?.sessionId

    fun cwd(workspaceId: String): String? = states[workspaceId]?.cwd

    /** Points the workspace at [sessionId], which is now sitting in [cwd]. */
    fun bind(workspaceId: String, sessionId: String, cwd: String) {
        val state = states.getOrPut(workspaceId) { State() }
        state.sessionId = sessionId
        state.cwd = cwd
    }

    /** Forgets a session that has died, so the next command opens a fresh one. */
    fun unbind(workspaceId: String, sessionId: String) {
        val state = states[workspaceId] ?: return
        if (state.sessionId == sessionId) {
            state.sessionId = null
            state.cwd = null
        }
    }

    fun record(workspaceId: String, record: TerminalCommandRecord) {
        val log = logs.getOrPut(workspaceId) { ConcurrentLinkedDeque() }
        log.addLast(record)
        while (log.size > MAX_RECORDS) log.pollFirst()
    }

    fun records(workspaceId: String): List<TerminalCommandRecord> =
        logs[workspaceId]?.toList().orEmpty()

    private companion object {
        const val MAX_RECORDS = 20
    }
}
