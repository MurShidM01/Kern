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
import com.aliedu.kern.core.tools.CommandImpact
import com.aliedu.kern.core.tools.CommandRequest
import com.aliedu.kern.core.tools.devserver.DevServer
import com.aliedu.kern.core.tools.devserver.DevServerRegistry
import com.aliedu.kern.core.tools.devserver.DevServerState
import com.aliedu.kern.core.workspace.ExecutionWorkspaceResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TerminalCommandExecutorTest {

    private class FakeResolver : ExecutionWorkspaceResolver {
        override suspend fun prepare(workspaceId: String) =
            ExecutionWorkspace(workspaceId, "/exec/$workspaceId")
        override fun scratchWorkspace() = ExecutionWorkspace("scratch", "/exec/scratch")
    }

    private class FakeRuntime : TerminalRuntime {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val flows = mutableMapOf<String, MutableSharedFlow<TerminalOutput>>()
        private var nextId = 0
        var existing: TerminalSession? = null
        var createCalls = 0
        var respond = true
        var exitCode = 0
        var outputText = "hello from the shell\n"
        val stopped = mutableListOf<String>()
        val sentScripts = mutableListOf<String>()

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

        override suspend fun findSessionByCwd(cwd: String, kind: SessionKind?): TerminalSession? =
            existing

        override fun sendInput(sessionId: String, input: ByteArray) {
            val text = String(input, Charsets.UTF_8)
            sentScripts.add(text)
            val marker = Regex("__KERN_DONE_[a-f0-9]+__").find(text)?.value ?: return
            scope.launch {
                delay(20)
                if (respond) {
                    flows[sessionId]?.emit(TerminalOutput.Stdout(outputText))
                    flows[sessionId]?.emit(TerminalOutput.Stdout("\n$marker $exitCode\n"))
                }
            }
        }

        override fun observeOutput(sessionId: String): Flow<TerminalOutput> =
            flows[sessionId] ?: emptyFlow()

        override suspend fun requestStop(sessionId: String) { stopped.add(sessionId) }
        override suspend fun closeSession(sessionId: String) {}
        override suspend fun uninstallEnvironment() = Result.success(Unit)
    }

    private fun request(command: String, timeoutMs: Long = 5_000) = CommandRequest(
        workspaceId = "ws",
        command = command,
        impact = CommandImpact.READ_ONLY,
        timeoutMs = timeoutMs
    )

    /** Local fake: the shared one lives in `:core:tools`' test source set, which is not visible here. */
    private class FakeDevServers : DevServerRegistry {
        private val _servers = MutableStateFlow<List<DevServer>>(emptyList())
        override val servers: StateFlow<List<DevServer>> = _servers
        val started = mutableListOf<String>()
        val owned = mutableSetOf<String>()

        override suspend fun start(
            command: String,
            port: Int?,
            workspaceId: String,
            cwd: String,
            onOutput: ((String) -> Unit)?
        ): Result<DevServer> {
            started.add(command)
            val server = DevServer(
                id = "srv1", command = command, port = port ?: 8000,
                url = "http://127.0.0.1:${port ?: 8000}", state = DevServerState.RUNNING,
                startedAt = 0, sessionId = "sess"
            )
            _servers.value = listOf(server)
            return Result.success(server)
        }

        override suspend fun stop(id: String): Boolean = false

        override fun isOwned(sessionId: String): Boolean = sessionId in owned
    }

    private fun executor(
        runtime: TerminalRuntime,
        devServers: DevServerRegistry = FakeDevServers()
    ) = TerminalCommandExecutor(runtime, FakeResolver(), devServers, AgentTerminalTracker())

    @Test
    fun `runs a command and returns its output and exit code`() = runBlocking {
        val runtime = FakeRuntime()
        val result = executor(runtime).run(request("echo hello"))

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.output).contains("hello from the shell")
        assertThat(result.timedOut).isFalse()
        assertThat(result.truncated).isFalse()
        assertThat(runtime.createCalls).isEqualTo(1)
    }

    @Test
    fun `the marker is stripped from the captured output`() = runBlocking {
        val runtime = FakeRuntime()
        val result = executor(runtime).run(request("echo hello"))
        assertThat(result.output).doesNotContain("__KERN_DONE_")
    }

    @Test
    fun `a non-zero exit code is surfaced`() = runBlocking {
        val runtime = FakeRuntime().apply { exitCode = 2 }
        val result = executor(runtime).run(request("false"))
        assertThat(result.exitCode).isEqualTo(2)
        assertThat(result.succeeded).isFalse()
    }

    @Test
    fun `output is truncated at the cap`() = runBlocking {
        val runtime = FakeRuntime().apply { outputText = "x".repeat(100_000) }
        val result = executor(runtime).run(request("cat big"))
        assertThat(result.truncated).isTrue()
        assertThat(result.output.length).isAtMost(64 * 1024)
    }

    @Test
    fun `a large output still resolves the sentinel and is not reported as a timeout`() = runBlocking {
        // Regression: the sentinel used to be searched in the 64 KB-capped output buffer, so any
        // command printing more than that (e.g. `apk search` listing every package) never completed.
        val runtime = FakeRuntime().apply { outputText = "pkg-name\n".repeat(20_000) }
        val result = executor(runtime).run(request("apk search", timeoutMs = 5_000))

        assertThat(result.timedOut).isFalse()
        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.truncated).isTrue()
    }

    @Test
    fun `a timeout stops the command and reports it`() = runBlocking {
        val runtime = FakeRuntime().apply { respond = false }
        val result = executor(runtime).run(request("sleep 100", timeoutMs = 200))
        assertThat(result.timedOut).isTrue()
        assertThat(result.exitCode).isNull()
        assertThat(runtime.stopped).isNotEmpty()
    }

    @Test
    fun `an existing session at the cwd is reused`() = runBlocking {
        val runtime = FakeRuntime().apply {
            val session = TerminalSession("existing", "Shell", "/exec/ws")
            existing = session
            // Pre-register the flow so the reused session can emit.
        }
        // The reused session has no registered flow, so seed one by creating the runtime's flow map
        // through a create call would change ids; instead just assert reuse for a non-responding run.
        runtime.respond = false
        val result = executor(runtime).run(request("pwd", timeoutMs = 200))
        assertThat(result.timedOut).isTrue()
        assertThat(runtime.createCalls).isEqualTo(0)
    }

    @Test
    fun `the script echoes a unique sentinel with the exit status`() = runBlocking {
        val runtime = FakeRuntime()
        executor(runtime).run(request("echo hi"))
        val script = runtime.sentScripts.single()
        assertThat(script).startsWith("echo hi\n")
        assertThat(script).contains("printf")
        assertThat(script).contains("$?")
    }

    @Test
    fun `a session owned by a dev server is never reused`() = runBlocking {
        // Regression: reusing the server's session would send the next command into the server's
        // terminal and then SIGKILL the whole process group when the sentinel never arrived.
        val runtime = FakeRuntime().apply {
            existing = TerminalSession("server-session", "Dev server", "/exec/ws")
            respond = false
        }
        val devServers = FakeDevServers().apply { owned.add("server-session") }

        val result = executor(runtime, devServers).run(request("pwd", timeoutMs = 200))

        assertThat(runtime.createCalls).isEqualTo(1)
        assertThat(runtime.stopped).contains("s1")
        assertThat(result.timedOut).isTrue()
    }

    @Test
    fun `a detached command starts a server instead of waiting for an exit`() = runBlocking {
        val runtime = FakeRuntime()
        val devServers = FakeDevServers()

        val result = executor(runtime, devServers).run(
            request("python3 -m http.server 8000").copy(detach = true, port = 8000)
        )

        assertThat(devServers.started).containsExactly("python3 -m http.server 8000")
        assertThat(result.succeeded).isTrue()
        assertThat(result.output).contains("http://127.0.0.1:8000")
        // No sentinel was ever written: a server never returns to the prompt.
        assertThat(runtime.sentScripts).isEmpty()
    }
}
