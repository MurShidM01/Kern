package com.aliedu.kern.core.tools

import com.aliedu.kern.core.providers.api.ToolSchema
import com.aliedu.kern.core.tools.diff.DiffService
import com.aliedu.kern.core.workspace.WorkspaceAccessException
import com.aliedu.kern.core.workspace.WorkspacePaths
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Exact-snippet editing: replaces one or more `old_string`/`new_string` pairs inside an existing
 * file and returns a reviewable diff. This is what makes edits affordable on a large file — sending
 * the whole new body (as [ProposeEditTool] requires) is expensive and easy to get wrong.
 *
 * Every edit is applied in memory and validated before anything is proposed, so a call either
 * produces one coherent patch or fails with the edit that did not match. Like the other write
 * tools it never touches the workspace; the orchestrator gates the patch behind approval.
 */
@Singleton
class EditFileTool @Inject constructor(
    private val diffService: DiffService
) : Tool {
    override val name = "edit_file"
    override val description =
        "Edit an existing text file by replacing exact snippets. Provide one or more " +
            "{old_string,new_string} pairs; each old_string must match exactly once unless " +
            "replace_all is true. Returns a diff; nothing is written until the user approves."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{
              "path":{"type":"string","description":"Workspace-relative file path."},
              "edits":{"type":"array","description":"Edits applied in order.","items":{"type":"object","properties":{"old_string":{"type":"string"},"new_string":{"type":"string"}},"required":["old_string","new_string"]}},
              "replace_all":{"type":"boolean","description":"Replace every occurrence instead of requiring exactly one."},
              "reason":{"type":"string"}
            },"required":["path","edits"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val rawPath = args.text("path")
            ?: return ToolResult(false, "", "Missing required argument 'path'.")
        val normalized = context.resolvePath(rawPath)
            ?: return ToolResult(false, "", "Invalid path: $rawPath")

        val rawEdits = args["edits"] as? JsonArray
            ?: return ToolResult(false, "", "Missing required argument 'edits'.")
        if (rawEdits.isEmpty()) {
            return ToolResult(false, "", "'edits' must contain at least one edit.")
        }
        val edits = ArrayList<Pair<String, String>>(rawEdits.size)
        for ((index, element) in rawEdits.withIndex()) {
            val obj = element as? JsonObject
                ?: return ToolResult(false, "", "Edit ${index + 1} is not an object.")
            val old = obj.text("old_string")
                ?: return ToolResult(false, "", "Edit ${index + 1} is missing 'old_string'.")
            val new = obj.text("new_string")
                ?: return ToolResult(false, "", "Edit ${index + 1} is missing 'new_string'.")
            edits.add(old to new)
        }
        val replaceAll = (args["replace_all"] as? JsonPrimitive)?.booleanOrNull ?: false

        return try {
            if (!context.adapter.exists(normalized)) {
                return ToolResult(
                    false, "",
                    "No such file: $normalized. Use propose_edit to create a new file."
                )
            }
            val oldText = context.adapter.readText(normalized).text
            val updated = when (
                val outcome = SnippetEdits.apply(oldText, edits, replaceAll, normalized)
            ) {
                is SnippetEdits.Outcome.Failed -> return ToolResult(false, "", outcome.message)
                is SnippetEdits.Outcome.Applied -> outcome.text
            }
            if (updated == oldText) {
                return ToolResult(true, "No change: the edits produce identical content.")
            }

            val diff = diffService.unifiedDiff(normalized, oldText, updated)
            val filePatch = FilePatch(
                path = normalized,
                originalHash = diffService.hash(oldText),
                newContent = updated,
                unifiedDiff = diff.unifiedDiff,
                insertions = diff.insertions,
                deletions = diff.deletions,
                isNewFile = false,
                previousContent = oldText
            )
            val proposal = PatchProposal(
                id = UUID.randomUUID().toString(),
                files = listOf(filePatch),
                hash = diffService.proposalHash(listOf(normalized to updated))
            )
            val summary = "Proposed ${edits.size} edit(s) to $normalized " +
                "(+${diff.insertions} / -${diff.deletions}). Awaiting approval."
            ToolResult(
                success = true,
                output = summary,
                proposal = proposal,
                requiresApproval = true,
                linesAdded = proposal.totalInsertions,
                linesRemoved = proposal.totalDeletions
            )
        } catch (e: WorkspaceAccessException) {
            ToolResult(false, "", e.message)
        }
    }
}
