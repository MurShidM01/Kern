package com.aliedu.kern.core.terminal.implementation.runtime

import com.aliedu.kern.core.terminal.api.ExecutionWorkspace
import com.aliedu.kern.core.terminal.api.SessionKind
import com.aliedu.kern.core.terminal.api.TerminalOutput
import com.aliedu.kern.core.terminal.api.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns live shell sessions. A singleton so sessions survive navigation (the terminal ViewModel is
 * recreated, the processes and their output rings are not). Sessions do NOT survive process death —
 * the OS kills child processes — so after a relaunch the manager is empty and the UI says so.
 */
@Singleton
class TerminalSessionManager @Inject constructor(
    backends: Set<@JvmSuppressWildcards SessionBackend>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backendsByKind: Map<SessionKind, SessionBackend> = backends.associateBy { it.kind }
    private val sessions = ConcurrentHashMap<String, ShellSession>()
    private val order = CopyOnWriteArrayList<String>()
    private val counters = ConcurrentHashMap<SessionKind, AtomicInteger>()

    private val _liveSessions = MutableStateFlow<List<TerminalSession>>(emptyList())

    /**
     * The live sessions, republished on every change. The terminal's foreground notification follows
     * this so the shade always shows the true session count (mirrors Termux).
     */
    val liveSessions: StateFlow<List<TerminalSession>> = _liveSessions.asStateFlow()

    suspend fun create(
        workspace: ExecutionWorkspace,
        kind: SessionKind,
        displayName: String? = null
    ): TerminalSession {
        val backend = resolveBackend(kind)
        val index = counters.computeIfAbsent(backend.kind) { AtomicInteger(0) }.incrementAndGet()
        val name = displayName ?: defaultName(backend.kind, index)
        val session = backend.start(workspace, name, scope)
        sessions[session.id] = session
        order.add(session.id)
        publish()
        return session.toApi()
    }

    fun list(): List<TerminalSession> =
        order.mapNotNull { sessions[it]?.toApi() }

    fun get(id: String): TerminalSession? = sessions[id]?.toApi()

    fun send(id: String, bytes: ByteArray) {
        sessions[id]?.write(bytes)
    }

    /** Live output only. The caller seeds the screen from [replay] before subscribing. */
    fun observe(id: String): Flow<TerminalOutput> = flow {
        val session = sessions[id] ?: return@flow
        session.output.collect { emit(it) }
    }

    fun replay(id: String): String = sessions[id]?.replay() ?: ""

    fun isAlive(id: String): Boolean = sessions[id]?.isAlive ?: false

    fun requestStop(id: String) {
        sessions[id]?.requestStop()
    }

    fun resize(id: String, columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        sessions[id]?.resize(columns, rows)
    }

    fun close(id: String) {
        sessions.remove(id)?.close()
        order.remove(id)
        publish()
    }

    /**
     * Close every live session backed by [kind], returning the closed ids. Used when the environment
     * changes (the Linux environment installed/uninstalled) so the UI rebuilds its shells on the new backend.
     */
    fun closeByKind(kind: SessionKind): List<String> {
        val victims = order.filter { sessions[it]?.kind == kind }
        victims.forEach { close(it) }
        return victims
    }

    fun rename(id: String, displayName: String) {
        sessions[id]?.displayName = displayName
        publish()
    }

    fun shutdownAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        order.clear()
        publish()
    }

    /** Find a *live* session whose working directory matches, for command reuse. */
    fun findByCwd(cwd: String, kind: SessionKind? = null): TerminalSession? =
        order.mapNotNull { sessions[it] }
            .firstOrNull { it.isAlive && it.cwd == cwd && (kind == null || it.kind == kind) }
            ?.toApi()

    /** Republish the live session list so observers (the terminal notification) see the change. */
    private fun publish() {
        _liveSessions.value = list()
    }

    private fun resolveBackend(kind: SessionKind): SessionBackend {
        val requested = backendsByKind[kind]
        if (requested != null && requested.isAvailable()) return requested
        return backendsByKind[SessionKind.DEVICE_SHELL]
            ?: error("No session backend registered for $kind")
    }

    private fun defaultName(kind: SessionKind, index: Int): String = when (kind) {
        SessionKind.LINUX_PROOT -> "Ubuntu $index"
        SessionKind.DEVICE_SHELL -> "Shell $index"
    }

    private fun ShellSession.toApi() = TerminalSession(id, displayName, cwd, kind)
}
