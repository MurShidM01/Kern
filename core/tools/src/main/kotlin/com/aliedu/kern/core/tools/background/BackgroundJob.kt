package com.aliedu.kern.core.tools.background

/**
 * Lifecycle of a finite command the agent started without waiting for it.
 *
 * A dev server is a different animal: it never exits, so [com.aliedu.kern.core.tools.devserver.DevServer]
 * tracks it by port readiness. A job is a command that *does* finish — `npm install`, `apt install
 * openjdk`, a cold `gradle build` — and the only thing missing was a way to wait for it after the
 * tool call returned.
 */
enum class BackgroundJobState {
    /** Started, still running. */
    RUNNING,

    /** Finished with exit code 0. */
    SUCCEEDED,

    /** Finished with a non-zero exit code, or its shell died without printing one. */
    FAILED,

    /** Stopped by the agent or the user. */
    STOPPED;

    val isTerminal: Boolean get() = this != RUNNING
}

/** One background job and everything known about it. */
data class BackgroundJob(
    val id: String,
    val command: String,
    val workspaceId: String,
    val cwd: String,
    val state: BackgroundJobState,
    val startedAt: Long,
    /** Null while the job runs. */
    val finishedAt: Long? = null,
    /** Null until the command prints an exit status. */
    val exitCode: Int? = null,
    /** The PTY session hosting it, so the executor can avoid reusing (and killing) it. */
    val sessionId: String? = null,
    /**
     * The head of the job's output, bounded by the cap it was started with. Long installs print
     * more than anyone wants to keep, and the interesting part of a failure is usually early plus
     * the very end; [outputChars] says how much was produced in total.
     */
    val output: String = "",
    val truncated: Boolean = false,
    /** Total characters the job produced, including the part that was dropped. */
    val outputChars: Int = 0
) {
    val running: Boolean get() = state == BackgroundJobState.RUNNING

    /** How long it ran, or has been running. */
    val durationMs: Long get() = (finishedAt ?: System.currentTimeMillis()) - startedAt
}

/** A slice of a job's output, from the cursor the caller passed in. */
data class BackgroundOutput(
    val jobId: String,
    val state: BackgroundJobState,
    val text: String,
    /** Pass this back as `cursor` to read only what arrived since. */
    val nextCursor: Int,
    val truncated: Boolean,
    val totalChars: Int
)
