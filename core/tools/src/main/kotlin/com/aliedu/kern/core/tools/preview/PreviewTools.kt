package com.aliedu.kern.core.tools.preview

import com.aliedu.kern.core.providers.api.ToolSchema
import com.aliedu.kern.core.tools.Tool
import com.aliedu.kern.core.tools.ToolContext
import com.aliedu.kern.core.tools.ToolResult
import com.aliedu.kern.core.tools.devserver.DevServerRegistry
import com.aliedu.kern.core.tools.devserver.DevServerState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import javax.inject.Inject
import javax.inject.Singleton

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** Shared formatting so every preview tool reports state the same way. */
private fun describeServers(registry: DevServerRegistry): String {
    val servers = registry.servers.value
    if (servers.isEmpty()) return "No servers are running."
    return servers.joinToString("\n") { server ->
        val port = server.port?.let { ":$it" } ?: "-"
        val extra = when (server.state) {
            DevServerState.RUNNING -> server.url.orEmpty()
            DevServerState.FAILED -> server.log.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
            else -> ""
        }
        "- id=${server.id}  ${server.state}  $port  ${server.command}  $extra"
    }
}

/**
 * Points the preview at a URL and asks the app to show it (PRD §4.3 bullet 8). Defaults to the first
 * running server, so the agent can simply say "open the preview".
 */
@Singleton
class PreviewOpenTool @Inject constructor(
    private val controller: PreviewController,
    private val registry: DevServerRegistry
) : Tool {
    override val name = "preview_open"
    override val description =
        "Show the web preview and point it at a URL. Defaults to the first running server, so call " +
            "it with no arguments to preview what server_start just launched."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{
              "url":{"type":"string","description":"The URL to show. Defaults to the first running server."},
              "reload":{"type":"boolean","description":"Reload the page if it is already open."}
            }}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val explicit = args.string("url")?.trim()?.takeIf { it.isNotEmpty() }
        val running = registry.servers.value.firstOrNull { it.state == DevServerState.RUNNING }
        val url = explicit ?: running?.url
            ?: return ToolResult(
                false, "",
                "No running server to preview. Start one with server_start first."
            )

        controller.open(url)
        controller.requestOpen()
        if (args.bool("reload") == true) controller.reload()
        val title = controller.state.value.title
        val suffix = title?.let { " (\"$it\")" } ?: ""
        return ToolResult(true, "Preview opened at $url$suffix.")
    }
}

/** Reports running servers plus what the preview is currently showing. */
@Singleton
class PreviewStatusTool @Inject constructor(
    private val controller: PreviewController,
    private val registry: DevServerRegistry
) : Tool {
    override val name = "preview_status"
    override val description =
        "List the servers you have started and what the preview is currently showing. Use it to " +
            "find a server id for server_stop, or to check the preview loaded."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """{"type":"object","properties":{}}"""
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val state = controller.state.value
        val errors = controller.console.value.count { it.level == ConsoleLevel.ERROR }
        val warnings = controller.console.value.count { it.level == ConsoleLevel.WARN }
        val previewLine = when {
            state.url == null -> "Preview: nothing loaded."
            !state.attached -> "Preview: ${state.url} (not on screen yet — the user must open it)."
            state.loading -> "Preview: ${state.url} (loading ${state.progress}%)."
            state.error != null -> "Preview: ${state.url} — failed: ${state.error}"
            else -> "Preview: ${state.url}${state.title?.let { " — \"$it\"" }.orEmpty()} (loaded)."
        }
        val logLine = "Console: $errors error(s), $warnings warning(s), " +
            "${controller.console.value.size} line(s) captured."
        return ToolResult(
            true,
            listOf(previewLine, logLine, "Servers:", describeServers(registry)).joinToString("\n")
        )
    }
}

/** Reads the captured browser console — the "F12" view the agent was missing. */
@Singleton
class PreviewConsoleTool @Inject constructor(
    private val controller: PreviewController
) : Tool {
    override val name = "preview_console"
    override val description =
        "Read the browser console of the preview: console.log/warn/error output and uncaught " +
            "JavaScript errors. Use it after loading a page to see what failed."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{
              "level":{"type":"string","enum":["log","info","warn","error","debug"],"description":"Only show this level."},
              "limit":{"type":"integer","description":"Return at most this many of the newest lines (default 50)."},
              "clear":{"type":"boolean","description":"Clear the buffer after reading."}
            }}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val level = args.string("level")?.trim()?.uppercase()?.let { name ->
            ConsoleLevel.entries.firstOrNull { it.name == name }
        }
        val limit = (args.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, PreviewController.MAX_ENTRIES)

        val all = controller.console.value
        val filtered = if (level == null) all else all.filter { it.level == level }
        val tail = filtered.takeLast(limit)

        if (args.bool("clear") == true) controller.clearConsole()

        if (tail.isEmpty()) {
            return ToolResult(
                true,
                if (all.isEmpty()) {
                    "The console is empty. Nothing has been logged since the preview opened."
                } else {
                    "No console lines at level ${level?.name?.lowercase()}. " +
                        "${all.size} line(s) at other levels."
                }
            )
        }

        val body = tail.joinToString("\n") { entry ->
            val where = entry.source?.let { source ->
                val file = source.substringAfterLast('/')
                " ($file:${entry.line})"
            }.orEmpty()
            "[${entry.level.name}]$where ${entry.message}"
        }
        val note = if (filtered.size > tail.size) {
            "\n[showing the newest ${tail.size} of ${filtered.size}]"
        } else {
            ""
        }
        return ToolResult(true, "$body$note")
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
    }
}

/**
 * Runs JavaScript in the previewed page. This is the agent's "full access": it can read the DOM,
 * click, fill inputs, or measure layout — everything a devtools console can do.
 */
@Singleton
class PreviewEvalTool @Inject constructor(
    private val controller: PreviewController
) : Tool {
    override val name = "preview_eval"
    override val description =
        "Run JavaScript in the previewed page and return the result. Use it to inspect the DOM " +
            "(e.g. 'document.title', 'document.querySelectorAll(\".error\").length'), to interact " +
            "with the page, or to check computed values. Wrap the expression so it returns a value."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{
              "js":{"type":"string","description":"JavaScript expression or IIFE whose value you want back."}
            },"required":["js"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val js = args.string("js")?.trim()
        if (js.isNullOrEmpty()) return ToolResult(false, "", "Missing required argument 'js'.")
        if (!controller.isAttached) {
            return ToolResult(
                false, "",
                "The preview is not on screen, so there is no page to run JavaScript in. Call " +
                    "preview_open first and ask the user to open the preview."
            )
        }
        val result = controller.evaluate(js)
            ?: return ToolResult(false, "", "The page did not return a value.")
        return ToolResult(true, result.take(MAX_RESULT_CHARS))
    }

    private companion object {
        const val MAX_RESULT_CHARS = 4_000
    }
}

/**
 * Captures the visible preview as a PNG in the workspace. User-facing: a text-only model cannot read
 * the image, but the file is there for the user (or a multimodal model) to inspect.
 */
@Singleton
class PreviewScreenshotTool @Inject constructor(
    private val controller: PreviewController
) : Tool {
    override val name = "preview_screenshot"
    override val description =
        "Save a PNG of the visible preview into the workspace and return its path. Use it to " +
            "capture what the page looks like right now."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{
              "path":{"type":"string","description":"Workspace-relative .png path. Defaults to preview-<time>.png."}
            }}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        if (!controller.isAttached) {
            return ToolResult(
                false, "",
                "The preview is not on screen, so there is nothing to capture. Call preview_open first."
            )
        }
        val bytes = controller.screenshot()
            ?: return ToolResult(false, "", "The preview could not be captured.")
        val path = args.string("path")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "preview-${System.currentTimeMillis()}.png"
        val saved = context.adapter.writeBytes(path, bytes)
        return if (saved) {
            ToolResult(true, "Saved a ${bytes.size / 1024} KB screenshot to $path.")
        } else {
            ToolResult(
                false, "",
                "This workspace cannot store binary files, so the screenshot was not saved."
            )
        }
    }
}
