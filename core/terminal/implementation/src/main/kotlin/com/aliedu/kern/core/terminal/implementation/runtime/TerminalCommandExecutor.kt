package com.aliedu.kern.core.terminal.implementation.runtime

import com.aliedu.kern.core.terminal.api.ExecutionWorkspace
import com.aliedu.kern.core.terminal.api.SessionKind
import com.aliedu.kern.core.terminal.api.TerminalOutput
import com.aliedu.kern.core.terminal.api.TerminalRuntime
import com.aliedu.kern.core.terminal.api.TerminalSession
import com.aliedu.kern.core.tools.CommandExecutor
import com.aliedu.kern.core.tools.CommandRequest
import com.aliedu.kern.core.tools.CommandResult
import com.aliedu.kern.core.tools.CappedBuffer
import com.aliedu.kern.core.tools.OutputPolicy
import com.aliedu.kern.core.tools.TerminalCommandRecord
import com.aliedu.kern.core.tools.background.BackgroundJobRegistry
import com.aliedu.kern.core.tools.devserver.DevServerRegistry
import com.aliedu.kern.core.workspace.ExecutionWorkspaceResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs an approved command in the workspace's execution directory (PRD FR-017).
 *
 * Reuses a live session at the same working directory when one exists, otherwise starts a new one.
 * Output is captured up to the request's cap and the run is never bounded by a timeout: the command
 * exits, or the caller cancels. Its exit code is recovered with a unique sentinel echoed after it,
 * and the wait ends on the sentinel, on the shell dying, or on the output stream closing — whichever
 * happens first. Cancelling the caller kills the process group and closes the shell.
 */
@Singleton
class TerminalCommandExecutor @Inject constructor(
    private val runtime: TerminalRuntime,
    private val resolver: ExecutionWorkspaceResolver,
    private val devServers: DevServerRegistry,
    private val backgroundJobs: BackgroundJobRegistry,
    private val tracker: AgentTerminalTracker
) : CommandExecutor {

    /**
     * Sessions with a foreground run in flight. Two commands typed into one shell interleave their
     * output and each can consume the other's sentinel, so a second concurrent run gets its own
     * shell instead of sharing one. The agent, the package manager and the inline read-only tools
     * all reach this executor, so this is not hypothetical.
     */
    private val busySessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override suspend fun run(
        request: CommandRequest,
        onOutput: ((String) -> Unit)?
    ): CommandResult {
        // A server never returns to the prompt, so the sentinel/exit-code path below would always
        // time out and then kill it. Detached runs go to the dev-server manager instead, which
        // waits only for the port to accept connections.
        if (request.detach) return runDetached(request, onOutput)
        // A background job is a *finite* command that outlives the tool call: it is tracked until
        // its command prints an exit status, so nothing here waits for it or kills it on a timeout.
        if (request.background) return runInBackground(request)
        return runToCompletion(request, onOutput)
    }

    /** Starts a background server and reports its URL, without waiting for the process to exit. */
    private suspend fun runDetached(
        request: CommandRequest,
        onOutput: ((String) -> Unit)?
    ): CommandResult {
        val started = System.currentTimeMillis()
        val workspace = resolver.prepare(request.workspaceId)
        val cwd = request.cwd.takeIf { it.isNotBlank() } ?: workspace.workingDirectory
        return devServers.start(request.command, request.port, request.workspaceId, cwd, onOutput)
            .fold(
                onSuccess = { server ->
                    CommandResult(
                        exitCode = 0,
                        output = "Server is running at ${server.url} " +
                            "(id=${server.id}, port=${server.port}). Open the preview to see it, " +
                            "then use preview_console to check for errors.",
                        durationMs = System.currentTimeMillis() - started
                    )
                },
                onFailure = { error ->
                    CommandResult(
                        exitCode = 1,
                        output = error.message ?: "The server did not start.",
                        durationMs = System.currentTimeMillis() - started
                    )
                }
            )
    }

    /**
     * Hands a finite command to the job manager and returns at once with its id. The job keeps
     * running after this tool call ends, which is the whole point: an install that needs five
     * minutes no longer has to fit inside a foreground timeout, and the agent can keep working.
     */
    private suspend fun runInBackground(request: CommandRequest): CommandResult {
        val started = System.currentTimeMillis()
        val workspace = resolver.prepare(request.workspaceId)
        val cwd = request.cwd.takeIf { it.isNotBlank() } ?: workspace.workingDirectory
        return backgroundJobs.start(request.command, request.workspaceId, cwd, request.maxOutputChars)
            .fold(
                onSuccess = { job ->
                    CommandResult(
                        exitCode = null,
                        output = "Started background job " + job.id + " (" +
                            request.command.take(120) + "). It is still running; read it with " +
                            "background_output, wait for it with background_wait, or stop it " +
                            "with background_stop.",
                        durationMs = System.currentTimeMillis() - started,
                        jobId = job.id,
                        running = true
                    )
                },
                onFailure = { error ->
                    CommandResult(
                        exitCode = 1,
                        output = error.message ?: "The background job did not start.",
                        durationMs = System.currentTimeMillis() - started
                    )
                }
            )
    }

    /**
     * Runs [request] in the workspace shell, restarting once on a shell that never started it.
     *
     * A shell can be left busy by an earlier command that is still waiting on input — `apt` asking
     * a question, a `read`, a pager — and everything typed into it afterwards is either queued or
     * swallowed. [attempt] reports that case as null instead of waiting on it, so this can replace
     * the shell and try once more rather than hanging for as long as the app is open.
     */
    private suspend fun runToCompletion(
        request: CommandRequest,
        onOutput: ((String) -> Unit)?
    ): CommandResult {
        val workspace = resolver.prepare(request.workspaceId)
        val cwd = request.cwd.takeIf { it.isNotBlank() } ?: workspace.workingDirectory
        attempt(request, cwd, onOutput, restartable = true)?.let { return it }
        return attempt(request, cwd, onOutput, restartable = false)
            ?: CommandResult(
                exitCode = -1,
                output = SHELL_UNRESPONSIVE_NOTE,
                durationMs = 0
            )
    }

    /**
     * One run against one shell. Null means the shell never even printed the start marker, so the
     * command did not run — the caller may retry in a fresh shell.
     */
    private suspend fun attempt(
        request: CommandRequest,
        cwd: String,
        onOutput: ((String) -> Unit)?,
        restartable: Boolean
    ): CommandResult? = coroutineScope {
        val started = System.currentTimeMillis()
        val session = resolveSession(request.workspaceId, cwd)
        // Claim the shell for this run. Anything else that arrives while this one is in flight
        // resolves a different session instead of typing into this one.
        busySessions.add(session.id)
        // The agent keeps ONE shell per workspace and every command starts in the workspace root.
        // Always re-entering the directory (rather than trusting the tracked cwd) is what stops a
        // `cd /sdcard/Downloads` inside one command from silently moving every later command out of
        // the project — the tracker cannot see the shell's real directory, so it must not guess.
        //
        // The cd must not be fatal. [cwd] is a host path, and an app-managed workspace lives under
        // /data/user/0/<package>/…, which is not bound inside the rootfs: with `&&` every guest
        // command (apt, dpkg, the package catalogue) died with "No such file or directory" even
        // though the command itself had nothing to do with the workspace.
        //
        // It must not be silent either. Inside proot the same directory is reachable at a *guest*
        // path (/sdcard/<rel> or /workspace), so a host path there fails, the shell keeps whatever
        // directory it was left in, and the command runs in the wrong place while the tracker still
        // records the workspace root. Ask the runtime for the guest spelling, and say so when even
        // that does not exist.
        val guestCwd = runCatching { runtime.guestPath(cwd) }.getOrNull()
        // Double quotes, so $(pwd) is expanded by the shell: the message is useless if it prints
        // the literal text instead of the directory the command actually ran in.
        val cdInto = "cd -- " + shellQuote(guestCwd ?: cwd) + " 2>/dev/null || echo \"" +
            CWD_WARNING + "\""

        // The marker is built by the shell from a seed, so the terminal's echo of this script
        // contains the seed but never the marker. That is what lets [OutputGate] drop the echo
        // without matching it character by character — a match that fails on any long line.
        val seed = UUID.randomUUID().toString().replace("-", "").take(12)
        val marker = "__KERN_" + seed + "__"
        // The command itself travels base64-encoded into a file the shell then runs, so the exit
        // sentinel is on the SAME input line as the command. That is the fix for the worst bug in
        // this path: the sentinel used to be the next line typed after the command, and a command
        // that reads stdin — apt, debconf, anything with a prompt — swallowed it. The command then
        // finished, the prompt came back, and the tool waited for an exit status that had been
        // eaten, with no timeout to end it.
        //
        // An interactive shell also reads its input one line at a time, so nothing after the first
        // line is safe while the command runs. One line is parsed completely before anything runs.
        val cmdFile = "/tmp/.kern_" + seed
        // The cd travels with the command, inside the file, so it applies to the command's own
        // subshell and cannot be eaten by anything either.
        val payload = cdInto + "\n" + request.command
        val encoded = Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
        val script = buildString {
            append("KERN_S=").append(seed)
            append("; KERN_M=\"__KERN_").append(DOLLAR).append("{KERN_S}__\"")
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
        // Keeps the head *and* the tail: a failing install prints its error last, and a cap that
        // only keeps the first N characters hands the model the one part that explains nothing.
        val buffer = CappedBuffer(request.maxOutputChars)
        // output_policy = stream means the user watches it and the model does not, so there is
        // nothing to retain.
        val capture = request.outputPolicy == OutputPolicy.CAPTURE
        // Compiled once: the collector runs per PTY chunk and a large listing arrives in thousands of
        // chunks, so recompiling the pattern each time measurably throttles the stream.
        val markerPattern = markerRegex(marker)
        // Marker detection runs on a small rolling tail, NOT on [buffer]: [buffer] is capped at
        // [cap], so for a large output (e.g. listing every apt package) the sentinel would be
        // dropped and the run would appear to hang until the timeout fired.
        val tail = StringBuilder()
        // The tty echoes what we type. Everything before the start marker is that echo; the gate
        // holds it back so neither the model nor the live stream ever sees the script as output.
        val gate = OutputGate(marker, holdLimit = script.length * 2 + 2_048)
        // Completed as soon as the shell prints the start marker: proof that it is running the
        // script rather than still busy with whatever ran before it.
        val startSignal = CompletableDeferred<Unit>()
        val exit = CompletableDeferred<Int>()
        // The collector must be attached *before* the script is written. A fast command prints its
        // output and its sentinel in one chunk; if nobody is subscribed yet that chunk is emitted to
        // nobody, the sentinel never arrives, and with no timeout the run waits forever. `onStart`
        // is not proof of subscription — it fires before the shared flow registers the collector —
        // so the count is taken first and the wait below asks for exactly one more.
        val subscribersBefore = runtime.outputSubscriberCount(session.id)

        val collector = launch(Dispatchers.IO) {
            runtime.observeOutput(session.id)
                .collect { output ->
                    val text = when (output) {
                        is TerminalOutput.Stdout -> output.text
                        is TerminalOutput.Stderr -> output.text
                        is TerminalOutput.Exited -> {
                            if (!exit.isCompleted) exit.complete(-1)
                            ""
                        }
                    }
                    if (text.isEmpty()) return@collect

                    // The sentinel is scanned in the raw stream: the shell builds it, so the echo
                    // never contains it and a match can only be the real exit status.
                    tail.append(text)
                    if (tail.length > MARKER_TAIL) tail.delete(0, tail.length - MARKER_TAIL)
                    markerPattern.find(tail)?.let { match ->
                        if (!exit.isCompleted) exit.complete(match.groupValues[1].toIntOrNull() ?: 0)
                    }

                    val body = gate.accept(text)
                    if (gate.opened && !startSignal.isCompleted) startSignal.complete(Unit)
                    if (body.isEmpty()) return@collect
                    if (capture) buffer.append(body)
                    onOutput?.let { sink -> emitLive(sink, markerPattern, body) }
                }
        }

        // A stream that ends before the sentinel — the session was closed by an environment
        // install, the shell was reaped, the manager dropped it — must end the run too. Waiting on
        // a collector that has already completed is the other way this hung forever.
        collector.invokeOnCompletion {
            if (!exit.isCompleted) exit.complete(EXIT_STREAM_LOST)
        }

        // A command that never starts must not look like a command that never ends.
        val attached = runtime.awaitOutputSubscriber(session.id, subscribersBefore + 1)
        if (!attached || !runtime.isSessionAlive(session.id)) {
            collector.cancel()
            busySessions.remove(session.id)
            tracker.unbind(request.workspaceId, session.id)
            runCatching { runtime.closeSession(session.id) }
            return@coroutineScope if (restartable) {
                null
            } else {
                CommandResult(
                    exitCode = -1,
                    output = if (!attached) SESSION_UNREACHABLE_NOTE else SESSION_GONE_NOTE,
                    durationMs = System.currentTimeMillis() - started
                )
            }
        }

        runtime.sendInput(session.id, script.toByteArray(Charsets.UTF_8))

        // The marker is the first thing the script prints, so it lands in milliseconds when the
        // shell is at a prompt. If it never arrives the shell is still busy with an earlier
        // command and is eating everything written to it — replace it instead of waiting.
        if (withTimeoutOrNull(START_HANDSHAKE_MS) { startSignal.await() } == null) {
            collector.cancel()
            busySessions.remove(session.id)
            runCatching { runtime.closeSession(session.id) }
            tracker.unbind(request.workspaceId, session.id)
            return@coroutineScope if (restartable) {
                null
            } else {
                CommandResult(
                    exitCode = -1,
                    output = SHELL_UNRESPONSIVE_NOTE,
                    durationMs = System.currentTimeMillis() - started
                )
            }
        }

        // The shell can vanish without the stream saying so: the PTY is closed underneath us, or
        // the process is reaped somewhere else. Nothing else would end the wait, so poll liveness.
        // This is not a command timeout — a live command is never interrupted, however long it runs.
        val watchdog = launch(Dispatchers.IO) {
            while (isActive) {
                delay(LIVENESS_POLL_MS)
                if (!runtime.isSessionAlive(session.id)) {
                    exit.complete(EXIT_SESSION_LOST)
                    return@launch
                }
            }
        }

        // No timeout, on purpose: the command runs until it exits. The only thing that ends it early
        // is the caller being cancelled — the user stopping the turn — and that kills the process
        // group deliberately. A command that needs a bound carries its own (`timeout 300 …`), which
        // is the only place that knows how long this particular work should take.
        val reported: Int?
        try {
            reported = exit.await()
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                runCatching { runtime.requestStop(session.id) }
                // Stop must leave nothing behind. A shell that survived the signal is still busy
                // running the stopped command, so the next script would be typed into its stdin,
                // swallowed by that command, and the next run would wait forever. Closing the
                // session also escalates to SIGKILL when SIGTERM is ignored.
                runCatching { runtime.closeSession(session.id) }
                tracker.unbind(request.workspaceId, session.id)
            }
            throw cancel
        } finally {
            watchdog.cancel()
            collector.cancel()
            busySessions.remove(session.id)
        }
        // A run whose transport died is reported as a killed command, not as an exit status: -1001
        // and -1002 are internal markers, and handing them to the model would read as a real code.
        val lostNote = when (reported) {
            EXIT_STREAM_LOST -> STREAM_LOST_NOTE
            EXIT_SESSION_LOST -> SESSION_GONE_NOTE
            else -> null
        }
        val exitCode = if (lostNote != null) -1 else reported

        // Remember where the shell ended up and what it ran, so the next command (and the
        // terminal_status tool) build on this one instead of starting over.
        if (exitCode == -1) {
            // The shell died, or the run lost its transport: drop the binding so the next run opens
            // a healthy session instead of writing into a dead one.
            tracker.unbind(request.workspaceId, session.id)
        } else {
            tracker.bind(request.workspaceId, session.id, cwd)
        }
        tracker.record(
            request.workspaceId,
            TerminalCommandRecord(
                command = request.command,
                cwd = cwd,
                exitCode = exitCode,
                durationMs = System.currentTimeMillis() - started,
                at = System.currentTimeMillis()
            )
        )

        val body = cleanOutput(buffer.text(), marker)
        CommandResult(
            exitCode = exitCode,
            output = if (lostNote == null) body else listOf(body, lostNote).filter { it.isNotBlank() }
                .joinToString("\n\n"),
            truncated = buffer.truncated,
            durationMs = System.currentTimeMillis() - started
        )
    }

    /**
     * The shell this workspace should use. Prefers the session the agent already holds, then any live
     * session already sitting in [cwd], and only opens a new one when neither exists.
     */
    private suspend fun resolveSession(workspaceId: String, cwd: String): TerminalSession {
        // A shell opened before the Linux environment was installed must not be reused for guest
        // commands, so the tracked session only counts when it is backed by the preferred backend.
        val kind = runCatching { runtime.preferredSessionKind() }
            .getOrDefault(SessionKind.DEVICE_SHELL)
        val live = runCatching { runtime.listSessions() }.getOrDefault(emptyList())
        val trackedId = tracker.sessionId(workspaceId)
        if (trackedId != null) {
            val tracked = live.firstOrNull { it.id == trackedId }
            // A tracked session that is no longer alive is worse than no session at all: writes
            // to it succeed silently, nothing is ever printed, and the run waits for a sentinel
            // that cannot come. This is exactly what a Stop leaves behind.
            if (tracked != null && tracked.kind == kind && runtime.isSessionAlive(tracked.id) &&
                tracked.id !in busySessions &&
                !devServers.isOwned(tracked.id) && !backgroundJobs.isOwned(tracked.id)
            ) {
                return tracked
            }
            tracker.unbind(workspaceId, trackedId)
        }
        // Never reuse a session that belongs to a running server: sending this command to it would
        // both corrupt the server's terminal and, on timeout, kill the server with the process group.
        // Same rule for a background job: its shell is busy running one command, and typing the
        // next one into it would interleave two outputs and corrupt both.
        val reusable = runtime.findSessionByCwd(cwd, kind)
            ?.takeIf {
                runtime.isSessionAlive(it.id) && it.id !in busySessions &&
                    !devServers.isOwned(it.id) && !backgroundJobs.isOwned(it.id)
            }
        val session = reusable ?: runtime.createSession(ExecutionWorkspace(workspaceId, cwd))
        tracker.bind(workspaceId, session.id, cwd)
        return session
    }

    /** Single-quotes a path for the guest shell. */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun markerRegex(marker: String) = Regex(Regex.escape(marker) + """\s+(-?\d+)""")

    /** Emits [text] to [sink], dropping any sentinel that leaked into the live stream. */
    private fun emitLive(sink: (String) -> Unit, markerPattern: Regex, text: String) {
        if (text.isEmpty()) return
        var cleaned = markerPattern.replace(text, "")
        // The sentinel is printed in one write, but the pty may hand it over in two reads. A
        // half-sentinel would otherwise flash in the live view as if it were output.
        val partial = cleaned.indexOf(SENTINEL_HEAD)
        if (partial >= 0) cleaned = cleaned.substring(0, partial)
        if (cleaned.isNotEmpty()) sink(cleaned)
    }

    /**
     * Everything from the sentinel's own line onwards is plumbing (the printf, its echo and the exit
     * status), never command output. Cutting at the start of that line also removes the prompt the
     * shell reprinted just before it. The echo before it was already dropped by [OutputGate].
     */
    private fun cleanOutput(text: String, marker: String): String {
        val withoutEscape = stripAnsi(text)
        val markerAt = withoutEscape.indexOf(marker)
        val body = if (markerAt >= 0) {
            val lineStart = withoutEscape.lastIndexOf('\n', markerAt)
            withoutEscape.substring(0, if (lineStart < 0) 0 else lineStart)
        } else {
            markerRegex(marker).replace(withoutEscape, "")
        }
        return body.trim('\n', ' ', '\r')
    }

    /** Drops CSI/OSC escape sequences and the carriage returns a pty inserts, leaving plain text. */
    private fun stripAnsi(text: String): String = TtyText.stripAnsi(text)

    private companion object {
        /** How much recent output is kept for sentinel scanning; the sentinel is ~40 chars. */
        const val MARKER_TAIL = 512

        /** First characters of every sentinel; a partial one is never command output. */
        const val SENTINEL_HEAD = "__KERN_"

        /** The shell variable prefix the script uses to build its marker without echoing it. */
        const val DOLLAR = "$"

        /**
         * Base64 characters per line. Small enough that the tty line buffer (4096 bytes) can never
         * truncate one, large enough that a long script is a handful of lines.
         */
        const val BASE64_CHUNK_CHARS = 2_000

        /**
         * How long the shell gets to print the start marker. It is the first thing the script does,
         * so a shell that has not printed it is not running the script — it is still busy with an
         * earlier command, and everything written to it is being swallowed.
         */
        const val START_HANDSHAKE_MS = 8_000L

        const val SHELL_UNRESPONSIVE_NOTE = "[kern] The workspace shell did not accept the " +
            "command twice in a row, so it was not run. Open the terminal and press Ctrl-C to " +
            "get the shell back, then try again."

        /** Printed (as output, so the agent sees it) when the workspace directory is unreachable. */
        const val CWD_WARNING = """[kern] could not enter the workspace directory; running in $(pwd)"""

        /** Internal marker: the output stream ended before the command reported an exit status. */
        const val EXIT_STREAM_LOST = -1001

        /** Internal marker: the shell was gone before the command reported an exit status. */
        const val EXIT_SESSION_LOST = -1002

        /**
         * How often the wait checks that the shell still exists. This is a liveness check, not a
         * command timeout: a live command is never interrupted, however long it runs.
         */
        const val LIVENESS_POLL_MS = 1_000L

        const val STREAM_LOST_NOTE = "[kern] The shell stopped reporting before the command " +
            "printed an exit status, so the run was ended instead of waiting forever. Check the " +
            "terminal to see what it is actually doing."

        const val SESSION_GONE_NOTE = "[kern] The shell running this command exited before the " +
            "command printed an exit status."

        const val SESSION_UNREACHABLE_NOTE = "[kern] The shell did not accept the command, so " +
            "nothing was run. Try again."
    }
}
