package com.aliedu.kern.core.agent

import com.aliedu.kern.core.model.Message
import com.aliedu.kern.core.model.MessageStatus
import com.aliedu.kern.core.model.PermissionMode
import com.aliedu.kern.core.model.ProviderProfile
import com.aliedu.kern.core.model.Todo
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.tools.ApplyResult
import com.aliedu.kern.core.tools.PatchProposal
import com.aliedu.kern.core.workspace.WorkspaceAdapter

/** Agent state machine states (PRD 9.1). */
enum class AgentState {
    IDLE,
    PREPARING_CONTEXT,
    REQUESTING_MODEL,
    ASSISTANT_RESPONSE,
    VALIDATING_TOOL_CALL,
    WAITING_FOR_APPROVAL,
    RUNNING_TOOL,
    COMPLETED,
    FAILED,
    CANCELLED,
    BLOCKED
}

/** Everything the UI needs to render a turn (PRD 13.2). */
sealed interface AgentEvent {
    data class State(val state: AgentState, val detail: String? = null) : AgentEvent
    data class Delta(val text: String) : AgentEvent
    data class ReasoningDelta(val text: String) : AgentEvent
    data class AssistantMessage(
        val messageId: String,
        val text: String,
        val status: MessageStatus,
        val reasoning: String? = null
    ) : AgentEvent

    data class ToolInvoked(
        val toolName: String,
        val argsRedacted: String,
        /** The assistant message this tool call belongs to; anchors it inline in the transcript. */
        val messageId: String,
        /**
         * The model's tool-call id. A step can call the same tool twice (two read_file calls), so the
         * UI must close each row by id rather than by name, or one row spins forever.
         */
        val callId: String? = null
    ) : AgentEvent
    data class ToolResultEvent(
        val toolName: String,
        val success: Boolean,
        val summary: String,
        val callId: String? = null,
        /** Line counts for file-editing tools; the transcript shows them as +N / -M. */
        val linesAdded: Int = 0,
        val linesRemoved: Int = 0
    ) : AgentEvent

    data class TodosUpdated(val todos: List<Todo>) : AgentEvent

    data class ApprovalRequired(val proposal: PatchProposal, val reason: String?) : AgentEvent
    data class PatchApplied(val proposalId: String, val result: ApplyResult) : AgentEvent

    /** A plan the agent wrote and wants approved before executing (plan mode). */
    data class PlanProposed(val plan: com.aliedu.kern.core.tools.PlanProposal) : AgentEvent
    data class PlanResolved(val planId: String, val approved: Boolean) : AgentEvent

    /** A shell command the agent wants to run, awaiting user approval (PRD FR-017). */
    data class CommandApprovalRequired(
        val request: com.aliedu.kern.core.tools.CommandRequest,
        val reason: String?
    ) : AgentEvent

    /** The outcome of an approved command. */
    data class CommandCompleted(
        val command: String,
        val result: com.aliedu.kern.core.tools.CommandResult
    ) : AgentEvent

    /**
     * A live chunk of an approved command's output, emitted while it still runs (PRD §4.3 bullet 8),
     * so a long build shows progress instead of a frozen spinner.
     */
    data class CommandOutput(val command: String, val chunk: String) : AgentEvent

    data class Failed(val message: String) : AgentEvent
    data class Completed(val finishReason: String?) : AgentEvent
}

data class TurnRequest(
    val sessionId: String,
    val workspace: Workspace,
    val adapter: WorkspaceAdapter,
    val profile: ProviderProfile,
    val apiKey: String?,
    val history: List<Message>,
    val userInput: String,
    val permissionMode: PermissionMode = PermissionMode.ASK_CHANGES,
    /**
     * Optional ceiling on model turns per user message. `null` (the default) means no ceiling: the
     * loop ends when the model stops asking for tools, the way Claude Code and opencode behave. Set
     * it only to bound a deliberately long run.
     */
    val maxSteps: Int? = null,
    val maxContextBytes: Int = 200_000,
    /**
     * The session's persisted task list, so a resumed or retried turn continues the existing plan
     * instead of starting over (PRD §4.3 bullet 1).
     */
    val todos: List<Todo> = emptyList(),

    /**
     * Whether this workspace's memory notes are injected into the prompt (and appended to when the
     * turn ends). Off means the model never sees them, so nothing from an older task can leak in.
     */
    val memoryEnabled: Boolean = true
)
