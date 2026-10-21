package com.aliedu.kern.core.tools

import com.aliedu.kern.core.providers.api.ToolSchema
import com.aliedu.kern.core.workspace.WorkspaceAdapter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Shared plumbing for the Git tools: every one of them proposes a real `git` invocation. */
private suspend fun requireGitRepo(adapter: WorkspaceAdapter): Boolean =
    runCatching { adapter.exists(".git") }.getOrDefault(false)

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

/** Wraps a value in single quotes for the shell, escaping any embedded quote. */
private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private fun gitRequest(
    context: ToolContext,
    command: String,
    impact: CommandImpact,
    reason: String?,
    alwaysApprove: Boolean = false
) = CommandRequest(
    workspaceId = context.workspace.id,
    command = command,
    cwd = context.workspace.localPath.orEmpty(),
    impact = impact,
    timeoutMs = 30_000,
    outputPolicy = OutputPolicy.CAPTURE,
    reason = reason,
    alwaysApprove = alwaysApprove
)

/**
 * `git status` as the user would see it in a terminal (PRD §4.3 bullet 3). It shells out to the
 * real CLI so the reported state cannot drift from what `git status` prints; the porcelain `--short`
 * form is compact and stable enough to feed the model.
 */
@Singleton
class GitStatusTool @Inject constructor() : Tool {
    override val name = "git_status"
    override val isReadOnly = true
    override val description =
        "Show the working tree status (branch and changed files) using the git CLI. Read-only."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{"reason":{"type":"string"}},"required":[]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        if (!requireGitRepo(context.adapter)) {
            return ToolResult(false, "", "This workspace is not a Git repository (no .git directory).")
        }
        return ToolResult(
            success = true,
            output = "Proposed git status.",
            commandRequest = gitRequest(
                context,
                command = "git status --short --branch",
                impact = CommandImpact.READ_ONLY,
                reason = args.string("reason") ?: "Inspect the working tree."
            ),
            requiresApproval = true
        )
    }
}

/** `git diff`, staged or unstaged, optionally limited to one path (PRD §4.3 bullet 3). */
@Singleton
class GitDiffTool @Inject constructor() : Tool {
    override val name = "git_diff"
    override val isReadOnly = true
    override val description =
        "Show the diff of unstaged changes, or staged changes with staged=true. Read-only."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{"staged":{"type":"boolean","description":"Show the staged (index) diff instead of the working tree."},"path":{"type":"string","description":"Limit the diff to this workspace-relative path."},"reason":{"type":"string"}},"required":[]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        if (!requireGitRepo(context.adapter)) {
            return ToolResult(false, "", "This workspace is not a Git repository (no .git directory).")
        }
        val staged = args.bool("staged") ?: false
        val path = args.string("path")?.trim()
        val command = buildString {
            append("git diff")
            if (staged) append(" --staged")
            if (!path.isNullOrEmpty()) append(" -- ").append(shellQuote(path))
        }
        return ToolResult(
            success = true,
            output = "Proposed git diff.",
            commandRequest = gitRequest(
                context,
                command = command,
                impact = CommandImpact.READ_ONLY,
                reason = args.string("reason") ?: "Review the pending changes."
            ),
            requiresApproval = true
        )
    }
}

/**
 * `git commit` with a message the model proposes and the user confirms (PRD §4.3 bullet 3). The
 * request is flagged [CommandRequest.alwaysApprove] so a commit is never auto-run, even in a
 * permissive permission mode — the approval card is where the user reads and accepts the message.
 */
@Singleton
class GitCommitTool @Inject constructor() : Tool {
    override val name = "git_commit"
    override val description =
        "Commit the working tree with a message you propose. Set add_all=true to stage every change " +
            "first. The user must approve the message before anything is committed."
    override val isMutating = false

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{"message":{"type":"string","description":"The commit message to propose."},"add_all":{"type":"boolean","description":"Stage all changes (git add -A) before committing."},"reason":{"type":"string"}},"required":["message"]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        if (!requireGitRepo(context.adapter)) {
            return ToolResult(false, "", "This workspace is not a Git repository (no .git directory).")
        }
        val message = args.string("message")?.trim()
        if (message.isNullOrEmpty()) {
            return ToolResult(false, "", "Missing required argument 'message'.")
        }
        val addAll = args.bool("add_all") ?: false
        val command = buildString {
            if (addAll) append("git add -A && ")
            append("git commit -m ").append(shellQuote(message))
        }
        return ToolResult(
            success = true,
            output = "Proposed commit: $message",
            commandRequest = gitRequest(
                context,
                command = command,
                impact = CommandImpact.MUTATING,
                reason = args.string("reason") ?: "Commit: $message",
                alwaysApprove = true
            ),
            requiresApproval = true
        )
    }
}
