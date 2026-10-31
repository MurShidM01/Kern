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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
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

        // A unique sentinel is how the exit code comes back: the shell stays alive afterwards, so
        // there is nothing else to observe. It is built by the shell from a seed, so the echo of
        // this script contains the seed but never the marker — the gate below can then drop the
        // echo without matching it.
        val seed = UUID.randomUUID().toString().replace("-", "").take(12)
        val marker = "__KERN_JOB_" + seed + "__"
        val markerPattern = Regex(Regex.escape(marker) + """\s+(-?\d+)""")
        // [cwd] is a host path. Inside proot the workspace is bound at /workspace, so a `cd` to the
        // host spelling fails and the job quietly runs in the guest home instead — which is wrong
        // for anything that reads the project (`npm install`, `gradle build`). Ask the runtime for
        // the spelling its shell actually understands, exactly like the foreground executor does.
        val guestCwd = runCatching { runtime.guestPath(cwd) }.getOrNull() ?: cwd
        // The command travels base64-encoded into a file the shell runs, so the exit sentinel is on
        // the SAME input line as the command. A command that reads stdin — apt, debconf, anything
        // with a prompt — used to swallow a sentinel typed on the next line, and the job then
        // stayed RUNNING with the prompt already back on screen.
        val cmdFile = "/tmp/.kern_" + seed
        val payload = "cd -- " + shellQuote(guestCwd) + " 2>/dev/null || true\n" + command
        val encoded = Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
        val script = buildString {
            append("KERN_S=").append(seed)
            append("; KERN_M=\"__KERN_JOB_").append(DOLLAR).append("{KERN_S}__\"")
            append("; printf '\\n%s\\n' \"").append(DOLLAR).append("KERN_M\"\n")
            append(": > ").append(cmdFile).append('\n')
            encoded.chunked(BASE64_CHUNK_CHARS).forEach { chunk ->
                append("printf %s '").append(chunk).append("' >> ").append(cmdFile).append('\n')
            }
            append("base64 -d ").append(cmdFile).append(" > ").append(cmdFile).append(".sh; ")
            append("sh ").append(cmdFile).append(".sh; KERN_E=").append(DOLLAR).append("?; ")
            append("printf '\\n%s %s\\n' \"").append(DOLLAR).append("KERN_M\" \"")
                .append(DOLLAR).append("KERN_E\"; rm -f ").append(cmdFile).append(' ')
                .append(cmdFile).append(".sh\n")
        }

        val run = JobRun(marker, maxOutputChars, script.length * 2 + 2_048)
        val startSignal = CompletableDeferred<Unit>()
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

        // Subscribe *before* writing, and prove it. `onStart` runs before the shared flow registers
        // the collector, so a job whose command finishes immediately could lose its only chunk —
        // output and sentinel together — and then stay RUNNING forever with nothing left to end it.
        val subscribersBefore = runtime.outputSubscriberCount(session.id)
        val trailing = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>(null)
        val collector = scope.launch {
            var lastPublish = 0L
            runtime.observeOutput(session.id)
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
                    if (run.started && !startSignal.isCompleted) startSignal.complete(Unit)
                    // Publish what has arrived so `background_output` and the transcript card see
                    // live progress instead of an empty buffer until the job ends.
                    val now = System.currentTimeMillis()
                    val since = now - lastPublish
                    if (since >= PROGRESS_PUBLISH_MS) {
                        lastPublish = now
                        publishProgress(id)
                    } else if (trailing.get() == null) {
                        // Throttled, but the text that just arrived is the newest thing the user
                        // can see. Publish it once the window closes instead of waiting for the
                        // next chunk, which for a slow command may be minutes away.
                        trailing.set(
                            scope.launch {
                                delay(PROGRESS_PUBLISH_MS - since)
                                trailing.set(null)
                                lastPublish = System.currentTimeMillis()
                                publishProgress(id)
                            }
                        )
                    }
                    val exitCode = run.exitCode(markerPattern)
                    if (exitCode != null) {
                        run.done = true
                        run.flush()
                        finish(id, exitCode)
                    }
                }
        }
        // A collector that ends without a result — the session was closed by an environment install,
        // the shell was reaped — leaves the job RUNNING with nothing left to finish it.
        collector.invokeOnCompletion {
            if (!run.done) {
                run.done = true
                scope.launch { finish(id, EXIT_STREAM_LOST) }
            }
        }

        val attached = runtime.awaitOutputSubscriber(session.id, subscribersBefore + 1)
        if (!attached || !runtime.isSessionAlive(session.id)) {
            run.done = true
            collector.cancel()
            status(id)?.let { settle(it, BackgroundJobState.FAILED, null) }
            return Result.failure(
                IllegalStateException(
                    "The shell did not accept the command, so the job was not started."
                )
            )
        }

        runtime.sendInput(session.id, script.toByteArray(Charsets.UTF_8))

        // The marker is printed before the command, so a shell at a prompt shows it at once. If it
        // never arrives the shell is still busy with an earlier command and is swallowing input;
        // fail the job rather than leaving a row that spins for as long as the app is open.
        if (withTimeoutOrNull(START_HANDSHAKE_MS) { startSignal.await() } == null) {
            run.done = true
            collector.cancel()
            status(id)?.let { settle(it, BackgroundJobState.FAILED, null) }
            return Result.failure(
                IllegalStateException("The shell did not run the command, so the job was not started.")
            )
        }

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
        // EXIT_STREAM_LOST is an internal marker, not a status a command ever returns. Reporting it
        // as the exit code would look like a real one to the model and to the job card.
        val reported = exitCode?.takeIf { it != EXIT_STREAM_LOST }
        val state = if (reported == 0) BackgroundJobState.SUCCEEDED else BackgroundJobState.FAILED
        settle(job, state, reported)
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
        // The job now owns the text; the duplicate buffer would only keep the memory alive.
        runs.remove(job.id)
    }

    /** Republishes a running job with the output produced so far, for live readers. */
    private fun publishProgress(id: String) {
        val job = status(id) ?: return
        if (job.state.isTerminal) return
        val run = runs[id] ?: return
        upsert(
            job.copy(
                output = run.text(),
                truncated = run.truncated,
                outputChars = run.totalChars
            )
        )
    }

    private fun upsert(job: BackgroundJob) {
        _jobs.value = _jobs.value.filterNot { it.id == job.id } + job
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Everything mutable about one running job. */
    private class JobRun(
        private val marker: String,
        maxOutputChars: Int,
        holdLimit: Int
    ) {

        private val buffer = CappedBuffer(maxOutputChars)
        private val gate = OutputGate(marker, holdLimit)

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

        /** The retained output: no echo, no sentinel, no escape sequences. */
        fun text(): String = TtyText.stripAnsi(stripSentinel(buffer.text(), marker))
            .trim('\n', ' ', '\r')

        /** True once the shell printed the start marker, i.e. the command is actually running. */
        val started: Boolean get() = gate.opened

        val truncated: Boolean get() = buffer.truncated

        val totalChars: Int get() = buffer.total

        private fun stripSentinel(text: String, marker: String): String {
            // A sentinel split across two reads still ends the output: match its opening, which no
            // command prints by accident, so a half-line never shows up as progress.
            val at = text.indexOf(marker).takeIf { it >= 0 } ?: text.indexOf(SENTINEL_HEAD)
            if (at < 0) return text
            val lineStart = text.lastIndexOf('\n', at)
            return text.substring(0, if (lineStart < 0) 0 else lineStart)
        }
    }


    private companion object {
        /** First characters of every sentinel; a partial one is never command output. */
        const val SENTINEL_HEAD = "__KERN_"

        /** How much recent output is kept for sentinel scanning; the sentinel is ~50 chars. */
        const val MARKER_TAIL = 512

        /** Ctrl-C, the polite way to stop a command in a shell. */
        val CTRL_C = byteArrayOf(3)

        /** The shell variable prefix the script uses to build its marker without echoing it. */
        const val DOLLAR = "$"

        /** Base64 characters per line; small enough that the tty cannot truncate one. */
        const val BASE64_CHUNK_CHARS = 2_000

        /** How long the shell gets to print the start marker before it is judged unresponsive. */
        const val START_HANDSHAKE_MS = 8_000L

        /** How often a running job republishes its output for live readers. */
        const val PROGRESS_PUBLISH_MS = 500L

        /** Internal marker: the output stream ended before the command reported an exit status. */
        const val EXIT_STREAM_LOST = -1001
    }
}
