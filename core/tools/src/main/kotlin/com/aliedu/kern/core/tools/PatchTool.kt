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

private fun JsonObject.flag(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

/**
 * Proposes a coordinated change across several files in one review (PRD §4.3 bullet 2). Each entry
 * describes one file as a full replacement (`new_content`), a set of exact-snippet edits (`edits`),
 * or a removal (`delete`). The whole call is validated up front and either produces one coherent
 * multi-file patch or fails without a proposal, so a rename or refactor never lands half-applied.
 */
@Singleton
class ProposePatchTool @Inject constructor(
    private val diffService: DiffService
) : Tool {
    override val name = "propose_patch"
    override val description =
        "Propose changes to several files at once for a single review. Each file entry uses " +
            "exactly one of: new_content (whole file; needs overwrite=true if it exists), " +
            "edits (exact {old_string,new_string} snippets in an existing file), or delete=true. " +
            "Returns one diff per file; nothing is written until the user approves."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{
              "files":{"type":"array","description":"One entry per file.","items":{"type":"object","properties":{
                "path":{"type":"string","description":"Workspace-relative file path."},
                "new_content":{"type":"string","description":"The complete new file contents."},
                "edits":{"type":"array","items":{"type":"object","properties":{"old_string":{"type":"string"},"new_string":{"type":"string"}},"required":["old_string","new_string"]}},
                "replace_all":{"type":"boolean"},
                "overwrite":{"type":"boolean","description":"Required to replace an existing file with new_content."},
                "delete":{"type":"boolean","description":"Remove this file."}
              },"required":["path"]}},
              "reason":{"type":"string"}
            },"required":["files"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val rawFiles = args["files"] as? JsonArray
            ?: return ToolResult(false, "", "Missing required argument 'files'.")
        if (rawFiles.isEmpty()) {
            return ToolResult(false, "", "'files' must contain at least one file.")
        }

        val patches = ArrayList<FilePatch>(rawFiles.size)
        val seen = HashSet<String>()
        for ((index, element) in rawFiles.withIndex()) {
            val entry = element as? JsonObject
                ?: return ToolResult(false, "", "File entry ${index + 1} is not an object.")
            val label = "File entry ${index + 1}"
            val rawPath = entry.text("path")
                ?: return ToolResult(false, "", "$label is missing 'path'.")
            val normalized = context.resolvePath(rawPath)
                ?: return ToolResult(false, "", "$label has an invalid path: $rawPath")
            if (normalized.isEmpty()) {
                return ToolResult(false, "", "$label targets the workspace root.")
            }
            if (!seen.add(normalized)) {
                return ToolResult(false, "", "'$normalized' appears more than once in 'files'.")
            }

            val patch = when (val built = buildPatch(entry, normalized, label, context)) {
                is BuildResult.Failed -> return ToolResult(false, "", built.message)
                is BuildResult.Ok -> built.patch
            }
            patches.add(patch)
        }

        val proposal = PatchProposal(
            id = UUID.randomUUID().toString(),
            files = patches,
            hash = diffService.proposalHash(patches.map { it.path to it.newContent })
        )
        val summary = "Proposed changes to ${patches.size} file(s) " +
            "(+${proposal.totalInsertions} / -${proposal.totalDeletions}). Awaiting approval."
        return ToolResult(
            success = true,
            output = summary,
            proposal = proposal,
            requiresApproval = true,
            linesAdded = proposal.totalInsertions,
            linesRemoved = proposal.totalDeletions
        )
    }

    private sealed interface BuildResult {
        data class Ok(val patch: FilePatch) : BuildResult
        data class Failed(val message: String) : BuildResult
    }

    private suspend fun buildPatch(
        entry: JsonObject,
        path: String,
        label: String,
        context: ToolContext
    ): BuildResult {
        val newContent = entry.text("new_content")
        val rawEdits = entry["edits"] as? JsonArray
        val delete = entry.flag("delete") ?: false
        val overwrite = entry.flag("overwrite") ?: false
        val replaceAll = entry.flag("replace_all") ?: false

        val modes = listOfNotNull(
            newContent?.let { "new_content" },
            rawEdits?.let { "edits" },
            if (delete) "delete" else null
        )
        if (modes.size != 1) {
            return BuildResult.Failed(
                "$label must set exactly one of new_content, edits or delete (got ${modes.size})."
            )
        }

        return try {
            when {
                delete -> {
                    if (!context.adapter.exists(path)) {
                        return BuildResult.Failed("$label: no such file $path.")
                    }
                    val oldText = context.adapter.readText(path).text
                    val diff = diffService.unifiedDiff(path, oldText, "")
                    BuildResult.Ok(
                        FilePatch(
                            path = path,
                            originalHash = diffService.hash(oldText),
                            newContent = "",
                            unifiedDiff = diff.unifiedDiff,
                            insertions = diff.insertions,
                            deletions = diff.deletions,
                            operation = PatchOperation.DELETE,
                            previousContent = oldText
                        )
                    )
                }

                newContent != null -> {
                    val exists = context.adapter.exists(path)
                    if (exists && !overwrite) {
                        return BuildResult.Failed(
                            "$label: $path already exists. Use edits for a targeted change, or set " +
                                "overwrite=true to replace it."
                        )
                    }
                    val oldText = if (exists) context.adapter.readText(path).text else ""
                    if (oldText == newContent) {
                        return BuildResult.Failed("$label: the new content matches $path; nothing to do.")
                    }
                    val diff = diffService.unifiedDiff(path, oldText, newContent)
                    BuildResult.Ok(
                        FilePatch(
                            path = path,
                            originalHash = diffService.hash(oldText),
                            newContent = newContent,
                            unifiedDiff = diff.unifiedDiff,
                            insertions = diff.insertions,
                            deletions = diff.deletions,
                            isNewFile = !exists,
                            previousContent = oldText.takeIf { exists }
                        )
                    )
                }

                else -> {
                    val edits = ArrayList<Pair<String, String>>(rawEdits?.size ?: 0)
                    for ((editIndex, element) in (rawEdits ?: JsonArray(emptyList())).withIndex()) {
                        val obj = element as? JsonObject
                            ?: return BuildResult.Failed("$label edit ${editIndex + 1} is not an object.")
                        val old = obj.text("old_string")
                            ?: return BuildResult.Failed("$label edit ${editIndex + 1} is missing 'old_string'.")
                        val new = obj.text("new_string")
                            ?: return BuildResult.Failed("$label edit ${editIndex + 1} is missing 'new_string'.")
                        edits.add(old to new)
                    }
                    if (edits.isEmpty()) {
                        return BuildResult.Failed("$label: 'edits' must contain at least one edit.")
                    }
                    if (!context.adapter.exists(path)) {
                        return BuildResult.Failed("$label: no such file $path. Use new_content to create it.")
                    }
                    val oldText = context.adapter.readText(path).text
                    val updated = when (val outcome = SnippetEdits.apply(oldText, edits, replaceAll, path)) {
                        is SnippetEdits.Outcome.Failed -> return BuildResult.Failed("$label: ${outcome.message}")
                        is SnippetEdits.Outcome.Applied -> outcome.text
                    }
                    if (updated == oldText) {
                        return BuildResult.Failed("$label: the edits produce identical content in $path.")
                    }
                    val diff = diffService.unifiedDiff(path, oldText, updated)
                    BuildResult.Ok(
                        FilePatch(
                            path = path,
                            originalHash = diffService.hash(oldText),
                            newContent = updated,
                            unifiedDiff = diff.unifiedDiff,
                            insertions = diff.insertions,
                            deletions = diff.deletions,
                            previousContent = oldText
                        )
                    )
                }
            }
        } catch (e: WorkspaceAccessException) {
            BuildResult.Failed("$label: ${e.message}")
        }
    }
}
