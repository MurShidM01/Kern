package com.aliedu.kern.core.terminal.implementation.runtime

import com.aliedu.kern.core.terminal.api.EnvironmentInfo
import com.aliedu.kern.core.terminal.api.EnvironmentState
import com.aliedu.kern.core.terminal.api.ExecutionWorkspace
import com.aliedu.kern.core.terminal.api.InstallEvent
import com.aliedu.kern.core.terminal.api.InstallProgress
import com.aliedu.kern.core.terminal.api.SessionKind
import com.aliedu.kern.core.terminal.api.TerminalOutput
import com.aliedu.kern.core.terminal.api.TerminalRuntime
import com.aliedu.kern.core.terminal.api.TerminalSession
import com.aliedu.kern.core.tools.devserver.DevServerState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** PRD §4.3 bullet 8: a server is launched in its own session and is ready when its port is. */
class DevServerManagerTest {

    private class FakeRuntime : TerminalRuntime {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val flows = mutableMapOf<String, MutableSharedFlow<TerminalOutput>>()
        private var nextId = 0

        var createCalls = 0
        /** What the "server" prints once started. */
        var banner = "Serving HTTP on 0.0.0.0 port 8000\n"
        /** Emit [TerminalOutput.Exited] instead of a banner, to simulate a crash. */
        var exitImmediately: Int? = null
        val stopped = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val renamed = mutableMapOf<String, String>()
        val sent = mutableListOf<String>()

        override suspend fun inspectInstallState() = EnvironmentInfo(EnvironmentState.READY)
        override fun installOrRepair(
        distroId: String?,
        onProgress: (InstallProgress) -> Unit
    ): Flow<InstallEvent> = emptyFlow()

        override suspend fun createSession(workspace: ExecutionWorkspace): TerminalSession {
            createCalls++
            val session = TerminalSession("s${++nextId}", "Shell", workspace.workingDirectory)
            flows[session.id] = MutableSharedFlow(extraBufferCapacity = 64)
            return session
        }

        override suspend fun findSessionByCwd(cwd: String, kind: SessionKind?): TerminalSession? = null

        override fun sendInput(sessionId: String, input: ByteArray) {
            val text = String(input, Charsets.UTF_8)
            sent.add(text)
            // Ctrl-C: the server is interrupted, so the session ends.
            if (text == "\u0003") {
                scope.launch { flows[sessionId]?.emit(TerminalOutput.Exited(130)) }
                return
            }
            scope.launch {
                delay(30)
                val code = exitImmediately
                if (code != null) {
                    flows[sessionId]?.emit(TerminalOutput.Exited(code))
                } else {
                    flows[sessionId]?.emit(TerminalOutput.Stdout(banner))
                }
            }
        }

        override fun observeOutput(sessionId: String): Flow<TerminalOutput> =
            flows[sessionId] ?: emptyFlow()

        override suspend fun requestStop(sessionId: String) {
            stopped.add(sessionId)
        }

        override suspend fun closeSession(sessionId: String) {
            closed.add(sessionId)
        }

        override suspend fun uninstallEnvironment() = Result.success(Unit)

        override suspend fun renameSession(sessionId: String, displayName: String) {
            renamed[sessionId] = displayName
        }
    }

    /** Reports a port open only once [openAfter] probes have happened, to exercise polling. */
    private class FakeProbe(var open: Boolean = true) : PortProbe {
        var probes = 0
        override suspend fun isOpen(port: Int): Boolean {
            probes++
            return open
        }
    }

    @Test
    fun `a declared port becomes a running server without scraping`(): Unit = runBlocking {
        val runtime = FakeRuntime()
        val manager = DevServerManager(runtime, FakeProbe())

        val server = manager.start("python3 -m http.server 8000", 8000, "ws", "/exec/ws").getOrThrow()

        assertThat(server.state).isEqualTo(DevServerState.RUNNING)
        assertThat(server.port).isEqualTo(8000)
        assertThat(server.url).isEqualTo("http://127.0.0.1:8000")
        assertThat(manager.servers.value).containsExactly(server)
    }

    @Test
    fun `an undeclared port is scraped from the server banner`(): Unit = runBlocking {
        val runtime = FakeRuntime().apply { banner = "  ➜  Local:   http://localhost:5173/\n" }
        val manager = DevServerManager(runtime, FakeProbe())

        val server = manager.start("npm run dev", null, "ws", "/exec/ws").getOrThrow()

        assertThat(server.port).isEqualTo(5173)
        assertThat(server.url).isEqualTo("http://127.0.0.1:5173")
    }

    @Test
    fun `the server gets its own session and owns it`(): Unit = runBlocking {
        val runtime = FakeRuntime()
        val manager = DevServerManager(runtime, FakeProbe())

        val server = manager.start("npm run dev", 3000, "ws", "/exec/ws").getOrThrow()

        assertThat(runtime.createCalls).isEqualTo(1)
        assertThat(server.sessionId).isEqualTo("s1")
        assertThat(manager.isOwned("s1")).isTrue()
        assertThat(manager.isOwned("other")).isFalse()
        assertThat(runtime.renamed["s1"]).isEqualTo("Dev server")
    }

    @Test
    fun `startup output is forwarded while the server comes up`(): Unit = runBlocking {
        val runtime = FakeRuntime().apply { banner = "starting...\nlistening on port 4000\n" }
        val manager = DevServerManager(runtime, FakeProbe())
        val forwarded = StringBuilder()

        manager.start("node server.js", null, "ws", "/exec/ws") { forwarded.append(it) }

        assertThat(forwarded.toString()).contains("listening on port 4000")
    }

    @Test
    fun `a server that exits before binding is reported as failed`(): Unit = runBlocking {
        val runtime = FakeRuntime().apply { exitImmediately = 1 }
        val manager = DevServerManager(runtime, FakeProbe(open = false))

        val result = manager.start("node broken.js", 9000, "ws", "/exec/ws")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).contains("exited with code 1")
        assertThat(manager.servers.value.single().state).isEqualTo(DevServerState.FAILED)
        // The dead session is torn down and released.
        assertThat(runtime.closed).contains("s1")
        assertThat(manager.isOwned("s1")).isFalse()
    }

    @Test
    fun `a port that never opens fails with the startup log`(): Unit = runBlocking {
        val runtime = FakeRuntime().apply { banner = "binding to ::1 only\n" }
        val manager = DevServerManager(runtime, FakeProbe(open = false))
        manager.readyTimeoutMs = 300

        val result = manager.start("npm run dev", 6000, "ws", "/exec/ws")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).contains("binding to ::1 only")
        assertThat(manager.servers.value.single().state).isEqualTo(DevServerState.FAILED)
    }

    @Test
    fun `stop interrupts the server and closes its session`(): Unit = runBlocking {
        val runtime = FakeRuntime()
        val manager = DevServerManager(runtime, FakeProbe())
        val server = manager.start("npm run dev", 3000, "ws", "/exec/ws").getOrThrow()

        val stopped = manager.stop(server.id)

        assertThat(stopped).isTrue()
        assertThat(runtime.sent).contains("\u0003")
        assertThat(runtime.closed).contains("s1")
        assertThat(manager.isOwned("s1")).isFalse()
        assertThat(manager.servers.value.single().state).isEqualTo(DevServerState.STOPPED)
    }

    @Test
    fun `stopping an unknown server reports false`(): Unit = runBlocking {
        val manager = DevServerManager(FakeRuntime(), FakeProbe())
        assertThat(manager.stop("nope")).isFalse()
    }

    @Test
    fun `port scraping recognises the common server banners`() {
        assertThat(DevServerManager.scrapePort("Serving HTTP on 0.0.0.0 port 8000")).isEqualTo(8000)
        assertThat(DevServerManager.scrapePort("  ➜  Local:   http://localhost:5173/")).isEqualTo(5173)
        assertThat(DevServerManager.scrapePort("* Running on http://127.0.0.1:5000")).isEqualTo(5000)
        assertThat(DevServerManager.scrapePort("Server listening on 0.0.0.0:3000")).isEqualTo(3000)
        assertThat(DevServerManager.scrapePort("no port here")).isNull()
    }
}
