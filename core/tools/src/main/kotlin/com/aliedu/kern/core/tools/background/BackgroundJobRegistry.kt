package com.aliedu.kern.core.tools.background

import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks the finite commands the agent started in the background.
 *
 * Declared next to [com.aliedu.kern.core.tools.CommandExecutor] — like
 * [com.aliedu.kern.core.tools.devserver.DevServerRegistry] — because the implementation lives in the
 * terminal layer and the tools must not depend on it.
 */
interface BackgroundJobRegistry {

    /** Every job started this process, newest last. Terminal jobs are retained for their output. */
    val jobs: StateFlow<List<BackgroundJob>>

    /**
     * Launch [command] in its own session and return immediately. The job keeps running while the
     * agent does other work; its state, output and exit code stay readable through this registry.
     *
     * @param maxOutputChars how much output to retain; the rest is counted but dropped.
     */
    suspend fun start(
        command: String,
        workspaceId: String,
        cwd: String,
        maxOutputChars: Int = com.aliedu.kern.core.tools.CommandRequest.BACKGROUND_MAX_OUTPUT_CHARS
    ): Result<BackgroundJob>

    /** The job with [id], or null when no such job was started in this process. */
    fun status(id: String): BackgroundJob?

    /**
     * Output from character [cursor] onwards. The first call passes 0; the returned
     * [BackgroundOutput.nextCursor] is what the next call passes, so a chatty install is read in
     * slices instead of being re-sent whole.
     */
    fun output(id: String, cursor: Int = 0): BackgroundOutput?

    /**
     * Waits up to [timeoutMs] for [id] to finish and returns the job either way — check
     * [BackgroundJob.running] to tell "finished" from "still going". Null means unknown id.
     */
    suspend fun await(id: String, timeoutMs: Long): BackgroundJob?

    /** Sends an interrupt, then closes the session. Returns false when the job was already gone. */
    suspend fun stop(id: String): Boolean

    /**
     * True when [sessionId] belongs to a job. The command executor must skip such sessions when
     * reusing a shell, or the next `run_command` would be typed into a running job.
     */
    fun isOwned(sessionId: String): Boolean
}
