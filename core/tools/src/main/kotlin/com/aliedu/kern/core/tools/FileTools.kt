package com.aliedu.kern.core.tools

import com.aliedu.kern.core.model.FileKind
import com.aliedu.kern.core.model.SearchMatchKind
import com.aliedu.kern.core.providers.api.ToolSchema
import com.aliedu.kern.core.workspace.WorkspaceAccessException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

@Singleton
class ListFilesTool @Inject constructor() : Tool {
    override val name = "list_files"
    override val isReadOnly = true
    override val description =
        "List files and folders inside the active workspace. Optional 'path' lists a subfolder."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative folder path; omit for the root."}},"required":[]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val raw = args.string("path").orEmpty()
        val path = context.resolvePath(raw)
            ?: return ToolResult(false, "", "Invalid path: $raw")
        return try {
            val nodes = context.adapter.list(path)
            if (nodes.isEmpty()) {
                ToolResult(true, "No entries in '${path.ifEmpty { "/" }}'.")
            } else {
                val text = nodes.joinToString("\n") { node ->
                    val type = if (node.kind == FileKind.DIRECTORY) "dir " else "file"
                    val size = node.sizeBytes?.let { " (${it} bytes)" }.orEmpty()
                    "$type  ${node.path}$size"
                }
                ToolResult(true, text)
            }
        } catch (e: WorkspaceAccessException) {
            ToolResult(false, "", e.message)
        }
    }
}

@Singleton
class ReadFileTool @Inject constructor() : Tool {
    override val name = "read_file"
    override val isReadOnly = true
    override val description = "Read a text file from the active workspace, returning its contents."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative file path."}},"required":["path"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val raw = args.string("path") ?: return ToolResult(false, "", "Missing required argument 'path'.")
        val path = context.resolvePath(raw)
            ?: return ToolResult(false, "", "Invalid path: $raw")
        return try {
            val content = context.adapter.readText(path)
            // An empty body is a real answer ("the file exists and is blank"), not a failed call.
            // Saying so keeps the model from re-reading the same file to find out what happened.
            if (content.text.isEmpty()) {
                return ToolResult(
                    true,
                    "$path is empty (0 bytes). Create content with write_file, or edit_file to add some."
                )
            }
            val notice = if (content.truncated) "\n\n[truncated: file larger than the read limit]" else ""
            ToolResult(true, content.text + notice)
        } catch (e: WorkspaceAccessException) {
            ToolResult(false, "", e.message)
        }
    }
}

@Singleton
class SearchFilesTool @Inject constructor() : Tool {
    override val name = "search_files"
    override val isReadOnly = true
    override val description =
        "Search file names and (optionally) text content in the active workspace."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{"query":{"type":"string"},"include_content":{"type":"boolean","description":"Also search inside text files."}},"required":["query"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val query = args.string("query") ?: return ToolResult(false, "", "Missing required argument 'query'.")
        val includeContent = args.bool("include_content") ?: true
        return try {
            val matches = context.adapter.search(query, context.maxResults, includeContent)
            if (matches.isEmpty()) {
                ToolResult(true, "No matches for '$query'.")
            } else {
                val text = matches.joinToString("\n") { match ->
                    when (match.kind) {
                        SearchMatchKind.FILENAME -> "filename  ${match.path}"
                        SearchMatchKind.CONTENT -> "${match.path}:${match.lineNumber}: ${match.lineText}"
                    }
                }
                ToolResult(true, text)
            }
        } catch (e: WorkspaceAccessException) {
            ToolResult(false, "", e.message)
        }
    }
}
