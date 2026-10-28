package com.aliedu.kern.core.terminal.implementation.runtime

import com.aliedu.kern.core.terminal.api.ExecutionWorkspace
import com.aliedu.kern.core.terminal.api.SessionKind
import com.aliedu.kern.core.terminal.api.TerminalOutput
import com.aliedu.kern.core.terminal.api.TerminalRuntime
import com.aliedu.kern.core.terminal.api.TerminalSession
import com.aliedu.kern.core.tools.CommandExecutor
import com.aliedu.kern.core.tools.CommandRequest
import com.aliedu.kern.core.tools.CommandResult
import com.aliedu.kern.core.tools.TerminalCommandRecord
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
import kotlinx.coroutines.withTimeoutOrNull
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
        val command = "cd -- " + shellQuote(cwd) + " 2>/dev/null || true; " + request.command

        val marker = "__KERN_DONE_" + UUID.randomUUID().toString().replace("-", "") + "__"
        val script = command + "\nprintf '\\n" + marker + " %s\\n' \"${'$'}?\"\n"
        val cap = request.maxOutputBytes
        val buffer = StringBuilder()
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
        var truncated = false
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
                    if (buffer.length < cap) {
                        val remaining = cap - buffer.length
                        if (text.length <= remaining) buffer.append(text) else {
                            buffer.append(text, 0, remaining)
                            truncated = true
                        }
                    } else {
                        truncated = true
                    }

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

        var timedOut = false
        val exitCode: Int?
        try {
            exitCode = withTimeoutOrNull(request.timeoutMs) { exit.await() }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { runCatching { runtime.requestStop(session.id) } }
            throw cancel
        } finally {
            collector.cancel()
        }

        if (exitCode == null) {
            timedOut = true
            runCatching { runtime.requestStop(session.id) }
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
            output = cleanOutput(buffer.toString(), script, marker),
            truncated = truncated,
            timedOut = timedOut,
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
            if (tracked != null && tracked.kind == kind && !devServers.isOwned(tracked.id)) {
                return tracked
            }
            tracker.unbind(workspaceId, trackedId)
        }
        // Never reuse a session that belongs to a running server: sending this command to it would
        // both corrupt the server's terminal and, on timeout, kill the server with the process group.
        val reusable = runtime.findSessionByCwd(cwd, kind)?.takeIf { !devServers.isOwned(it.id) }
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
    private fun stripAnsi(text: String): String =
        text.replace(ANSI_OSC, "")
            .replace(ANSI_CSI, "")
            .replace(ANSI_TWO_CHAR, "")
            .replace("\r", "")

    /**
     * Index just past the tty's echo of [script] in [text], or -1 if it is not present near the head.
     * The tty may render `\n` as `\r\n` and wrap long lines, so we match the script character by
     * character while ignoring carriage returns and newlines.
     */
    private fun findEchoEnd(text: String, script: String): Int {
        val limit = (script.length * 2 + 64).coerceAtMost(text.length)
        for (start in 0..limit) {
            var i = start
            var j = 0
            while (i < text.length && j < script.length) {
                val expected = script[j]
                if (expected == '\n') {
                    j++
                    continue
                }
                val actual = text[i]
                if (actual == '\r' || actual == '\n') {
                    i++
                    continue
                }
                if (actual != expected) break
                i++
                j++
            }
            if (j == script.length) return i
        }
        return -1
    }

    private companion object {
        /** How much recent output is kept for sentinel scanning; the sentinel is ~40 chars. */
        const val MARKER_TAIL = 512

        /** OSC: a title or hyperlink, terminated by BEL or ST. */
        val ANSI_OSC = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")

        /** CSI: colours, cursor moves, bracketed-paste markers. */
        val ANSI_CSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

        /** Two-character escapes such as the charset switch. */
        val ANSI_TWO_CHAR = Regex("\u001B[@-Z\\\\-_]")
    }
}
