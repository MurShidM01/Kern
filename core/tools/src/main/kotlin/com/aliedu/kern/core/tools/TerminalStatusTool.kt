package com.aliedu.kern.core.tools

import com.aliedu.kern.core.providers.api.ToolSchema
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** One command the agent ran in its terminal, as recorded for [TerminalInspector]. */
data class TerminalCommandRecord(
    val command: String,
    val cwd: String,
    val exitCode: Int?,
    val durationMs: Long,
    val at: Long
)

/** What the agent's terminal looks like right now. */
data class TerminalStatus(
    val sessionId: String?,
    val sessionName: String?,
    val cwd: String?,
    val liveSessions: Int,
    val backend: String,
    val recent: List<TerminalCommandRecord>
)

/**
 * Read-only window onto the terminal the agent drives. Implemented by the terminal layer, which
 * owns the sessions; the tool layer only formats it, so the agent can see which shell it is using,
 * where it is, and what it has already run.
 */
interface TerminalInspector {
    suspend fun status(workspaceId: String): TerminalStatus
}

/**
 * Lets the agent look at its own terminal instead of guessing: which session it holds, its working
 * directory, and the commands it has already run with their exit codes.
 */
@Singleton
class TerminalStatusTool @Inject constructor(
    private val inspector: TerminalInspector
) : Tool {

    override val name = "terminal_status"

    override val description =
        "Show the terminal the agent is using: the session, its working directory, and the commands " +
            "already run with their exit codes. Use it before re-running something or when a command " +
            "failed, so you build on what actually happened."

    override val isMutating = false

    override val isReadOnly = true

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """{"type":"object","properties":{}}"""
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val status = inspector.status(context.workspace.id)
        return ToolResult(success = true, output = render(status))
    }

    private fun render(status: TerminalStatus): String = buildString {
        appendLine("Backend: ${status.backend}")
        appendLine("Live sessions: ${status.liveSessions}")
        val id = status.sessionId
        if (id == null) {
            appendLine("Agent session: none yet (the next run_command opens one)")
        } else {
            val name = status.sessionName?.let { "\"$it\" " }.orEmpty()
            appendLine("Agent session: $name($id)")
            appendLine("Working directory: ${status.cwd ?: "unknown"}")
        }
        if (status.recent.isEmpty()) {
            appendLine("Recent commands: none")
        } else {
            appendLine("Recent commands (newest last):")
            status.recent.forEach { record ->
                val exit = record.exitCode?.let { "exit $it" } ?: "no exit code"
                appendLine("  - ${record.command.oneLine()} · $exit · ${record.durationMs} ms")
            }
        }
    }

    private fun String.oneLine(): String = trim().replace(Regex("\\s+"), " ").take(160)
}
