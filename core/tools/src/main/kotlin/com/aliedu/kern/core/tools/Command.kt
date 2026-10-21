package com.aliedu.kern.core.tools

/**
 * How dangerous a shell command is (PRD FR-017). Drives approval: [DESTRUCTIVE] commands are *never*
 * auto-run, even in permissive modes; [READ_ONLY] commands still require approval under ASK_CHANGES.
 */
enum class CommandImpact { READ_ONLY, MUTATING, DESTRUCTIVE, NETWORK }

/** How the captured output is used. CAPTURE feeds it back to the model; STREAM shows it live only. */
enum class OutputPolicy { CAPTURE, STREAM }

/** A proposed shell command awaiting approval/execution (PRD FR-017). */
data class CommandRequest(
    val workspaceId: String,
    val command: String,
    /** Absolute execution directory; empty means "resolve from the workspace". */
    val cwd: String = "",
    val impact: CommandImpact,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val outputPolicy: OutputPolicy = OutputPolicy.CAPTURE,
    val reason: String? = null,
    /**
     * How much output to retain in [CommandResult.output]. Commands that legitimately print a lot
     * (e.g. listing every apt package) raise this; the default bounds memory for everything else.
     */
    val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    /**
     * True for build/test/lint runs, whose output is mostly noise. The orchestrator then feeds the
     * model a head/signal/tail summary instead of the raw log (PRD §4.3 bullet 4).
     */
    val summarize: Boolean = false,
    /**
     * Forces a confirmation even in permissive permission modes. Used for actions the user must
     * always see, such as a Git commit (PRD §4.3 bullet 3), without mislabelling them DESTRUCTIVE.
     */
    val alwaysApprove: Boolean = false,
    /**
     * Run this as a long-lived background process instead of waiting for it to exit. A dev server
     * never returns to the prompt, so the normal sentinel/exit-code path would always time out and
     * then SIGKILL it. Detached runs are handed to the dev-server manager, which waits only for the
     * port to accept connections.
     */
    val detach: Boolean = false,
    /** The port a detached server is expected to listen on; null means "discover it". */
    val port: Int? = null
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 60_000

        /**
         * A Gradle build, an `apt install` or a cold `npm test` on a phone routinely runs past two
         * minutes. The old 120 s ceiling killed those commands mid-run, the model saw "timed out",
         * retried, and the task stalled; ten minutes is long enough for real work and still bounds
         * a hung process.
         */
        const val MAX_TIMEOUT_MS: Long = 600_000
        const val DEFAULT_MAX_OUTPUT_BYTES: Int = 64 * 1024

        /**
         * How long a detached server may take to accept connections before it is reported as failed.
         * Deliberately its own budget: a cold `npm run dev` can exceed [DEFAULT_TIMEOUT_MS], and this
         * is a readiness wait, not a command timeout.
         */
        const val DEV_SERVER_READY_TIMEOUT_MS: Long = 30_000
    }
}

/** Bounded outcome of running a command (PRD FR-017). */
data class CommandResult(
    val exitCode: Int?,
    val output: String,
    val truncated: Boolean = false,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val durationMs: Long = 0
) {
    val succeeded: Boolean get() = exitCode == 0 && !timedOut && !cancelled
}

/** Runs approved commands in the execution workspace (implemented by the terminal layer). */
interface CommandExecutor {
    /**
     * Run [request] to completion and return its bounded output. Cancelling the caller's coroutine
     * stops the underlying process. [onOutput] receives incremental text when non-null.
     */
    suspend fun run(request: CommandRequest, onOutput: ((String) -> Unit)? = null): CommandResult
}

/**
 * Classifies a command string by impact using a conservative token/regex heuristic. This is a safety
 * signal only — it never executes anything and errs toward a higher impact when unsure.
 */
object CommandClassifier {

    private val DESTRUCTIVE = listOf(
        Regex("""\brm\s+-[a-z]*r"""),                       // rm -r / -rf / -fr
        Regex("""\brm\s+-[a-z]*f[a-z]*\s+/(\s|$)"""),        // rm -f /
        Regex("""\bsudo\s+rm\b"""),
        Regex("""\bmkfs\b"""),
        Regex("""\bdd\b.*\bof="""),
        Regex("""\b(shutdown|reboot|halt|poweroff)\b"""),
        Regex("""\bgit\s+reset\s+--hard\b"""),
        Regex("""\bgit\s+clean\s+-[a-z]*f"""),
        Regex("""\bchmod\s+-R\s+777\s+/(\s|$)"""),
        Regex("""\bchown\s+-R\b"""),
        Regex("""\btruncate\s+-s\s*0\b"""),
        Regex("""\bkill(all)?\s+-9\b"""),
        Regex("""\b:\(\)\s*\{"""),                           // fork bomb
        Regex("""\bmv\s+[^|&;]*\s+/(\s|$)"""),
        Regex("""\b(drop|truncate)\s+table\b"""),
        Regex("""\bdelete\s+from\b"""),
        Regex(""">\s*/dev/(sd|block|mmcblk|nvme)""")
    )

    private val NETWORK = listOf(
        Regex("""\b(curl|wget|nc|ncat|netcat|ssh|scp|sftp|telnet|ping|traceroute|dig|nslookup)\b"""),
        Regex("""\bgit\s+(clone|push|pull|fetch|remote)\b"""),
        Regex("""\b(npm|pnpm|yarn)\s+(install|add|publish|create)\b"""),
        Regex("""\b(pip|pip3|pipenv|poetry)\s+(install|download)\b"""),
        Regex("""\b(apt|apt-get|apk|yum|dnf|pacman|brew)\s+(install|update|upgrade|add)\b"""),
        Regex("""\bdocker\s+(pull|push)\b"""),
        Regex("""\bgo\s+(get|install)\b"""),
        Regex("""\bcargo\s+(install|add)\b""")
    )

    private val MUTATING = listOf(
        Regex("""\b(mkdir|rmdir|touch|mv|cp|rm|chmod|chown|ln|tee|truncate|rename|dd|install)\b"""),
        Regex("""\bsed\s+-i\b"""),
        Regex("""\bgit\s+(add|commit|checkout|switch|merge|rebase|stash|apply|cherry-pick|tag|branch|init)\b"""),
        Regex("""(^|\s|\d)>{1,2}\s*[^\s&|]""")               // output redirection writes a file
    )

    fun classify(command: String): CommandImpact {
        val c = command.trim().lowercase()
        if (c.isEmpty()) return CommandImpact.READ_ONLY
        if (DESTRUCTIVE.any { it.containsMatchIn(c) }) return CommandImpact.DESTRUCTIVE
        if (NETWORK.any { it.containsMatchIn(c) }) return CommandImpact.NETWORK
        if (MUTATING.any { it.containsMatchIn(c) }) return CommandImpact.MUTATING
        return CommandImpact.READ_ONLY
    }
}
