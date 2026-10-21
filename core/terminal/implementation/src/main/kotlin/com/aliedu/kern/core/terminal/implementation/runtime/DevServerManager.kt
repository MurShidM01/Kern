package com.aliedu.kern.core.terminal.implementation.runtime

import com.aliedu.kern.core.terminal.api.ExecutionWorkspace
import com.aliedu.kern.core.terminal.api.TerminalOutput
import com.aliedu.kern.core.terminal.api.TerminalRuntime
import com.aliedu.kern.core.tools.CommandRequest
import com.aliedu.kern.core.tools.devserver.DevServer
import com.aliedu.kern.core.tools.devserver.DevServerRegistry
import com.aliedu.kern.core.tools.devserver.DevServerState
import com.aliedu.kern.core.tools.devserver.localPreviewUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs long-lived servers (PRD §4.3 bullet 8).
 *
 * A server never returns to the prompt, so it cannot go through the normal command path: the
 * sentinel that recovers an exit code is never printed, the run would always time out, and the
 * timeout handler would SIGKILL the server. Instead this creates a **dedicated** session, sends the
 * command, and waits only for the port to accept connections.
 *
 * The session is owned: [isOwned] lets the command executor refuse to reuse it, which is what stops
 * a later `run_command` from hijacking (and then killing) a running server.
 */
@Singleton
class DevServerManager @Inject constructor(
    private val runtime: TerminalRuntime,
    private val probe: PortProbe
) : DevServerRegistry {

    private val _servers = MutableStateFlow<List<DevServer>>(emptyList())
    override val servers: StateFlow<List<DevServer>> = _servers.asStateFlow()

    private val owned = ConcurrentHashMap.newKeySet<String>()

    /**
     * How long to wait for a server to accept connections. A property rather than a constant so
     * tests can fail fast instead of sitting out the real budget.
     */
    internal var readyTimeoutMs: Long = CommandRequest.DEV_SERVER_READY_TIMEOUT_MS

    /** Process-lifetime scope: a server outlives the tool call that started it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun isOwned(sessionId: String): Boolean = sessionId in owned

    override suspend fun start(
        command: String,
        port: Int?,
        workspaceId: String,
        cwd: String,
        onOutput: ((String) -> Unit)?
    ): Result<DevServer> {
        val id = "srv-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val startedAt = System.currentTimeMillis()

        val session = try {
            runtime.createSession(ExecutionWorkspace(workspaceId, cwd))
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("Could not open a session for the server: ${e.message}"))
        }
        owned.add(session.id)
        runCatching { runtime.renameSession(session.id, "Dev server") }

        val log = StringBuilder()
        val discoveredPort = AtomicReference<Int?>(null)
        val exited = AtomicReference<Int?>(null)
        val ready = AtomicBoolean(false)

        // One collector for the server's whole life. Keeping it subscribed also keeps the PTY
        // drained; cancelling it would leave the guest's output flowing into a SharedFlow nobody
        // reads. Forwarding to the caller stops at readiness so a chatty server cannot grow the
        // orchestrator's buffer.
        val subscribed = CompletableDeferred<Unit>()
        scope.launch {
            runtime.observeOutput(session.id)
                .onStart { subscribed.complete(Unit) }
                .collect { output ->
                    when (output) {
                        is TerminalOutput.Stdout -> handleChunk(output.text, log, onOutput, ready, discoveredPort)
                        is TerminalOutput.Stderr -> handleChunk(output.text, log, onOutput, ready, discoveredPort)
                        is TerminalOutput.Exited -> exited.compareAndSet(null, output.exitCode)
                    }
                }
        }

        // Subscribe before sending, or a server that prints its banner immediately would have that
        // output dropped and its port would never be discovered.
        subscribed.await()
        runtime.sendInput(session.id, (command + "\n").toByteArray(Charsets.UTF_8))

        val readyPort = awaitReady(port, discoveredPort, exited)

        if (readyPort == null) {
            runCatching { runtime.closeSession(session.id) }
            owned.remove(session.id)
            val reason = exited.get()?.let { code ->
                "The server exited with code $code before it accepted connections."
            } ?: "No server accepted connections on port ${port ?: "(unknown)"} within " +
                "${readyTimeoutMs / 1000}s."
            upsert(
                DevServer(
                    id = id, command = command, port = port, url = null,
                    state = DevServerState.FAILED, startedAt = startedAt,
                    sessionId = session.id, log = log.toString()
                )
            )
            return Result.failure(IllegalStateException("$reason\n${log.toString().takeLast(LOG_REPORT_CHARS)}"))
        }

        ready.set(true)
        val server = DevServer(
            id = id,
            command = command,
            port = readyPort,
            url = localPreviewUrl(readyPort),
            state = DevServerState.RUNNING,
            startedAt = startedAt,
            sessionId = session.id,
            log = log.toString()
        )
        upsert(server)

        // Reflect a later crash: the session ending means the server is gone.
        scope.launch {
            while (exited.get() == null) delay(EXIT_WATCH_MS)
            owned.remove(session.id)
            markStopped(id, exited.get())
        }

        return Result.success(server)
    }

    override suspend fun stop(id: String): Boolean {
        val server = _servers.value.firstOrNull { it.id == id } ?: return false
        val sessionId = server.sessionId
        if (sessionId != null) {
            // Ctrl-C first so the server can shut down cleanly, then tear the session down.
            runCatching { runtime.sendInput(sessionId, CTRL_C) }
            delay(STOP_GRACE_MS)
            runCatching { runtime.closeSession(sessionId) }
            owned.remove(sessionId)
        }
        upsert(server.copy(state = DevServerState.STOPPED))
        return true
    }

    /**
     * Poll until the port accepts connections. The port is known up front when the caller supplied
     * it, otherwise it is scraped from the server's own banner.
     */
    private suspend fun awaitReady(
        declaredPort: Int?,
        discoveredPort: AtomicReference<Int?>,
        exited: AtomicReference<Int?>
    ): Int? {
        val deadline = System.currentTimeMillis() + readyTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (exited.get() != null) return null
            val candidate = declaredPort ?: discoveredPort.get()
            if (candidate != null && probe.isOpen(candidate)) {
                // An open port is not proof of life: something else may already hold the port we
                // were told to expect. Make sure our own process is still there before declaring
                // the server up.
                if (exited.get() != null) return null
                return candidate
            }
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    private fun handleChunk(
        text: String,
        log: StringBuilder,
        onOutput: ((String) -> Unit)?,
        ready: AtomicBoolean,
        discoveredPort: AtomicReference<Int?>
    ) {
        if (text.isEmpty()) return
        if (log.length < MAX_LOG_CHARS) {
            val remaining = MAX_LOG_CHARS - log.length
            log.append(if (text.length <= remaining) text else text.take(remaining))
        }
        if (!ready.get()) onOutput?.invoke(text)
        if (discoveredPort.get() == null) {
            scrapePort(text)?.let { discoveredPort.compareAndSet(null, it) }
        }
    }

    private fun markStopped(id: String, exitCode: Int?) {
        val server = _servers.value.firstOrNull { it.id == id } ?: return
        if (server.state != DevServerState.RUNNING) return
        upsert(
            server.copy(
                state = if (exitCode == 0) DevServerState.STOPPED else DevServerState.FAILED,
                log = server.log + "\n[server exited with code ${exitCode ?: -1}]"
            )
        )
    }

    private fun upsert(server: DevServer) {
        _servers.value = _servers.value.filterNot { it.id == server.id } + server
    }

    companion object {
        /** How often to re-probe the port while waiting for a server to come up. */
        const val POLL_INTERVAL_MS = 250L

        /** How long to let Ctrl-C land before closing the session outright. */
        const val STOP_GRACE_MS = 400L

        /** How often to notice that a running server's session has ended. */
        const val EXIT_WATCH_MS = 1_000L

        /** How much of the startup log to retain for diagnostics. */
        const val MAX_LOG_CHARS = 8_000

        /** How much of the log to hand back when startup fails. */
        const val LOG_REPORT_CHARS = 1_500

        private val CTRL_C = byteArrayOf(3)

        /**
         * Common ways a server announces its port. `0.0.0.0` is included because a server bound to
         * every interface is still reachable at `127.0.0.1`.
         */
        private val PORT_PATTERNS = listOf(
            Regex("""https?://(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::\]):(\d{2,5})"""),
            Regex("""Local:\s+https?://[^:\s]+:(\d{2,5})""", RegexOption.IGNORE_CASE),
            Regex("""listening on[^\n\r]{0,40}?:(\d{2,5})""", RegexOption.IGNORE_CASE),
            Regex("""\bport[ =:]+(\d{2,5})""", RegexOption.IGNORE_CASE)
        )

        /** The first plausible listening port mentioned in [text], if any. */
        fun scrapePort(text: String): Int? {
            for (pattern in PORT_PATTERNS) {
                val value = pattern.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (value in 1..65_535) return value
            }
            return null
        }
    }
}
