package com.aliedu.kern.core.tools

import com.aliedu.kern.core.providers.api.ToolSchema
import com.aliedu.kern.core.tools.diff.DiffService
import com.aliedu.kern.core.workspace.WorkspaceAccessException
import com.aliedu.kern.core.workspace.WorkspaceAdapter
import com.aliedu.kern.core.workspace.WorkspacePaths
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Builds a reviewable patch for a single file. It never writes; the orchestrator surfaces the
 * proposal for approval (PRD 9.2 propose_edit, 9.5).
 */
@Singleton
class ProposeEditTool @Inject constructor(
    private val diffService: DiffService
) : Tool {
    override val name = "propose_edit"
    override val description =
        "Propose replacing a file's full text. Returns a diff for the user to review; it does not write anything."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{"path":{"type":"string"},"new_content":{"type":"string","description":"The complete new file contents."},"reason":{"type":"string"}},"required":["path","new_content"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val rawPath = args.str("path") ?: return ToolResult(false, "", "Missing required argument 'path'.")
        val newContent = args.str("new_content")
            ?: return ToolResult(false, "", "Missing required argument 'new_content'.")
        val normalized = context.resolvePath(rawPath)
            ?: return ToolResult(false, "", "Invalid path: $rawPath")

        return try {
            val exists = context.adapter.exists(normalized)
            val oldText = if (exists) context.adapter.readText(normalized).text else ""
            if (oldText == newContent) {
                return ToolResult(true, "No change: proposed content matches the current file.")
            }
            val diff = diffService.unifiedDiff(normalized, oldText, newContent)
            val filePatch = FilePatch(
                path = normalized,
                originalHash = diffService.hash(oldText),
                newContent = newContent,
                unifiedDiff = diff.unifiedDiff,
                insertions = diff.insertions,
                deletions = diff.deletions,
                isNewFile = !exists,
                previousContent = oldText.takeIf { exists }
            )
            val proposal = PatchProposal(
                id = UUID.randomUUID().toString(),
                files = listOf(filePatch),
                hash = diffService.proposalHash(listOf(normalized to newContent))
            )
            val summary = "Proposed ${if (exists) "edit" else "new file"} for $normalized " +
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

/**
 * Builds a reviewable patch that removes a file. Like [ProposeEditTool] it never deletes; the
 * orchestrator surfaces the proposal for approval and [PatchApplier] performs the delete (PRD 9.2, 9.5).
 */
@Singleton
class DeleteFileTool @Inject constructor(
    private val diffService: DiffService
) : Tool {
    override val name = "delete_file"
    override val description =
        "Propose deleting a file from the workspace. Returns a diff for the user to review; the file is not removed until the change is approved."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative file path to delete."},"reason":{"type":"string"}},"required":["path"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val rawPath = args.str("path") ?: return ToolResult(false, "", "Missing required argument 'path'.")
        val normalized = context.resolvePath(rawPath)
            ?: return ToolResult(false, "", "Invalid path: $rawPath")
        if (normalized.isEmpty()) return ToolResult(false, "", "Refusing to delete the workspace root.")

        return try {
            if (!context.adapter.exists(normalized)) {
                return ToolResult(false, "", "No such file: $normalized")
            }
            val oldText = try {
                context.adapter.readText(normalized).text
            } catch (e: WorkspaceAccessException) {
                return ToolResult(false, "", "$normalized is a folder; delete its files individually.")
            }
            val diff = diffService.unifiedDiff(normalized, oldText, "")
            val filePatch = FilePatch(
                path = normalized,
                originalHash = diffService.hash(oldText),
                newContent = "",
                unifiedDiff = diff.unifiedDiff,
                insertions = diff.insertions,
                deletions = diff.deletions,
                isNewFile = false,
                operation = PatchOperation.DELETE,
                previousContent = oldText
            )
            val proposal = PatchProposal(
                id = UUID.randomUUID().toString(),
                files = listOf(filePatch),
                hash = diffService.proposalHash(listOf(normalized to ""))
            )
            ToolResult(
                success = true,
                output = "Proposed deleting $normalized. Awaiting approval.",
                proposal = proposal,
                requiresApproval = true,
                linesRemoved = proposal.totalDeletions
            )
        } catch (e: WorkspaceAccessException) {
            ToolResult(false, "", e.message)
        }
    }
}

/**
 * Creates a new file, or replaces an existing one's whole text. The guardrail is `overwrite`:
 * an agent that means to touch one line but sends a whole file body would silently clobber the
 * rest, so overwriting an existing file requires an explicit `overwrite=true`. Creating a new file
 * needs no flag. Like the other write tools it only proposes; the orchestrator gates the write
 * behind approval (PRD 9.2, 9.5).
 */
@Singleton
class WriteFileTool @Inject constructor(
    private val diffService: DiffService
) : Tool {
    override val name = "write_file"
    override val description =
        "Create a new file with the given content. To replace an existing file you must pass " +
            "overwrite=true; otherwise use edit_file for a targeted change. Returns a diff; " +
            "nothing is written until the user approves."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative file path."},"content":{"type":"string","description":"The complete file contents."},"overwrite":{"type":"boolean","description":"Must be true to replace a file that already exists."},"reason":{"type":"string"}},"required":["path","content"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val rawPath = args.str("path") ?: return ToolResult(false, "", "Missing required argument 'path'.")
        val content = args.str("content")
            ?: return ToolResult(false, "", "Missing required argument 'content'.")
        val normalized = context.resolvePath(rawPath)
            ?: return ToolResult(false, "", "Invalid path: $rawPath")
        if (normalized.isEmpty()) return ToolResult(false, "", "Refusing to write to the workspace root.")
        val overwrite = (args["overwrite"] as? JsonPrimitive)?.booleanOrNull ?: false

        return try {
            val exists = context.adapter.exists(normalized)
            if (exists && !overwrite) {
                return ToolResult(
                    false, "",
                    "$normalized already exists. Use edit_file for a targeted change, or pass " +
                        "overwrite=true to replace the whole file."
                )
            }
            val oldText = if (exists) context.adapter.readText(normalized).text else ""
            if (oldText == content) {
                return ToolResult(true, "No change: proposed content matches the current file.")
            }
            val diff = diffService.unifiedDiff(normalized, oldText, content)
            val filePatch = FilePatch(
                path = normalized,
                originalHash = diffService.hash(oldText),
                newContent = content,
                unifiedDiff = diff.unifiedDiff,
                insertions = diff.insertions,
                deletions = diff.deletions,
                isNewFile = !exists,
                previousContent = oldText.takeIf { exists }
            )
            val proposal = PatchProposal(
                id = UUID.randomUUID().toString(),
                files = listOf(filePatch),
                hash = diffService.proposalHash(listOf(normalized to content))
            )
            val verb = if (exists) "overwriting" else "creating"
            val summary = "Proposed $verb $normalized " +
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

data class FileApplyStatus(val path: String, val applied: Boolean, val message: String)

data class ApplyResult(
    val allApplied: Boolean,
    val statuses: List<FileApplyStatus>,
    val appliedAt: Long
)

/**
 * Applies an approved proposal. Before writing anything it validates *every* file: each file's
 * current hash must match the hash captured at preview time, and a delete must target an existing
 * file. If any file fails validation nothing is written, so a multi-file change is all-or-nothing
 * and a rejected or interrupted task leaves the workspace exactly as it was (PRD 9.2 apply_edit,
 * 9.5, 15.3). If a write fails part-way the already-written files are rolled back to their captured
 * previous content.
 */
@Singleton
class PatchApplier @Inject constructor(
    private val diffService: DiffService
) {
    suspend fun apply(proposal: PatchProposal, adapter: WorkspaceAdapter): ApplyResult {
        // Phase 1 — validate. Never write until every file is known to be safe to write.
        val state = ArrayList<FileState>(proposal.files.size)
        val failures = ArrayList<FileApplyStatus>()
        for (file in proposal.files) {
            val checked = check(file, adapter)
            when (checked) {
                is CheckResult.Ok -> state.add(checked.state)
                is CheckResult.Failed -> failures.add(FileApplyStatus(file.path, false, checked.message))
            }
        }
        if (failures.isNotEmpty()) {
            // Report every file as unapplied; nothing was written.
            val statuses = proposal.files.map { file ->
                failures.firstOrNull { it.path == file.path }
                    ?: FileApplyStatus(file.path, false, "Not applied: another file failed validation.")
            }
            return ApplyResult(allApplied = false, statuses = statuses, appliedAt = System.currentTimeMillis())
        }

        // Phase 2 — write. Track what we wrote so a mid-way failure can be undone.
        val statuses = ArrayList<FileApplyStatus>(state.size)
        val written = ArrayList<FileState>(state.size)
        for (fileState in state) {
            val status = write(fileState, adapter)
            statuses.add(status)
            if (status.applied) written.add(fileState) else {
                // Roll back everything already written so the workspace matches the review state.
                written.forEach { rollBack(it, adapter) }
                for (index in statuses.indices) {
                    if (statuses[index].applied) {
                        statuses[index] = statuses[index].copy(
                            applied = false,
                            message = "Rolled back because a later file failed."
                        )
                    }
                }
                break
            }
        }
        return ApplyResult(
            allApplied = statuses.all { it.applied },
            statuses = statuses,
            appliedAt = System.currentTimeMillis()
        )
    }

    private sealed interface CheckResult {
        data class Ok(val state: FileState) : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    private data class FileState(
        val patch: FilePatch,
        val exists: Boolean,
        val currentText: String
    )

    private suspend fun check(file: FilePatch, adapter: WorkspaceAdapter): CheckResult = try {
        val exists = adapter.exists(file.path)
        val currentText = if (exists) adapter.readText(file.path).text else ""
        when {
            diffService.hash(currentText) != file.originalHash ->
                CheckResult.Failed("File changed since preview; regenerate the change before applying.")

            file.operation == PatchOperation.DELETE && !exists ->
                CheckResult.Failed("Already gone.")

            else -> CheckResult.Ok(FileState(file, exists, currentText))
        }
    } catch (e: WorkspaceAccessException) {
        CheckResult.Failed(e.message ?: "Failed to read the file.")
    }

    private suspend fun write(state: FileState, adapter: WorkspaceAdapter): FileApplyStatus {
        val file = state.patch
        return try {
            when (file.operation) {
                PatchOperation.DELETE -> {
                    if (adapter.delete(file.path)) {
                        FileApplyStatus(file.path, applied = true, message = "Deleted.")
                    } else {
                        FileApplyStatus(file.path, applied = false, message = "Could not delete the file.")
                    }
                }

                PatchOperation.WRITE -> {
                    if (!state.exists) {
                        adapter.createFile(file.path, file.newContent)
                    } else {
                        adapter.writeText(file.path, file.newContent)
                    }
                    FileApplyStatus(file.path, applied = true, message = "Applied.")
                }
            }
        } catch (e: WorkspaceAccessException) {
            FileApplyStatus(file.path, applied = false, message = e.message ?: "Failed to apply.")
        }
    }

    /** Best-effort restore of a file to its reviewed starting point. */
    private suspend fun rollBack(state: FileState, adapter: WorkspaceAdapter) {
        val file = state.patch
        try {
            when {
                !state.exists -> adapter.delete(file.path)
                file.previousContent != null -> adapter.writeText(file.path, file.previousContent)
            }
        } catch (e: WorkspaceAccessException) {
            // Nothing further we can do; the status message already tells the user the file is stale.
        }
    }
}
