package com.aliedu.kern.core.tools.background

import com.aliedu.kern.core.providers.api.ToolSchema
import com.aliedu.kern.core.tools.CommandClassifier
import com.aliedu.kern.core.tools.CommandRequest
import com.aliedu.kern.core.tools.OutputPolicy
import com.aliedu.kern.core.tools.Tool
import com.aliedu.kern.core.tools.ToolContext
import com.aliedu.kern.core.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import javax.inject.Inject
import javax.inject.Singleton

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/** How much of a finished job's output is handed back by `background_wait`. */
private const val WAIT_TAIL_CHARS = 4_000

/** How many characters one `background_output` slice may return. */
private const val OUTPUT_SLICE_CHARS = 8_000

/**
 * Starts a finite command in the background (PRD §4.3 bullet 8).
 *
 * Like [com.aliedu.kern.core.tools.RunCommandTool] this only *proposes*: it returns a
 * [CommandRequest] flagged `background`, and the orchestrator applies the usual approval rules.
 * The difference is what happens after approval — the executor starts a job and returns at once
 * with its id instead of blocking the turn until the command exits, so `npm install` no longer has
 * to fit inside a foreground timeout.
 */
@Singleton
class BackgroundStartTool @Inject constructor() : Tool {
    override val name = "background_start"
    override val description =
        "Start a command that takes minutes (installs, dependency downloads, cold builds) as a " +
            "background job and return its id immediately, so you can keep working. Read its " +
            "output with background_output, wait for it with background_wait, or stop it with " +
            "background_stop. The user approves it first. Use run_command instead for anything " +
            "that finishes quickly, and server_start for anything that never exits."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{
              "command":{"type":"string","description":"The shell command to run, e.g. 'apt-get install -y openjdk-17-jdk'."},
              "reason":{"type":"string","description":"A short explanation shown to the user."}
            },"required":["command"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val command = args.string("command")?.trim()
        if (command.isNullOrEmpty()) {
            return ToolResult(false, "", "Missing required argument 'command'.")
        }
        val request = CommandRequest(
            workspaceId = context.workspace.id,
            command = command,
            cwd = context.workspace.localPath.orEmpty(),
            impact = CommandClassifier.classify(command),
            outputPolicy = OutputPolicy.CAPTURE,
            reason = args.string("reason"),
            background = true
        )
        return ToolResult(
            success = true,
            output = "Proposed background job for approval: $command",
            commandRequest = request,
            requiresApproval = true
        )
    }
}

/** Reports one job, or every job when no id is given. */
@Singleton
class BackgroundStatusTool @Inject constructor(
    private val registry: BackgroundJobRegistry
) : Tool {
    override val name = "background_status"
    override val description =
        "Check background jobs started with background_start: their state (running, succeeded, " +
            "failed, stopped), exit code, how long they ran and how much output they produced. " +
            "Call it before assuming a job is still running."
    override val isMutating = false
    override val isReadOnly = true

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{
              "id":{"type":"string","description":"The job id from background_start. Omit to list every job."}
            },"required":[]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val id = args.string("id")?.trim()
        if (!id.isNullOrEmpty()) {
            val job = registry.status(id)
                ?: return ToolResult(false, "", "No background job with id '$id'.")
            return ToolResult(true, describe(job))
        }
        val jobs = registry.jobs.value
        if (jobs.isEmpty()) return ToolResult(true, "No background jobs have been started.")
        return ToolResult(true, jobs.joinToString("\n") { job ->
            "${job.id}  ${job.state.name.lowercase()}  exit=${job.exitCode ?: "-"}  " +
                "${job.durationMs / 1_000}s  ${job.command}"
        })
    }

    private fun describe(job: BackgroundJob): String = buildString {
        appendLine("Job ${job.id}: ${job.state.name.lowercase()}")
        appendLine("Command: ${job.command}")
        appendLine("Directory: ${job.cwd}")
        appendLine("Exit code: ${job.exitCode ?: "not finished"}")
        appendLine("Ran for: ${job.durationMs / 1_000}s")
        appendLine("Output: ${job.outputChars} chars${if (job.truncated) " (truncated)" else ""}")
    }.trimEnd()
}

/** Reads a job's output, optionally only what arrived since a cursor. */
@Singleton
class BackgroundOutputTool @Inject constructor(
    private val registry: BackgroundJobRegistry
) : Tool {
    override val name = "background_output"
    override val description =
        "Read the output a background job has produced so far. Pass the cursor from the previous " +
            "call to get only what is new. The job keeps running while you read it."
    override val isMutating = false
    override val isReadOnly = true

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{
              "id":{"type":"string","description":"The job id from background_start."},
              "cursor":{"type":"integer","description":"Character offset to read from; omit for the start."}
            },"required":["id"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val id = args.string("id")?.trim()
        if (id.isNullOrEmpty()) return ToolResult(false, "", "Missing required argument 'id'.")
        val cursor = (args.int("cursor") ?: 0).coerceAtLeast(0)
        val slice = registry.output(id, cursor)
            ?: return ToolResult(false, "", "No background job with id '$id'.")
        if (slice.text.isEmpty()) {
            return ToolResult(
                true,
                "No new output (job is ${slice.state.name.lowercase()}, cursor ${slice.nextCursor})."
            )
        }
        val bounded = if (slice.text.length <= OUTPUT_SLICE_CHARS) {
            slice.text
        } else {
            slice.text.take(OUTPUT_SLICE_CHARS)
        }
        return ToolResult(
            true,
            "$bounded\n[state=${slice.state.name.lowercase()} cursor=${cursor + bounded.length} " +
                "of ${slice.totalChars} chars${if (slice.truncated) ", older output dropped" else ""}]"
        )
    }
}

/** Blocks until the job finishes or the wait budget runs out. */
@Singleton
class BackgroundWaitTool @Inject constructor(
    private val registry: BackgroundJobRegistry
) : Tool {
    override val name = "background_wait"
    override val description =
        "Wait for a background job to finish and get its exit code and the end of its output. " +
            "Returns as soon as the job is done, or when the wait budget runs out — if it is still " +
            "running you get the state back and can wait again or do other work."
    override val isMutating = false
    override val isReadOnly = true

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{
              "id":{"type":"string","description":"The job id from background_start."},
              "timeout_seconds":{"type":"integer","description":"Longest time to wait (default 120, max 600)."}
            },"required":["id"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val id = args.string("id")?.trim()
        if (id.isNullOrEmpty()) return ToolResult(false, "", "Missing required argument 'id'.")
        // This bounds the *wait*, not the job: the job keeps running and the tool just returns so
        // the model can do something else instead of holding the turn open.
        val timeoutMs = (args.int("timeout_seconds")?.toLong()?.times(1_000))
            ?.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
            ?: DEFAULT_WAIT_MS
        val job = registry.await(id, timeoutMs)
            ?: return ToolResult(false, "", "No background job with id '$id'.")
        val tail = job.output.takeLast(WAIT_TAIL_CHARS)
        val header = when {
            job.running -> "Job ${job.id} is still running after ${timeoutMs / 1_000}s " +
                "(${job.durationMs / 1_000}s so far). Call background_wait again to keep waiting, " +
                "or do other work first."
            else -> "Job ${job.id} finished: ${job.state.name.lowercase()}, " +
                "exit code ${job.exitCode ?: "unknown"}, ${job.durationMs / 1_000}s."
        }
        return ToolResult(
            success = !job.running && job.state == BackgroundJobState.SUCCEEDED,
            output = if (tail.isBlank()) header else "$header\n\n$tail",
            errorMessage = if (job.state == BackgroundJobState.FAILED) {
                "The job failed with exit code ${job.exitCode ?: "unknown"}."
            } else {
                null
            }
        )
    }

    private companion object {
        /** Two minutes is long enough for most installs, short enough to keep the turn responsive. */
        const val DEFAULT_WAIT_MS = 120_000L
        const val MIN_WAIT_MS = 5_000L
        const val MAX_WAIT_MS = 600_000L
    }
}

/**
 * Stops a job the agent started. Not approval-gated: it can only affect a process this app
 * launched, and leaving a stray install running is worse than the small blast radius.
 */
@Singleton
class BackgroundStopTool @Inject constructor(
    private val registry: BackgroundJobRegistry
) : Tool {
    override val name = "background_stop"
    override val description =
        "Stop a running background job (interrupt, then close its session). Use background_status " +
            "first to get its id and confirm it is still running."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{
              "id":{"type":"string","description":"The job id from background_start."}
            },"required":["id"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val id = args.string("id")?.trim()
        if (id.isNullOrEmpty()) return ToolResult(false, "", "Missing required argument 'id'.")
        return if (registry.stop(id)) {
            ToolResult(true, "Stopped background job $id.")
        } else {
            ToolResult(false, "", "No background job with id '$id'.")
        }
    }
}
