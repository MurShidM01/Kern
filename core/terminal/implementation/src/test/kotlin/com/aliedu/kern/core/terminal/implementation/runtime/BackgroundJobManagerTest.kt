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
import com.aliedu.kern.core.tools.background.BackgroundJobState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * A job is the answer to "the command takes longer than a tool call": it must keep running after
 * `start` returns, report its exit code when the command finally prints one, and never be killed
 * by the foreground timeout path.
 */
class BackgroundJobManagerTest {

    /** Answers every sent script the way a shell would: with output, then the sentinel. */
    private class FakeRuntime : TerminalRuntime {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val flows = mutableMapOf<String, MutableSharedFlow<TerminalOutput>>()
        private var nextId = 0

        var outputText = "downloading…\ndone\n"
        var exitCode = 0
        var autoRespond = true

        /** False makes the shell refuse the command, as a dead session would. */
        var acceptsCommands = true

        private val subscribers = mutableMapOf<String, Int>()

        /** True when a script was written before anyone was listening for output. */
        var wroteBeforeSubscribing = false

        val sent = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val stopped = mutableListOf<String>()

        override suspend fun inspectInstallState() = EnvironmentInfo(EnvironmentState.READY)

        override fun installOrRepair(
            distroId: String?,
            onProgress: (InstallProgress) -> Unit
        ): Flow<InstallEvent> = emptyFlow()

        override suspend fun createSession(workspace: ExecutionWorkspace): TerminalSession {
            val session = TerminalSession("s" + (++nextId), "Shell", workspace.workingDirectory)
            flows[session.id] = MutableSharedFlow(extraBufferCapacity = 64)
            return session
        }

        override suspend fun findSessionByCwd(cwd: String, kind: SessionKind?): TerminalSession? = null

        override fun sendInput(sessionId: String, input: ByteArray) {
            val text = String(input, Charsets.UTF_8)
            sent.add(text)
            if (outputSubscriberCount(sessionId) == 0) wroteBeforeSubscribing = true
            // The shell builds its marker from the seed, so the start marker comes before the
            // command output and the exit marker after it — exactly like a real shell.
            val seed = Regex("KERN_S=([a-f0-9]+)").find(text)?.groupValues?.get(1) ?: return
            val marker = "__KERN_JOB_" + seed + "__"
            scope.launch {
                // The start marker always comes first: it is how the manager knows the shell is
                // actually running the script.
                flows[sessionId]?.emit(TerminalOutput.Stdout("\n" + marker + "\n"))
                if (!autoRespond) return@launch
                flows[sessionId]?.emit(TerminalOutput.Stdout(outputText))
                flows[sessionId]?.emit(TerminalOutput.Stdout("\n" + marker + " " + exitCode + "\n"))
            }
        }

        /** Pushes a chunk as if the command had printed it. */
        fun emit(sessionId: String, text: String) {
            scope.launch { flows[sessionId]?.emit(TerminalOutput.Stdout(text)) }
        }

        override fun observeOutput(sessionId: String): Flow<TerminalOutput> {
            val stream = flows[sessionId] ?: return emptyFlow()
            return stream.onSubscription {
                synchronized(subscribers) {
                    subscribers[sessionId] = (subscribers[sessionId] ?: 0) + 1
                }
            }
        }

        override fun outputSubscriberCount(sessionId: String): Int =
            synchronized(subscribers) { subscribers[sessionId] ?: 0 }

        override suspend fun awaitOutputSubscriber(
            sessionId: String,
            count: Int,
            timeoutMs: Long
        ): Boolean {
            if (!acceptsCommands) return false
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (outputSubscriberCount(sessionId) >= count) return true
                delay(5)
            }
            return outputSubscriberCount(sessionId) >= count
        }

        override suspend fun isSessionAlive(sessionId: String): Boolean = acceptsCommands

        override suspend fun requestStop(sessionId: String) {
            stopped.add(sessionId)
        }

        override suspend fun closeSession(sessionId: String) {
            closed.add(sessionId)
        }

        override suspend fun uninstallEnvironment() = Result.success(Unit)
    }

    private fun manager(runtime: FakeRuntime) = BackgroundJobManager(runtime).apply {
        stopGraceMs = 10
    }

    @Test
    fun `starting a job returns at once and keeps the session to itself`(): Unit = runBlocking {
        val runtime = FakeRuntime().apply { autoRespond = false }
        val jobs = manager(runtime)

        val job = jobs.start("apt-get install -y openjdk-17-jdk", "ws", "/exec/ws").getOrThrow()

        assertThat(job.state).isEqualTo(BackgroundJobState.RUNNING)
        assertThat(job.running).isTrue()
        assertThat(job.exitCode).isNull()
        // The executor must not reuse (and then kill) the shell a job is working in.
        assertThat(jobs.isOwned("s1")).isTrue()
        // The command travels base64-encoded, so it reaches the shell byte-for-byte without the
        // tty re-wrapping or mangling it.
        val encoded = Regex("printf %s '([A-Za-z0-9+/=]+)'").findAll(runtime.sent.single())
            .joinToString("") { it.groupValues[1] }
        val payload = String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        assertThat(payload).contains("apt-get install -y openjdk-17-jdk")
        // The sentinel is on the same input line as the command, so a stdin-reading command cannot
        // swallow it, and it is built by the shell so the echo never contains it.
        assertThat(runtime.sent.single()).contains("__KERN_JOB_")
    }

    @Test
    fun `a finished job records its exit code and its output`(): Unit = runBlocking {
        val runtime = FakeRuntime()
        val jobs = manager(runtime)

        val job = jobs.start("npm install", "ws", "/exec/ws").getOrThrow()
        assertThat(waitUntil { jobs.status(job.id)?.state?.isTerminal == true }).isTrue()

        val finished = jobs.status(job.id)
        assertThat(finished?.state).isEqualTo(BackgroundJobState.SUCCEEDED)
        assertThat(finished?.exitCode).isEqualTo(0)
        assertThat(finished?.output).contains("downloading")
        // The sentinel is plumbing, never part of what the command printed.
        assertThat(finished?.output).doesNotContain("__KERN_JOB_")
        assertThat(finished?.finishedAt).isNotNull()
        // The session is closed once the command is done, so jobs do not leak shells.
        assertThat(runtime.closed).contains("s1")
    }

    @Test
    fun `a non-zero exit code marks the job failed`(): Unit = runBlocking {
        val runtime = FakeRuntime().apply { exitCode = 100 }
        val jobs = manager(runtime)

        val job = jobs.start("apt-get install -y nope", "ws", "/exec/ws").getOrThrow()
        assertThat(waitUntil { jobs.status(job.id)?.state?.isTerminal == true }).isTrue()

        assertThat(jobs.status(job.id)?.state).isEqualTo(BackgroundJobState.FAILED)
        assertThat(jobs.status(job.id)?.exitCode).isEqualTo(100)
    }

    @Test
    fun `wait returns as soon as the job finishes`(): Unit = runBlocking {
        val runtime = FakeRuntime()
        val jobs = manager(runtime)

        val job = jobs.start("make", "ws", "/exec/ws").getOrThrow()
        val finished = jobs.await(job.id, 5_000)

        assertThat(finished?.state).isEqualTo(BackgroundJobState.SUCCEEDED)
        assertThat(finished?.running).isFalse()
    }

    @Test
    fun `wait hands back a still-running job when its budget runs out`(): Unit = runBlocking {
        val runtime = FakeRuntime().apply { autoRespond = false }
        val jobs = manager(runtime)

        val job = jobs.start("sleep 600", "ws", "/exec/ws").getOrThrow()
        val waited = jobs.await(job.id, 50)

        // Still running is a normal answer, not an error: the agent waits again later.
        assertThat(waited?.running).isTrue()
        assertThat(waited?.state).isEqualTo(BackgroundJobState.RUNNING)
    }

    @Test
    fun `stopping a job interrupts it and closes its session`(): Unit = runBlocking {
        val runtime = FakeRuntime().apply { autoRespond = false }
        val jobs = manager(runtime)

        val job = jobs.start("sleep 600", "ws", "/exec/ws").getOrThrow()
        assertThat(jobs.stop(job.id)).isTrue()

        assertThat(runtime.stopped).isEmpty() // Ctrl-C goes through sendInput, not requestStop
        assertThat(runtime.sent.last()).isEqualTo(String(byteArrayOf(3), Charsets.UTF_8))
        assertThat(runtime.closed).contains("s1")
        assertThat(jobs.status(job.id)?.state).isEqualTo(BackgroundJobState.STOPPED)
        assertThat(jobs.isOwned("s1")).isFalse()
    }

    @Test
    fun `output can be read in slices from a cursor`(): Unit = runBlocking {
        val runtime = FakeRuntime().apply { outputText = "0123456789" }
        val jobs = manager(runtime)

        val job = jobs.start("echo 0123456789", "ws", "/exec/ws").getOrThrow()
        assertThat(waitUntil { jobs.status(job.id)?.state?.isTerminal == true }).isTrue()

        val all = jobs.output(job.id, 0)
        assertThat(all?.text).contains("0123456789")
        val rest = jobs.output(job.id, 5)
        assertThat(rest?.text).isEqualTo(all?.text?.substring(5))
        assertThat(rest?.nextCursor).isEqualTo(all?.text?.length)
    }


    @Test
    fun `the job script is never written before its collector is attached`(): Unit = runBlocking {
        // A job that finishes instantly prints its output and its sentinel in one chunk. Losing
        // that chunk left the job RUNNING with nothing left that could ever finish it.
        val runtime = FakeRuntime()
        val jobs = manager(runtime)

        val job = jobs.start("echo hi", "ws", "/exec/ws").getOrThrow()
        assertThat(waitUntil { jobs.status(job.id)?.state?.isTerminal == true }).isTrue()

        assertThat(runtime.wroteBeforeSubscribing).isFalse()
        assertThat(jobs.status(job.id)?.state).isEqualTo(BackgroundJobState.SUCCEEDED)
    }

    @Test
    fun `a shell that never accepts the command leaves no running job behind`(): Unit = runBlocking {
        val runtime = FakeRuntime().apply { acceptsCommands = false }
        val jobs = manager(runtime)

        val started = jobs.start("sleep 600", "ws", "/exec/ws")

        assertThat(started.isFailure).isTrue()
        assertThat(jobs.jobs.value.none { it.running }).isTrue()
        assertThat(jobs.jobs.value.single().state).isEqualTo(BackgroundJobState.FAILED)
        // The shell it opened is not left owned by a job that never started.
        assertThat(jobs.isOwned("s1")).isFalse()
    }

    @Test
    fun `a running job publishes its output as it arrives`(): Unit = runBlocking {
        // `background_output` and the transcript card used to see nothing until the job ended, so
        // a long install looked silent while it was actually working.
        val runtime = FakeRuntime().apply { autoRespond = false }
        val jobs = manager(runtime)

        val job = jobs.start("apt-get install -y openjdk-17-jdk", "ws", "/exec/ws").getOrThrow()
        // The start marker is what tells the gate where the echo ends, so it comes first.
        runtime.emit("s1", "reading package lists\n")

        assertThat(waitUntil { jobs.status(job.id)?.output?.contains("reading package lists") == true })
            .isTrue()
        assertThat(jobs.status(job.id)?.state).isEqualTo(BackgroundJobState.RUNNING)
        assertThat(jobs.output(job.id, 0)?.text).contains("reading package lists")
    }

    @Test
    fun `an unknown job id is reported as unknown`(): Unit = runBlocking {
        val jobs = manager(FakeRuntime())
        assertThat(jobs.status("job-nope")).isNull()
        assertThat(jobs.output("job-nope", 0)).isNull()
        assertThat(jobs.await("job-nope", 10)).isNull()
        assertThat(jobs.stop("job-nope")).isFalse()
    }
}
