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
        "Propose a shell command to run in the active workspace (built-in shell) and wait for it to " +
            "exit. The user must approve it before it runs. Use for builds, tests, git, and other " +
            "quick CLI tasks; use background_start for anything that may take minutes (installs, " +
            "dependency downloads, cold builds) so you can keep working while it runs. There is no " +
            "timeout: the command runs until it exits. If it might hang, bound it yourself, e.g. " +
            "`timeout 300 apt-get install -y …`."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{
              "command":{"type":"string","description":"The shell command to run."},
              "reason":{"type":"string","description":"A short explanation shown to the user."},
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
        val impact = CommandClassifier.classify(command)
        val outputPolicy = if (args.string("output_policy") == "stream") {
            OutputPolicy.STREAM
        } else {
            OutputPolicy.CAPTURE
        }

        val request = CommandRequest(
            workspaceId = context.workspace.id,
            command = command,
            cwd = context.workspace.localPath.orEmpty(),
            impact = impact,
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
