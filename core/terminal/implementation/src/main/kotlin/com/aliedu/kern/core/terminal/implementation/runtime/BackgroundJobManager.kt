package com.aliedu.kern.core.terminal.implementation.runtime

import com.aliedu.kern.core.terminal.api.ExecutionWorkspace
import com.aliedu.kern.core.terminal.api.TerminalOutput
import com.aliedu.kern.core.terminal.api.TerminalRuntime
import com.aliedu.kern.core.tools.CappedBuffer
import com.aliedu.kern.core.tools.background.BackgroundJob
import com.aliedu.kern.core.tools.background.BackgroundJobRegistry
import com.aliedu.kern.core.tools.background.BackgroundJobState
import com.aliedu.kern.core.tools.background.BackgroundOutput
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs finite commands in the background (PRD §4.3 bullet 8).
 *
 * The foreground path cannot express "start this and tell me later": it waits for a sentinel and,
 * on timeout, kills the whole process group — which is what turned a slow `apt install` into a
 * half-configured dpkg. A job instead gets its own session and lives in a process-lifetime scope,
 * so it keeps running after the tool call that started it has returned.
 *
 * The session is owned: [isOwned] lets the command executor refuse to reuse it, so a later
 * `run_command` can never be typed into a job that is still working.
 */
@Singleton
class BackgroundJobManager @Inject constructor(
    private val runtime: TerminalRuntime
) : BackgroundJobRegistry {

    private val _jobs = MutableStateFlow<List<BackgroundJob>>(emptyList())
    override val jobs: StateFlow<List<BackgroundJob>> = _jobs.asStateFlow()

    /** Sessions hosting a job; the executor must not reuse them. */
    private val owned = ConcurrentHashMap.newKeySet<String>()

    /** Mutable per-job state: output, echo handling and the sentinel scan. */
    private val runs = ConcurrentHashMap<String, JobRun>()

    /** Completed when a job reaches a terminal state, so `background_wait` can block on it. */
    private val signals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Process-lifetime scope: a job outlives the tool call that started it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How long to let an interrupt land before the session is closed. A property for tests. */
    internal var stopGraceMs: Long = 400L

    override fun isOwned(sessionId: String): Boolean = sessionId in owned

    override suspend fun start(
        command: String,
        workspaceId: String,
        cwd: String,
        maxOutputChars: Int
    ): Result<BackgroundJob> {
        val id = "job-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val session = try {
            runtime.createSession(ExecutionWorkspace(workspaceId, cwd))
        } catch (e: Exception) {
            return Result.failure(
                IllegalStateException("Could not open a session for the job: " + e.message)
            )
        }
        owned.add(session.id)
        runCatching { runtime.renameSession(session.id, "Job " + id.takeLast(4)) }

        // A unique sentinel echoed after the command is how the exit code comes back: the command
        // may never exit, and the shell stays alive afterwards, so there is nothing else to observe.
        // The tty echoes the *text* of this line too, but with `$?` unexpanded — no digits — so a
        // numeric match can only be the real sentinel.
        val marker = "__KERN_JOB_" + UUID.randomUUID().toString().replace("-", "") + "__"
        val markerPattern = Regex(Regex.escape(marker) + """\s+(-?\d+)""")
        val script = "cd -- " + shellQuote(cwd) + " 2>/dev/null || true; " + command +
            "\necho \"" + marker + " " + EXIT_STATUS + "\"\n"

        val run = JobRun(script, marker, maxOutputChars)
        runs[id] = run
        signals[id] = CompletableDeferred()
        // Registered before the command is sent, so a job that finishes instantly cannot be
        // overwritten back to RUNNING by the upsert below.
        upsert(
            BackgroundJob(
                id = id,
                command = command,
                workspaceId = workspaceId,
                cwd = cwd,
                state = BackgroundJobState.RUNNING,
                startedAt = System.currentTimeMillis(),
                sessionId = session.id
            )
        )

        val subscribed = CompletableDeferred<Unit>()
        scope.launch {
            runtime.observeOutput(session.id)
                .onStart { subscribed.complete(Unit) }
                .collect { output ->
                    val text = when (output) {
                        is TerminalOutput.Stdout -> output.text
                        is TerminalOutput.Stderr -> output.text
                        is TerminalOutput.Exited -> {
                            if (!run.done) {
                                run.done = true
                                run.flush()
                                finish(id, output.exitCode)
                            }
                            ""
                        }
                    }
                    if (run.done || text.isEmpty()) return@collect
                    run.accept(text)
                    val exitCode = run.exitCode(markerPattern)
                    if (exitCode != null) {
                        run.done = true
                        run.flush()
                        finish(id, exitCode)
                    }
                }
        }

        // Subscribe before sending, or a job whose command prints immediately loses its first
        // chunk and its sentinel.
        subscribed.await()
        runtime.sendInput(session.id, script.toByteArray(Charsets.UTF_8))
        val job = status(id)
            ?: return Result.failure(IllegalStateException("The job disappeared while starting."))
        return Result.success(job)
    }

    override fun status(id: String): BackgroundJob? = _jobs.value.firstOrNull { it.id == id }

    override fun output(id: String, cursor: Int): BackgroundOutput? {
        val job = status(id) ?: return null
        val text = job.output
        val from = cursor.coerceIn(0, text.length)
        return BackgroundOutput(
            jobId = job.id,
            state = job.state,
            text = text.substring(from),
            nextCursor = text.length,
            truncated = job.truncated,
            totalChars = job.outputChars
        )
    }

    override suspend fun await(id: String, timeoutMs: Long): BackgroundJob? {
        val job = status(id) ?: return null
        if (job.state.isTerminal) return job
        val signal = signals.computeIfAbsent(id) { CompletableDeferred() }
        withTimeoutOrNull(timeoutMs) { signal.await() }
        return status(id)
    }

    override suspend fun stop(id: String): Boolean {
        val job = status(id) ?: return false
        if (job.state.isTerminal) return false
        runs[id]?.let { it.done = true; it.flush() }
        val sessionId = job.sessionId
        if (sessionId != null) {
            // Ctrl-C first so the command can shut down cleanly, then tear the session down.
            runCatching { runtime.sendInput(sessionId, CTRL_C) }
            delay(stopGraceMs)
            runCatching { runtime.closeSession(sessionId) }
            owned.remove(sessionId)
        }
        settle(job, BackgroundJobState.STOPPED, exitCode = job.exitCode)
        return true
    }

    /** Records the final state of [id] once, closes its session and wakes anyone waiting. */
    private suspend fun finish(id: String, exitCode: Int?) {
        val job = status(id) ?: return
        if (job.state.isTerminal) return
        val state = if (exitCode == 0) BackgroundJobState.SUCCEEDED else BackgroundJobState.FAILED
        settle(job, state, exitCode)
    }

    private suspend fun settle(job: BackgroundJob, state: BackgroundJobState, exitCode: Int?) {
        val run = runs[job.id]
        upsert(
            job.copy(
                state = state,
                exitCode = exitCode,
                finishedAt = System.currentTimeMillis(),
                output = run?.text() ?: job.output,
                truncated = run?.truncated ?: false,
                outputChars = run?.totalChars ?: job.output.length
            )
        )
        signals.remove(job.id)?.complete(Unit)
        val sessionId = job.sessionId
        if (sessionId != null) {
            runCatching { runtime.closeSession(sessionId) }
            owned.remove(sessionId)
        }
    }

    private fun upsert(job: BackgroundJob) {
        _jobs.value = _jobs.value.filterNot { it.id == job.id } + job
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Everything mutable about one running job. */
    private class JobRun(script: String, private val marker: String, maxOutputChars: Int) {

        private val buffer = CappedBuffer(maxOutputChars)
        private val gate = EchoGate(script)

        /** The last few hundred raw characters, scanned for the sentinel. */
        private val tail = StringBuilder()

        var done: Boolean = false

        /** Feeds one chunk in. The output is kept; the sentinel is found by [exitCode]. */
        fun accept(text: String) {
            tail.append(text)
            if (tail.length > MARKER_TAIL) tail.delete(0, tail.length - MARKER_TAIL)
            val body = gate.accept(text)
            if (body != null && body.isNotEmpty()) buffer.append(body)
        }

        /** The sentinel's exit status once the command has finished, else null. */
        fun exitCode(pattern: Regex): Int? =
            pattern.find(tail)?.groupValues?.get(1)?.toIntOrNull()

        /**
         * Empties whatever the echo filter is still holding. A pty without echo (or a test double)
         * would otherwise keep the whole output in the gate until it grew past the safety limit.
         */
        fun flush() {
            val rest = gate.flush()
            if (rest.isNotEmpty()) buffer.append(rest)
        }

        /** The retained output, without the sentinel line the shell echoed back. */
        fun text(): String = stripSentinel(buffer.text(), marker)

        val truncated: Boolean get() = buffer.truncated

        val totalChars: Int get() = buffer.total

        private fun stripSentinel(text: String, marker: String): String {
            val at = text.indexOf(marker)
            if (at < 0) return text
            val lineStart = text.lastIndexOf('\n', at)
            return text.substring(0, if (lineStart < 0) 0 else lineStart)
        }
    }

    /**
     * Drops the tty's echo of the script from the head of the stream.
     *
     * The shell prints back what we typed. Matching the script's first line and skipping to the
     * next newline removes it, so the job's output starts with the command's own first line.
     */
    private class EchoGate(script: String) {

        private val firstLine = script.substringBefore('\n')
        private val head = StringBuilder()
        private var open = false

        /** Returns the part of [text] that is real output, or null while still buffering the echo. */
        fun accept(text: String): String? {
            if (open) return text
            head.append(text)
            val end = TtyText.findEchoEnd(head.toString(), firstLine)
            if (end >= 0) {
                val newline = head.indexOf('\n', end)
                if (newline < 0) return null
                open = true
                return drain(newline + 1)
            }
            // Safety: never buffer unboundedly if the echo never matches (no tty, or echo off).
            if (head.length > firstLine.length * 2 + 256) {
                open = true
                return drain(0)
            }
            return null
        }

        /** Gives up on echo matching and returns everything held so far. */
        fun flush(): String {
            if (open) return ""
            open = true
            return drain(0)
        }

        private fun drain(from: Int): String {
            val rest = head.substring(from)
            head.setLength(0)
            return rest
        }
    }

    private companion object {
        /** How much recent output is kept for sentinel scanning; the sentinel is ~50 chars. */
        const val MARKER_TAIL = 512

        /** Ctrl-C, the polite way to stop a command in a shell. */
        val CTRL_C = byteArrayOf(3)

        /** Expanded by the shell into the last command's exit status. */
        const val EXIT_STATUS = "\$?"
    }
}
