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
import com.aliedu.kern.core.tools.OutputPolicy
import com.aliedu.kern.core.tools.background.BackgroundJob
import com.aliedu.kern.core.tools.background.BackgroundJobRegistry
import com.aliedu.kern.core.tools.background.BackgroundJobState
import com.aliedu.kern.core.tools.background.BackgroundOutput
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
import kotlinx.coroutines.cancelAndJoin
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
        /** The guest spelling of the workspace, when the backend maps one. */
        var guestCwd: String? = null
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

        override suspend fun guestPath(hostPath: String): String? = guestCwd

        override suspend fun requestStop(sessionId: String) { stopped.add(sessionId) }
        override suspend fun closeSession(sessionId: String) {}
        override suspend fun uninstallEnvironment() = Result.success(Unit)
    }

    private fun request(command: String) = CommandRequest(
        workspaceId = "ws",
        command = command,
        impact = CommandImpact.READ_ONLY
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

    /** Records what was started as a job; the manager itself is tested separately. */
    private class FakeJobs : BackgroundJobRegistry {
        private val _jobs = MutableStateFlow<List<BackgroundJob>>(emptyList())
        override val jobs: StateFlow<List<BackgroundJob>> = _jobs
        val started = mutableListOf<String>()
        val owned = mutableSetOf<String>()

        override suspend fun start(
            command: String,
            workspaceId: String,
            cwd: String,
            maxOutputChars: Int
        ): Result<BackgroundJob> {
            started.add(command)
            val job = BackgroundJob(
                id = "job-1",
                command = command,
                workspaceId = workspaceId,
                cwd = cwd,
                state = BackgroundJobState.RUNNING,
                startedAt = 0,
                sessionId = "job-session"
            )
            _jobs.value = listOf(job)
            return Result.success(job)
        }

        override fun status(id: String): BackgroundJob? = _jobs.value.firstOrNull { it.id == id }

        override fun output(id: String, cursor: Int): BackgroundOutput? {
            val job = status(id) ?: return null
            return BackgroundOutput(job.id, job.state, "", 0, false, 0)
        }

        override suspend fun await(id: String, timeoutMs: Long): BackgroundJob? = status(id)
        override suspend fun stop(id: String): Boolean = false
        override fun isOwned(sessionId: String): Boolean = sessionId in owned
    }

    private fun executor(
        runtime: TerminalRuntime,
        devServers: DevServerRegistry = FakeDevServers(),
        backgroundJobs: BackgroundJobRegistry = FakeJobs()
    ) = TerminalCommandExecutor(runtime, FakeResolver(), devServers, backgroundJobs, AgentTerminalTracker())

    @Test
    fun `runs a command and returns its output and exit code`() = runBlocking {
        val runtime = FakeRuntime()
        val result = executor(runtime).run(request("echo hello"))

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.output).contains("hello from the shell")
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
    fun `a truncated output keeps its tail, where the failure is`() = runBlocking {
        // Regression: the cap used to keep only the first 64 KB, so the error a failing install
        // prints at the end was the first thing thrown away.
        val runtime = FakeRuntime().apply {
            outputText = "START\n" + "x".repeat(100_000) + "\nEND\n"
        }
        val result = executor(runtime).run(request("cat big"))

        assertThat(result.truncated).isTrue()
        assertThat(result.output).contains("START")
        assertThat(result.output).contains("END")
        assertThat(result.output).contains("characters omitted")
        assertThat(result.output.length).isLessThan(64 * 1024 + 200)
    }

    @Test
    fun `a streamed command is not captured for the model`() = runBlocking {
        val runtime = FakeRuntime()
        val result = executor(runtime).run(request("make").copy(outputPolicy = OutputPolicy.STREAM))

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.output).isEmpty()
        assertThat(result.truncated).isFalse()
    }

    @Test
    fun `the workspace is entered by its guest path when the backend maps one`() = runBlocking {
        // Inside proot the workspace is bound at /workspace (or /sdcard/<rel>); the host path does
        // not exist there, so a cd to it failed silently and the command ran wherever the shell
        // happened to be.
        val runtime = FakeRuntime().apply { guestCwd = "/workspace" }
        executor(runtime).run(request("pwd"))

        assertThat(runtime.sentScripts.single()).startsWith("cd -- '/workspace' 2>/dev/null || echo ")
    }

    @Test
    fun `a large output still resolves the sentinel`() = runBlocking {
        // Regression: the sentinel used to be searched in the 64 KB-capped output buffer, so any
        // command printing more than that (e.g. `apk search` listing every package) never completed.
        val runtime = FakeRuntime().apply { outputText = "pkg-name\n".repeat(20_000) }
        val result = executor(runtime).run(request("apk search"))

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.truncated).isTrue()
    }

    @Test
    fun `a command is never abandoned on its own`() = runBlocking {
        // There is no command timeout. A silent command is still running as far as the executor is
        // concerned, so it keeps waiting and nothing is killed.
        val runtime = FakeRuntime().apply { respond = false }
        val job = launch(Dispatchers.Default) { executor(runtime).run(request("sleep 100")) }
        delay(400)

        assertThat(job.isActive).isTrue()
        assertThat(runtime.stopped).isEmpty()

        job.cancelAndJoin()
    }

    @Test
    fun `cancelling the run kills the command`() = runBlocking {
        // Cancellation — the user stopping the turn — is now the only thing that ends a command
        // early, and it kills the process group rather than just dropping the wait.
        val runtime = FakeRuntime().apply { respond = false }
        val job = launch(Dispatchers.Default) { executor(runtime).run(request("sleep 100")) }
        delay(200)

        job.cancelAndJoin()

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
        val job = launch(Dispatchers.Default) { executor(runtime).run(request("pwd")) }
        delay(200)

        assertThat(runtime.createCalls).isEqualTo(0)

        job.cancelAndJoin()
    }

    @Test
    fun `the script echoes a unique sentinel with the exit status`() = runBlocking {
        val runtime = FakeRuntime()
        executor(runtime).run(request("echo hi"))
        val script = runtime.sentScripts.single()
        // Every command is prefixed with a cd into the workspace, so the agent cannot drift out of
        // it after an earlier command changed directory. The cd is best-effort: a workspace path the
        // rootfs cannot see must not fail the command itself.
        // The cd is best-effort, but no longer silent: a workspace the guest cannot see now says so
        // instead of quietly running the command in the previous directory.
        assertThat(script).startsWith("cd -- '/exec/ws' 2>/dev/null || echo ")
        assertThat(script).contains("; echo hi\n")
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

        val job = launch(Dispatchers.Default) { executor(runtime, devServers).run(request("pwd")) }
        delay(200)

        assertThat(runtime.createCalls).isEqualTo(1)

        job.cancelAndJoin()
        assertThat(runtime.stopped).contains("s1")
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

    @Test
    fun `a background command returns a job id instead of waiting for an exit`() = runBlocking {
        val runtime = FakeRuntime().apply { respond = false }
        val jobs = FakeJobs()

        val result = executor(runtime, backgroundJobs = jobs).run(
            request("apt-get install -y openjdk-17-jdk").copy(background = true)
        )

        assertThat(jobs.started).containsExactly("apt-get install -y openjdk-17-jdk")
        assertThat(result.jobId).isEqualTo("job-1")
        assertThat(result.running).isTrue()
        // Started, not finished: no exit code, but the start itself counts as a success.
        assertThat(result.exitCode).isNull()
        assertThat(result.succeeded).isTrue()
        // The foreground sentinel path was never used, so nothing can kill the job.
        assertThat(runtime.sentScripts).isEmpty()
        assertThat(runtime.stopped).isEmpty()
    }

    @Test
    fun `a session owned by a background job is never reused`() = runBlocking {
        // Regression: typing the next command into a running job's shell would interleave two
        // outputs, and a cancellation would then SIGKILL the job's process group.
        val runtime = FakeRuntime().apply {
            existing = TerminalSession("job-session", "Job 0001", "/exec/ws")
            respond = false
        }
        val jobs = FakeJobs().apply { owned.add("job-session") }

        val job = launch(Dispatchers.Default) { executor(runtime, backgroundJobs = jobs).run(request("pwd")) }
        delay(200)

        assertThat(runtime.createCalls).isEqualTo(1)

        job.cancelAndJoin()
    }
}
