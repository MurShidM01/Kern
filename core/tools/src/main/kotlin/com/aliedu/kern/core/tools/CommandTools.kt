package com.aliedu.kern.core.tools

import com.aliedu.kern.core.providers.api.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import javax.inject.Inject
import javax.inject.Singleton

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

/**
 * Proposes a shell command for the user to approve (PRD FR-017). The tool never runs anything — it
 * classifies the command's impact and returns a [CommandRequest]; the orchestrator executes it only
 * after approval (and never auto-runs a DESTRUCTIVE command).
 */
@Singleton
class RunCommandTool @Inject constructor() : Tool {
    override val name = "run_command"
    override val description =
        "Propose a shell command to run in the active workspace (built-in shell). The user must " +
            "approve it before it runs. Use for builds, tests, git, and other CLI tasks."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{
              "command":{"type":"string","description":"The shell command to run."},
              "reason":{"type":"string","description":"A short explanation shown to the user."},
              "timeout_seconds":{"type":"integer","description":"Stop after this many seconds (default 30, max 120)."},
              "output_policy":{"type":"string","enum":["capture","stream"],"description":"capture returns output to you; stream shows it to the user only."}
            },"required":["command"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val command = args.string("command")?.trim()
        if (command.isNullOrEmpty()) {
            return ToolResult(false, "", "Missing required argument 'command'.")
        }
        val reason = args.string("reason")
        val timeoutMs = (args.int("timeout_seconds")?.toLong()?.times(1000))
            ?.coerceIn(1_000, CommandRequest.MAX_TIMEOUT_MS)
            ?: CommandRequest.DEFAULT_TIMEOUT_MS
        val outputPolicy = if (args.string("output_policy") == "stream") {
            OutputPolicy.STREAM
        } else {
            OutputPolicy.CAPTURE
        }

        val request = CommandRequest(
            workspaceId = context.workspace.id,
            command = command,
            cwd = context.workspace.localPath.orEmpty(),
            impact = CommandClassifier.classify(command),
            timeoutMs = timeoutMs,
            outputPolicy = outputPolicy,
            reason = reason
        )

        return ToolResult(
            success = true,
            output = "Proposed command for approval: $command",
            commandRequest = request,
            requiresApproval = true
        )
    }
}
