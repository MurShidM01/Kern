package com.aliedu.kern.core.tools

import com.aliedu.kern.core.model.Todo
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.providers.api.ToolSchema
import com.aliedu.kern.core.workspace.WorkspaceAdapter
import kotlinx.serialization.json.JsonObject

/** Execution context handed to every tool (PRD 9.2). */
data class ToolContext(
    val sessionId: String,
    val workspace: Workspace,
    val adapter: WorkspaceAdapter,
    val maxResults: Int = 200
)

/** The kind of change a [FilePatch] describes. */
enum class PatchOperation { WRITE, DELETE }

/** One file inside a proposed change. [originalHash] binds apply to the reviewed version. */
data class FilePatch(
    val path: String,
    val originalHash: String,
    val newContent: String,
    val unifiedDiff: String,
    val insertions: Int,
    val deletions: Int,
    val isNewFile: Boolean = false,
    val operation: PatchOperation = PatchOperation.WRITE,
    /**
     * The file's text before the change, kept so a partially applied multi-file patch can be rolled
     * back to exactly the reviewed state (PRD §4.3 bullet 2). Null when the file is new.
     */
    val previousContent: String? = null
)

/** A reviewable, approvable set of file changes (PRD 9.5). */
data class PatchProposal(
    val id: String,
    val files: List<FilePatch>,
    val hash: String
) {
    val totalInsertions: Int get() = files.sumOf { it.insertions }
    val totalDeletions: Int get() = files.sumOf { it.deletions }

    /** A copy holding only [paths]; used to apply the subset of files the user accepted. */
    fun only(paths: Set<String>): PatchProposal = copy(files = files.filter { it.path in paths })
}

data class ToolResult(
    val success: Boolean,
    val output: String,
    val errorMessage: String? = null,
    /** Set by propose_edit; the orchestrator turns this into an approval card. */
    val proposal: PatchProposal? = null,
    /** Set by run_command; the orchestrator executes it only after approval (PRD FR-017). */
    val commandRequest: CommandRequest? = null,
    /** True when the orchestrator must obtain user approval before any effect. */
    val requiresApproval: Boolean = false,
    /** Set by update_todos; the orchestrator emits these so the UI can track the task list. */
    val todos: List<Todo>? = null,
    /** Set by propose_plan; the orchestrator turns this into a plan approval card. */
    val plan: PlanProposal? = null,
    /** Set by remember; the orchestrator appends it to the workspace memory file. */
    val memoryNote: MemoryNote? = null,
    /** Lines added by a file-writing tool, shown as +N on its row. Zero for every other tool. */
    val linesAdded: Int = 0,
    /** Lines removed by a file-writing tool, shown as -M on its row. */
    val linesRemoved: Int = 0
)

/**
 * A single agent capability with a stable typed schema (PRD 9.2). Implementations must validate
 * arguments, enforce workspace containment, and never mutate without an approved proposal.
 */
interface Tool {
    val name: String
    val description: String
    val isMutating: Boolean

    /**
     * True only for tools with no side effects: they read files, search, fetch, or inspect git and
     * never change state, so the orchestrator may run several of them in one step concurrently
     * (PRD §4.3 bullet 8). Anything that can write, delete, run a command, or update the task list
     * must leave this false and runs sequentially, under approval.
     */
    val isReadOnly: Boolean get() = false

    fun schema(): ToolSchema

    suspend fun execute(args: JsonObject, context: ToolContext): ToolResult
}
