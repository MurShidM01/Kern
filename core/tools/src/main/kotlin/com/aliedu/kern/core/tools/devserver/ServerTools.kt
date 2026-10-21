package com.aliedu.kern.core.tools.devserver

import com.aliedu.kern.core.providers.api.ToolSchema
import com.aliedu.kern.core.tools.CommandClassifier
import com.aliedu.kern.core.tools.CommandRequest
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

/**
 * Starts a long-running server (PRD §4.3 bullet 8).
 *
 * Like [com.aliedu.kern.core.tools.RunCommandTool] this only *proposes*: it returns a
 * [CommandRequest] flagged `detach`, and the orchestrator applies the usual approval rules. The
 * difference is what happens after approval — the run is handed to the dev-server manager, which
 * waits only for the port to accept connections instead of waiting for the process to exit.
 */
@Singleton
class ServerStartTool @Inject constructor() : Tool {
    override val name = "server_start"
    override val description =
        "Start a long-running server (dev server, static file server, API) in the workspace and " +
            "keep it running in the background. Use this instead of run_command for anything that " +
            "does not exit on its own. Returns the local URL to preview."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{
              "command":{"type":"string","description":"The command that starts the server, e.g. 'python3 -m http.server 8000'."},
              "port":{"type":"integer","description":"The port it listens on. Omit only if the server prints its URL."},
              "reason":{"type":"string","description":"A short explanation shown to the user."}
            },"required":["command"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val command = args.string("command")?.trim()
        if (command.isNullOrEmpty()) {
            return ToolResult(false, "", "Missing required argument 'command'.")
        }
        val port = args.int("port")?.takeIf { it in 1..65_535 }
        val request = CommandRequest(
            workspaceId = context.workspace.id,
            command = command,
            cwd = context.workspace.localPath.orEmpty(),
            impact = CommandClassifier.classify(command),
            // The readiness budget, not a command timeout: a cold dev server can take a while to bind.
            timeoutMs = CommandRequest.DEV_SERVER_READY_TIMEOUT_MS,
            reason = args.string("reason"),
            detach = true,
            port = port
        )
        return ToolResult(
            success = true,
            output = "Proposed server for approval: $command",
            commandRequest = request,
            requiresApproval = true
        )
    }
}

/**
 * Stops a server the agent started. Not approval-gated: it can only affect a process this app
 * launched, and leaving stray servers running is worse than the small blast radius.
 */
@Singleton
class ServerStopTool @Inject constructor(
    private val registry: DevServerRegistry
) : Tool {
    override val name = "server_stop"
    override val description =
        "Stop a background server started with server_start, freeing its port and closing its " +
            "session. Use server_list (preview_status) to get the id."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{
              "id":{"type":"string","description":"The id returned by server_start."}
            },"required":["id"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val id = args.string("id")?.trim()
        if (id.isNullOrEmpty()) {
            return ToolResult(false, "", "Missing required argument 'id'.")
        }
        val stopped = registry.stop(id)
        return if (stopped) {
            ToolResult(true, "Stopped server $id.")
        } else {
            ToolResult(false, "", "No running server with id '$id'.")
        }
    }
}
