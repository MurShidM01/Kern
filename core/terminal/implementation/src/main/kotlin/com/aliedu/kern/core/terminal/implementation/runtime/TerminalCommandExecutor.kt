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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs an approved command in the workspace's execution directory (PRD FR-017).
 *
 * Reuses a live session at the same working directory when one exists, otherwise starts a new one.
 * Output is captured up to [MAX_OUTPUT_BYTES] and the run is bounded by the request timeout; the
 * command's exit code is recovered with a unique sentinel echoed after it. Cancelling the caller
 * interrupts the process.
 */
@Singleton
class TerminalCommandExecutor @Inject constructor(
    private val runtime: TerminalRuntime,
    private val resolver: ExecutionWorkspaceResolver,
    private val devServers: DevServerRegistry,
    private val backgroundJobs: BackgroundJobRegistry,
    private val tracker: AgentTerminalTracker
) : CommandExecutor {

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
                        output = "Started background job undefined (undefined). It is still " +
                            "running; read it with background_output, wait for it with " +
                            "background_wait, or stop it with background_stop.",
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

    private suspend fun runToCompletion(
        request: CommandRequest,
        onOutput: ((String) -> Unit)?
    ): CommandResult = coroutineScope {
        val started = System.currentTimeMillis()
        val workspace = resolver.prepare(request.workspaceId)
        val cwd = request.cwd.takeIf { it.isNotBlank() } ?: workspace.workingDirectory
        val session = resolveSession(request.workspaceId, cwd)
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
        val command = "cd -- " + shellQuote(guestCwd ?: cwd) + " 2>/dev/null || echo " +
            shellQuote(CWD_WARNING) + "; " + request.command

        val marker = "__KERN_DONE_" + UUID.randomUUID().toString().replace("-", "") + "__"
        val script = command + "\nprintf '\\n" + marker + " %s\\n' \"${'$'}?\"\n"
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
        // The tty echoes what we type. Strip that echo from the *live* stream so callers that parse it
        // never see the command text or the sentinel as data.
        val head = StringBuilder()
        var echoPending = true
        val exit = CompletableDeferred<Int>()
        val subscribed = CompletableDeferred<Unit>()

        val collector = launch(Dispatchers.IO) {
            runtime.observeOutput(session.id)
                .onStart { subscribed.complete(Unit) }
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
                    if (capture) buffer.append(text)

                    tail.append(text)
                    if (tail.length > MARKER_TAIL) tail.delete(0, tail.length - MARKER_TAIL)
                    markerPattern.find(tail)?.let { match ->
                        if (!exit.isCompleted) exit.complete(match.groupValues[1].toIntOrNull() ?: 0)
                    }

                    val sink = onOutput ?: return@collect
                    if (echoPending) {
                        head.append(text)
                        val end = findEchoEnd(head.toString(), script)
                        if (end >= 0) {
                            echoPending = false
                            val rest = head.substring(end)
                            head.setLength(0)
                            emitLive(sink, markerPattern, rest)
                        } else if (head.length > script.length * 2 + 64) {
                            // Safety: never buffer unboundedly if the echo never matches.
                            echoPending = false
                            val rest = head.toString()
                            head.setLength(0)
                            emitLive(sink, markerPattern, rest)
                        }
                    } else {
                        emitLive(sink, markerPattern, text)
                    }
                }
        }

        // Ensure we are subscribed before sending, so the first chunk is never dropped.
        subscribed.await()
        runtime.sendInput(session.id, script.toByteArray(Charsets.UTF_8))

        // No timeout, on purpose: the command runs until it exits. The only thing that ends it early
        // is the caller being cancelled — the user stopping the turn — and that kills the process
        // group deliberately. A command that needs a bound carries its own (`timeout 300 …`), which
        // is the only place that knows how long this particular work should take.
        val exitCode: Int?
        try {
            exitCode = exit.await()
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { runCatching { runtime.requestStop(session.id) } }
            throw cancel
        } finally {
            collector.cancel()
        }

        // Remember where the shell ended up and what it ran, so the next command (and the
        // terminal_status tool) build on this one instead of starting over.
        if (exitCode == null || exitCode == -1) {
            // Timed out, or the shell itself exited: drop the binding so the next run opens a healthy
            // session instead of writing into a dead one.
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

        CommandResult(
            exitCode = exitCode,
            output = cleanOutput(buffer.text(), script, marker),
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
            if (tracked != null && tracked.kind == kind &&
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
            ?.takeIf { !devServers.isOwned(it.id) && !backgroundJobs.isOwned(it.id) }
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
        val cleaned = markerPattern.replace(text, "")
        if (cleaned.isNotEmpty()) sink(cleaned)
    }

    /**
     * The PTY echoes what we type, so the captured stream begins with our own script. Drop that echo
     * (and the sentinel line) so the agent sees only the command's real output.
     */
    private fun cleanOutput(text: String, script: String, marker: String): String {
        val withoutEscape = stripAnsi(text)
        val withoutEcho = stripEchoedScript(withoutEscape, script)
        // Everything from the sentinel's own line onwards is plumbing (the printf, its echo and the
        // exit status), never command output. Cutting at the start of that line also removes the
        // prompt the shell reprinted just before it.
        val markerAt = withoutEcho.indexOf(marker)
        val body = if (markerAt >= 0) {
            val lineStart = withoutEcho.lastIndexOf('\n', markerAt)
            withoutEcho.substring(0, if (lineStart < 0) 0 else lineStart)
        } else {
            markerRegex(marker).replace(withoutEcho, "")
        }
        return body.trim('\n', ' ', '\r')
    }

    /**
     * Removes the tty's echo of the command.
     *
     * The whole script cannot be matched: bracketed paste closes before the shell runs anything, so
     * the paste-end marker and the command's own output land *between* the echoed first and second
     * lines. Cutting after the first line is what the model actually needs, and it keeps the raw
     * escape sequences out of the tool result.
     */
    private fun stripEchoedScript(text: String, script: String): String {
        if (script.isEmpty()) return text
        val firstLine = script.substringBefore('\n')
        if (firstLine.isEmpty()) return text
        val end = findEchoEnd(text, firstLine)
        if (end < 0) return text
        val newline = text.indexOf('\n', end)
        return if (newline < 0) "" else text.substring(newline + 1)
    }

    /** Drops CSI/OSC escape sequences and the carriage returns a pty inserts, leaving plain text. */
    private fun stripAnsi(text: String): String = TtyText.stripAnsi(text)

    /** Index just past the tty's echo of [script] in [text]; see [TtyText.findEchoEnd]. */
    private fun findEchoEnd(text: String, script: String): Int = TtyText.findEchoEnd(text, script)

    private companion object {
        /** How much recent output is kept for sentinel scanning; the sentinel is ~40 chars. */
        const val MARKER_TAIL = 512

        /** Printed (as output, so the agent sees it) when the workspace directory is unreachable. */
        const val CWD_WARNING = """[kern] could not enter the workspace directory; running in $(pwd)"""
    }
}
