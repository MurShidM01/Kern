package com.aliedu.kern.core.agent

import com.aliedu.kern.core.common.coroutines.DispatcherProvider
import com.aliedu.kern.core.common.redaction.Redactor
import com.aliedu.kern.core.model.MessageRole
import com.aliedu.kern.core.model.MessageStatus
import com.aliedu.kern.core.model.PermissionMode
import com.aliedu.kern.core.model.Todo
import com.aliedu.kern.core.model.TodoStatus
import com.aliedu.kern.core.providers.api.AgentMessage
import com.aliedu.kern.core.providers.api.AgentRequest
import com.aliedu.kern.core.providers.api.AiProviderAdapter
import com.aliedu.kern.core.providers.api.ProviderEvent
import com.aliedu.kern.core.providers.api.ToolCall
import com.aliedu.kern.core.tools.CommandExecutor
import com.aliedu.kern.core.tools.CommandImpact
import com.aliedu.kern.core.tools.CommandOutputSummarizer
import com.aliedu.kern.core.tools.CommandRecovery
import com.aliedu.kern.core.tools.CommandRequest
import com.aliedu.kern.core.tools.CommandResult
import com.aliedu.kern.core.tools.OutputPolicy
import com.aliedu.kern.core.tools.OutputWindow
import com.aliedu.kern.core.tools.PatchApplier
import com.aliedu.kern.core.tools.PatchProposal
import com.aliedu.kern.core.tools.ToolArgs
import com.aliedu.kern.core.tools.ToolContext
import com.aliedu.kern.core.tools.ToolRegistry
import com.aliedu.kern.core.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives one agent turn through the PRD 9.1 state machine: build context, request the model,
 * stream the answer, validate and run tool calls, and gate writes behind user approval. Every
 * terminal state is emitted so the caller can persist a session event.
 */
/** Retry delays for a provider call that failed before producing anything. */
private val RETRY_BACKOFF_MS = longArrayOf(700L, 1800L)

/** How many times a turn is pushed to continue while its task list still has open items. */
private const val MAX_NUDGES = 2

/** How many empty replies are tolerated before the turn reports a failure instead of stopping. */
private const val MAX_EMPTY_STEPS = 1

/** Sent as a tool result when the tool produced no output at all. Never send an empty body. */
private const val NO_TOOL_OUTPUT = "(the tool produced no output)"

/** Replayed conversation size, in characters, at which old tool output starts being elided. */
private const val CONTEXT_ELIDE_CHARS = 200_000

/** Number of most recent tool results that always keep their full text. */
private const val KEEP_TOOL_RESULTS = 8

/** Tool results at or below this size are already small enough to leave alone. */
private const val ELIDED_MAX_CHARS = 2_000

@Singleton
class AgentOrchestrator @Inject constructor(
    private val providerAdapter: AiProviderAdapter,
    private val toolRegistry: ToolRegistry,
    private val patchApplier: PatchApplier,
    private val commandExecutor: CommandExecutor,
    private val contextBuilder: ContextBuilder,
    private val projectRulesLoader: ProjectRulesLoader,
    private val notes: WorkspaceNotes,
    private val dispatchers: DispatcherProvider
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pendingApprovals = ConcurrentHashMap<String, CompletableDeferred<ApprovalDecision>>()
    private val pendingPlans = ConcurrentHashMap<String, CompletableDeferred<PlanDecision>>()

    /** The user's answer to a proposed plan: approval, or the changes they asked for. */
    private data class PlanDecision(val approved: Boolean, val feedback: String? = null)

    /**
     * The user's answer to a pending change. [acceptedPaths] narrows a multi-file patch to the files
     * the user ticked; null means "all files" (or the whole thing for a command).
     */
    data class ApprovalDecision(
        val approved: Boolean,
        val acceptedPaths: Set<String>? = null
    )

    /** Called by the UI when the user approves or rejects a pending change. */
    fun resolveApproval(sessionId: String, approved: Boolean) {
        pendingApprovals[sessionId]?.complete(ApprovalDecision(approved))
    }

    /** Called by the UI when the user approves only some files of a multi-file change. */
    fun resolveApproval(sessionId: String, decision: ApprovalDecision) {
        pendingApprovals[sessionId]?.complete(decision)
    }

    /**
     * Called by the UI with the user's answer to a proposed plan. [feedback] is what they want
     * changed; it is handed back to the model so it can revise the plan instead of guessing.
     */
    fun resolvePlan(sessionId: String, approved: Boolean, feedback: String? = null) {
        pendingPlans[sessionId]?.complete(PlanDecision(approved, feedback))
    }

    fun runTurn(request: TurnRequest): Flow<AgentEvent> = flow<AgentEvent> {
        emit(AgentEvent.State(AgentState.PREPARING_CONTEXT))
        val toolContext = ToolContext(
            sessionId = request.sessionId,
            workspace = request.workspace,
            adapter = request.adapter
        )
        val messages = contextBuilder.buildMessages(
            workspace = request.workspace,
            history = request.history,
            userInput = request.userInput,
            maxBytes = request.maxContextBytes,
            projectRules = projectRulesLoader.load(request.adapter),
            todos = request.todos,
            plan = notes.plan(request.workspace),
            memory = if (request.memoryEnabled) notes.memory(request.workspace) else null
        ).toMutableList()
        val usedTools = LinkedHashSet<String>()
        // The live task list, updated as the model calls update_todos, so a turn that stops with
        // work still pending can be pushed to continue instead of ending early.
        var latestTodos: List<Todo> = request.todos
        var nudges = 0
        var emptySteps = 0

        // No step ceiling unless the caller asked for one: the turn ends when the model stops
        // requesting tools, so a task can take as many tool calls as it needs.
        val maxSteps = request.maxSteps
        var steps = 0
        while (maxSteps == null || steps < maxSteps) {
            // Long tasks overflow the window. Tool output is the bulk of it and is cheap to fetch
            // again, so old results are elided rather than letting the provider reject the request
            // and killing the turn half-way through.
            if (elideOldToolResults(messages)) emit(AgentEvent.State(AgentState.PREPARING_CONTEXT))

            emit(AgentEvent.State(AgentState.REQUESTING_MODEL))
            emit(AgentEvent.State(AgentState.ASSISTANT_RESPONSE))

            val agentRequest = AgentRequest(
                model = request.profile.modelId,
                messages = messages,
                tools = toolRegistry.schemas()
            )

            val textBuilder = StringBuilder()
            val reasoningBuilder = StringBuilder()
            val toolCalls = ArrayList<ToolCall>()
            var failure: String? = null
            var finishReason: String? = null

            // A dropped connection or a 429 used to end the whole task. Retry a few times, but only
            // while nothing has reached the transcript yet: replaying a partially streamed step
            // would duplicate text the user has already seen.
            var attempt = 0
            while (true) {
                textBuilder.setLength(0)
                reasoningBuilder.setLength(0)
                toolCalls.clear()
                failure = null
                finishReason = null

                providerAdapter.streamResponse(request.profile, request.apiKey, agentRequest).collect { event ->
                    when (event) {
                        is ProviderEvent.Delta -> {
                            textBuilder.append(event.text)
                            emit(AgentEvent.Delta(event.text))
                        }

                        is ProviderEvent.ReasoningDelta -> {
                            reasoningBuilder.append(event.text)
                            emit(AgentEvent.ReasoningDelta(event.text))
                        }

                        is ProviderEvent.ToolCallRequested -> toolCalls.add(event.toolCall)
                        is ProviderEvent.Usage -> Unit
                        is ProviderEvent.Completed -> finishReason = event.finishReason
                        is ProviderEvent.Failed -> failure = event.error.userMessage
                    }
                }

                val produced = textBuilder.isNotBlank() ||
                    reasoningBuilder.isNotBlank() ||
                    toolCalls.isNotEmpty()
                if (failure == null || produced || attempt >= RETRY_BACKOFF_MS.size) break
                com.aliedu.kern.core.common.logging.KernLog.d(
                    "Agent",
                    "step=${steps + 1} retry ${attempt + 1} after: $failure"
                )
                delay(RETRY_BACKOFF_MS[attempt])
                attempt++
            }

            val failureMessage = failure
            if (failureMessage != null) {
                emit(AgentEvent.Failed(failureMessage))
                emit(AgentEvent.State(AgentState.FAILED))
                return@flow
            }

            val assistantText = textBuilder.toString()
            val reasoningText = reasoningBuilder.toString()
            val stepMessageId = UUID.randomUUID().toString()
            com.aliedu.kern.core.common.logging.KernLog.d(
                "Agent",
                "step=${steps + 1} textChars=${assistantText.length} reasoningChars=${reasoningText.length} " +
                    "tools=${toolCalls.joinToString { it.name }} finish=$finishReason"
            )
            // Emit an anchor message for any step that produced text, reasoning or a tool call so its
            // tools can be placed inline in the transcript. Tool-only steps persist a blank anchor
            // that the UI hides but keeps for ordering.
            if (assistantText.isNotBlank() || reasoningText.isNotBlank() || toolCalls.isNotEmpty()) {
                emit(
                    AgentEvent.AssistantMessage(
                        messageId = stepMessageId,
                        text = assistantText,
                        status = MessageStatus.COMPLETE,
                        reasoning = reasoningText.takeIf { it.isNotBlank() }
                    )
                )
            }

            // A step with no text and no tool call is not a finished task, it is a dropped reply.
            // Retrying once turns a silent stop into either progress or a clear error.
            if (toolCalls.isEmpty() && assistantText.isBlank() && reasoningText.isBlank()) {
                if (emptySteps < MAX_EMPTY_STEPS) {
                    emptySteps++
                    messages.add(
                        AgentMessage(
                            MessageRole.USER,
                            "Your last reply was empty. Continue the task from where you left off and " +
                                "finish it, or explain what is blocking you."
                        )
                    )
                    emit(AgentEvent.State(AgentState.REQUESTING_MODEL))
                    continue
                }
                emit(
                    AgentEvent.Failed(
                        "The model returned an empty reply twice in a row. Check the provider, then retry."
                    )
                )
                emit(AgentEvent.State(AgentState.FAILED))
                return@flow
            }

            if (toolCalls.isEmpty()) {
                // "length" means the provider cut the reply off at the output limit. The model was
                // still mid-thought, so the turn is not finished - asking it to continue is the
                // difference between a task that completes and one that stops half-way.
                if (isTruncated(finishReason) && nudges < MAX_NUDGES) {
                    nudges++
                    messages.add(
                        AgentMessage(
                            MessageRole.USER,
                            "Your last reply was cut off by the output limit. Continue from exactly " +
                                "where it stopped, without repeating what you already wrote."
                        )
                    )
                    emit(AgentEvent.State(AgentState.REQUESTING_MODEL))
                    continue
                }

                // A model that stops while its own task list still has open items is usually
                // stopping early, not finishing. Push it once or twice before believing it, which
                // is what stops a long task from ending half-way through.
                val open = latestTodos.filter { it.status != TodoStatus.COMPLETED }
                if (open.isNotEmpty() && nudges < MAX_NUDGES) {
                    nudges++
                    messages.add(
                        AgentMessage(
                            MessageRole.USER,
                            "You still have unfinished tasks: " +
                                open.joinToString("; ") { it.content } +
                                ". Continue working until every one of them is complete, then give " +
                                "a short final summary. Do not stop to ask for confirmation."
                        )
                    )
                    emit(AgentEvent.State(AgentState.REQUESTING_MODEL))
                    continue
                }

                // The task is finished: record what was done in the workspace memory. This is the
                // automatic half of the memory system — it does not depend on the model remembering
                // to call remember, so every task leaves a trail the next one can read. It is skipped
                // entirely when the user turned memory off.
                if (request.memoryEnabled) runCatching {
                    notes.appendTaskLog(
                        workspace = request.workspace,
                        request = request.userInput,
                        outcome = if (usedTools.isEmpty()) {
                            "answered"
                        } else {
                            "done · " + usedTools.joinToString(", ")
                        }
                    )
                }
                emit(AgentEvent.State(AgentState.COMPLETED))
                emit(AgentEvent.Completed(finishReason))
                return@flow
            }

            // Providers occasionally reuse an id within one step, or omit it. Two tool messages
            // with the same id make the follow-up request invalid, which surfaced as the turn
            // failing right after a successful edit. Make the ids unique before echoing them back.
            ensureUniqueToolCallIds(toolCalls)

            messages.add(AgentMessage(MessageRole.ASSISTANT, assistantText, toolCalls))

            // Partition by capability. Side-effect-free tools (read/search/fetch/git-inspect) run
            // concurrently; anything that can write, delete, run a command, or update the task list
            // runs in order through the approval flow (PRD §4.3 bullet 8). Results are re-ordered
            // into the original call order when they are added back to the conversation.
            val readOnlyCalls = toolCalls.filter { toolRegistry.get(it.name)?.isReadOnly == true }
            val gatedCalls = toolCalls.filter { toolRegistry.get(it.name)?.isReadOnly != true }
            val resultsById = LinkedHashMap<String, String>()

            // Announce every call up front so the transcript places them all under this step and
            // the parallel reads are visibly running together.
            for (call in toolCalls) {
                emit(AgentEvent.State(AgentState.VALIDATING_TOOL_CALL, call.name))
                if (toolRegistry.get(call.name) != null) usedTools.add(call.name)
                // Announced even for an unknown tool: the transcript needs a row to attach the
                // "unknown tool" result to, otherwise the failure is invisible and looks like the
                // agent quietly stopping.
                emit(
                    AgentEvent.ToolInvoked(
                        toolName = call.name,
                        argsRedacted = Redactor.redactJsonArgs(call.argumentsJson),
                        messageId = stepMessageId,
                        callId = call.id
                    )
                )
            }

            if (readOnlyCalls.isNotEmpty()) {
                emit(AgentEvent.State(AgentState.RUNNING_TOOL, readOnlyCalls.joinToString(", ") { it.name }))
                val computed: Map<String, ToolResult> = coroutineScope {
                    readOnlyCalls
                        .map { call ->
                            async {
                                val tool = toolRegistry.get(call.name)!!
                                // A throwing tool must not abort the whole turn (which left its row
                                // spinning): surface the failure as the call's result instead.
                                val parsed = runCatching {
                                    json.parseToJsonElement(call.argumentsJson).jsonObject
                                }
                                val result = parsed.fold(
                                    onSuccess = { args ->
                                        val repaired = ToolArgs.normalize(
                                            args,
                                            tool.schema().parametersJsonSchema
                                        )
                                        runCatching { tool.execute(repaired, toolContext) }
                                            .getOrElse { error ->
                                                ToolResult(
                                                    success = false,
                                                    output = "",
                                                    errorMessage = toolFailureMessage(call.name, error)
                                                )
                                            }
                                    },
                                    onFailure = { error ->
                                        ToolResult(
                                            success = false,
                                            output = "",
                                            errorMessage = badArgumentsMessage(call.name, error)
                                        )
                                    }
                                )
                                call.id to result
                            }
                        }
                        .awaitAll()
                        .toMap()
                }
                // Some read-only tools answer by proposing a read-only command instead of running
                // one: git_status and git_diff do exactly that. Only the gated branch below ever
                // executed a request, so the model used to receive the proposal text — "Proposed
                // git status." — and never the repository state it asked for.
                //
                // They run here, one at a time: every command shares the workspace's single shell,
                // so two concurrent runs would interleave their output and corrupt both.
                val inline = LinkedHashMap<String, Pair<CommandRequest, CommandResult>>()
                for (call in readOnlyCalls) {
                    val proposed = computed.getValue(call.id).commandRequest ?: continue
                    // A tool that declares itself read-only may only propose a read-only command;
                    // anything else belongs in the approval flow, not here.
                    if (proposed.impact != CommandImpact.READ_ONLY) continue
                    val outcome = runCatching { commandExecutor.run(proposed) }.getOrNull() ?: continue
                    inline[call.id] = proposed to outcome
                    emit(AgentEvent.CommandCompleted(proposed.command, outcome))
                }
                for (call in readOnlyCalls) {
                    val result = computed.getValue(call.id)
                    result.todos?.let { latestTodos = it; emit(AgentEvent.TodosUpdated(it)) }
                    val executed = inline[call.id]
                    val success = executed?.second?.succeeded ?: result.success
                    val text = when {
                        executed != null -> formatCommandResult(executed.first, executed.second)
                        result.success -> result.output
                        else -> result.errorMessage ?: "Tool failed."
                    }
                    resultsById[call.id] = text
                    emit(
                        AgentEvent.ToolResultEvent(
                            toolName = call.name,
                            success = success,
                            summary = text.take(400),
                            callId = call.id,
                            linesAdded = result.linesAdded,
                            linesRemoved = result.linesRemoved
                        )
                    )
                }
            }

            for (call in gatedCalls) {
                val tool = toolRegistry.get(call.name)
                if (tool == null) {
                    emit(AgentEvent.ToolResultEvent(call.name, false, "Unknown tool", call.id))
                    resultsById[call.id] = "Error: unknown tool '${call.name}'."
                    continue
                }

                // Malformed arguments used to be swallowed into an empty object, so the tool
                // answered "missing required argument" and the model repeated the same broken
                // call. Say what was wrong so it can correct itself.
                val parsedArgs = runCatching { json.parseToJsonElement(call.argumentsJson).jsonObject }
                if (parsedArgs.isFailure) {
                    val problem = badArgumentsMessage(call.name, parsedArgs.exceptionOrNull())
                    resultsById[call.id] = problem
                    emit(AgentEvent.ToolResultEvent(call.name, false, problem.take(400), call.id))
                    continue
                }
                // Repair the shape before the tool validates it: stringified arrays, extra
                // wrappers, and Claude-style argument names are all normalised here.
                val args = ToolArgs.normalize(
                    parsedArgs.getOrThrow(),
                    tool.schema().parametersJsonSchema
                )
                emit(AgentEvent.State(AgentState.RUNNING_TOOL, call.name))
                // A tool that throws used to abort the turn and leave its row spinning forever.
                val result = runCatching { tool.execute(args, toolContext) }.getOrElse { error ->
                    ToolResult(
                        success = false,
                        output = "",
                        errorMessage = toolFailureMessage(call.name, error)
                    )
                }
                result.todos?.let { latestTodos = it; emit(AgentEvent.TodosUpdated(it)) }
                // A memory note is a durable fact about this workspace: write it before the model
                // continues, so the same turn's later steps already see it.
                result.memoryNote?.let { note ->
                    runCatching { notes.remember(request.workspace, note) }
                }

                // Plan mode: the model's plan is reviewed before it is allowed to do any work. The
                // approved Markdown is persisted per workspace and becomes part of every later turn.
                val plan = result.plan
                if (result.requiresApproval && plan != null) {
                    emit(AgentEvent.State(AgentState.WAITING_FOR_APPROVAL, call.name))
                    emit(AgentEvent.PlanProposed(plan))
                    val decision = awaitPlan(request.sessionId)
                    if (decision.approved) {
                        runCatching { notes.savePlan(request.workspace, plan) }
                        emit(AgentEvent.PlanResolved(plan.id, true))
                        resultsById[call.id] =
                            "The user approved the plan and it is saved. Execute it now, step by step, " +
                                "and keep the task list in sync with it."
                        emit(AgentEvent.ToolResultEvent(call.name, true, "Plan approved", call.id))
                    } else {
                        emit(AgentEvent.PlanResolved(plan.id, false))
                        val feedback = decision.feedback?.trim().orEmpty()
                        resultsById[call.id] = if (feedback.isNotEmpty()) {
                            "The user did not approve the plan and asked for these changes: $feedback. " +
                                "Revise the plan and call propose_plan again before doing any work."
                        } else {
                            "The user rejected the plan. Do not start working; ask what they want instead."
                        }
                        emit(AgentEvent.ToolResultEvent(call.name, false, "Plan rejected", call.id))
                    }
                    continue
                }

                val resultText: String
                val proposal = result.proposal
                val commandRequest = result.commandRequest
                if (result.requiresApproval && commandRequest != null) {
                    // Commands: ASK_CHANGES always prompts; permissive modes auto-run, EXCEPT that a
                    // DESTRUCTIVE command (or one flagged alwaysApprove, e.g. a Git commit) never runs
                    // without explicit approval (PRD FR-017).
                    val autoApprove = !commandRequest.alwaysApprove &&
                        request.permissionMode != PermissionMode.ASK_CHANGES &&
                        commandRequest.impact != CommandImpact.DESTRUCTIVE
                    if (!autoApprove) {
                        emit(AgentEvent.State(AgentState.WAITING_FOR_APPROVAL, call.name))
                        emit(
                            AgentEvent.CommandApprovalRequired(
                                commandRequest,
                                commandRequest.reason ?: args.stringOrNull("reason")
                            )
                        )
                    }
                    val decision = if (autoApprove) ApprovalDecision(true) else awaitApproval(request.sessionId)
                    if (decision.approved) {
                        emit(AgentEvent.State(AgentState.RUNNING_TOOL, call.name))
                        // Stream the command's output as it is produced so a long build shows
                        // progress live instead of a frozen spinner (PRD §4.3 bullet 8). The
                        // callback runs on the executor's own thread, so chunks are funnelled
                        // through a channel and emitted from this coroutine, never concurrently.
                        val commandResult = coroutineScope {
                            val chunks = Channel<String>(Channel.UNLIMITED)
                            val job = async {
                                try {
                                    commandExecutor.run(commandRequest) { chunk ->
                                        chunks.trySend(chunk)
                                    }
                                } finally {
                                    chunks.close()
                                }
                            }
                            for (chunk in chunks) {
                                emit(AgentEvent.CommandOutput(commandRequest.command, chunk))
                            }
                            job.await()
                        }
                        emit(AgentEvent.CommandCompleted(commandRequest.command, commandResult))
                        resultText = formatCommandResult(commandRequest, commandResult)
                        emit(
                            AgentEvent.ToolResultEvent(
                                call.name,
                                commandResult.succeeded,
                                resultText.take(400),
                                call.id
                            )
                        )
                    } else {
                        resultText = "The user rejected running the command. Do not re-propose it unless asked."
                        emit(AgentEvent.ToolResultEvent(call.name, false, "Rejected by user", call.id))
                    }
                } else if (result.requiresApproval && proposal != null) {
                    // AUTO_EDIT / FULL_ACCESS apply file edits without waiting for the user; ASK_CHANGES
                    // pauses and emits an ApprovalRequired event for the UI to resolve.
                    val autoApprove = request.permissionMode != PermissionMode.ASK_CHANGES
                    if (!autoApprove) {
                        emit(AgentEvent.State(AgentState.WAITING_FOR_APPROVAL, call.name))
                        emit(AgentEvent.ApprovalRequired(proposal, args.stringOrNull("reason")))
                    }
                    val decision = if (autoApprove) ApprovalDecision(true) else awaitApproval(request.sessionId)
                    // A multi-file patch can be approved one file at a time; apply only what was accepted.
                    val selected = decision.acceptedPaths
                        ?.let { proposal.only(it) }
                        ?: proposal
                    val rejected = proposal.files.map { it.path } - selected.files.map { it.path }.toSet()
                    if (decision.approved && selected.files.isNotEmpty()) {
                        val applyResult = patchApplier.apply(selected, request.adapter)
                        emit(AgentEvent.PatchApplied(selected.id, applyResult))
                        val rejectionNote = if (rejected.isEmpty()) {
                            ""
                        } else {
                            " The user rejected these files, so they were left unchanged: " +
                                rejected.joinToString(", ") + "."
                        }
                        resultText = if (applyResult.allApplied) {
                            val prefix = if (autoApprove) {
                                "Applied automatically under the current permission mode. "
                            } else {
                                "The user approved and the change was applied. "
                            }
                            prefix + summarizePatch(selected) + rejectionNote
                        } else {
                            "The change was approved, but applying failed: " +
                                applyResult.statuses.joinToString("; ") { "${it.path}: ${it.message}" }
                        }
                        // The +N / -M badge comes from these two fields, and an applied change used
                        // to be reported without them, so every edit row showed no diff stats.
                        emit(
                            AgentEvent.ToolResultEvent(
                                toolName = call.name,
                                success = applyResult.allApplied,
                                summary = resultText,
                                callId = call.id,
                                linesAdded = selected.totalInsertions,
                                linesRemoved = selected.totalDeletions
                            )
                        )
                    } else {
                        resultText = "The user rejected the proposed change. Do not re-propose it unless asked."
                        emit(AgentEvent.ToolResultEvent(call.name, false, "Rejected by user", call.id))
                    }
                } else {
                    resultText = if (result.success) result.output else (result.errorMessage ?: "Tool failed.")
                    emit(
                        AgentEvent.ToolResultEvent(
                            toolName = call.name,
                            success = result.success,
                            summary = resultText.take(400),
                            callId = call.id,
                            linesAdded = result.linesAdded,
                            linesRemoved = result.linesRemoved
                        )
                    )
                }

                resultsById[call.id] = resultText
            }

            // Restore the model's own call order for the tool-result messages it will read next.
            // A tool that legitimately produced nothing (an empty file, a command with no output, a
            // search with no matches) must still send a non-empty body: several OpenAI-compatible
            // providers answer an empty tool message with an empty completion, which looked like
            // the agent silently stopping mid-task.
            for (call in toolCalls) {
                val result = resultsById[call.id]
                messages.add(
                    AgentMessage(
                        role = MessageRole.TOOL,
                        content = if (result.isNullOrBlank()) NO_TOOL_OUTPUT else result,
                        toolCallId = call.id,
                        name = call.name
                    )
                )
            }
            steps++
        }

        // Hitting the safety ceiling is not a failure: the work done so far is real and the user
        // can continue from it. Report it as a normal stop with an explanation.
        emit(
            AgentEvent.AssistantMessage(
                messageId = UUID.randomUUID().toString(),
                text = "Stopped after $maxSteps tool steps, the configured limit for this turn. " +
                    "The work so far is saved - send another message to continue.",
                status = MessageStatus.COMPLETE
            )
        )
        emit(AgentEvent.State(AgentState.COMPLETED))
        emit(AgentEvent.Completed("step_limit"))
    }.flowOn(dispatchers.io)

    /**
     * Elides the bodies of old tool results once the replayed conversation grows too large.
     *
     * Tool output is the bulk of a long turn's context, and it is the part that is cheapest to
     * recover (re-run the read or search). Clearing it keeps the assistant/tool call structure
     * intact, unlike dropping messages, which would leave orphaned tool calls the provider rejects.
     */
    private fun elideOldToolResults(messages: MutableList<AgentMessage>): Boolean {
        val chars = messages.sumOf { it.content.length }
        if (chars <= CONTEXT_ELIDE_CHARS) return false
        val toolIndexes = messages.indices.filter { messages[it].role == MessageRole.TOOL }
        if (toolIndexes.size <= KEEP_TOOL_RESULTS) return false
        var changed = false
        for (index in toolIndexes.dropLast(KEEP_TOOL_RESULTS)) {
            val message = messages[index]
            if (message.content.length <= ELIDED_MAX_CHARS) continue
            messages[index] = message.copy(
                content = "[elided " + message.content.length +
                    " chars of earlier tool output to stay inside the context window; " +
                    "re-run the tool if you need the detail]"
            )
            changed = true
        }
        return changed
    }

    /**
     * Tool call ids must be unique inside a step: the assistant message echoes them and each tool
     * message answers by id. A provider that repeats an id, or omits it, produced a follow-up
     * request the API rejected - which looked like the agent dying right after a good edit.
     */
    private fun ensureUniqueToolCallIds(calls: MutableList<ToolCall>) {
        val seen = HashSet<String>(calls.size)
        for (index in calls.indices) {
            val call = calls[index]
            if (call.id.isNotBlank() && seen.add(call.id)) continue
            val replacement = "call_" + index + "_" + UUID.randomUUID().toString().take(8)
            seen.add(replacement)
            calls[index] = call.copy(id = replacement)
        }
    }

    /** A one-line description of an applied change, with the diff size the transcript shows. */
    private fun summarizePatch(proposal: PatchProposal): String {
        val files = proposal.files.joinToString(", ") { it.path }
        return "Changed " + files + " (+" + proposal.totalInsertions +
            " / -" + proposal.totalDeletions + ")."
    }

    /** True when the provider stopped because the reply hit the output-token ceiling. */
    private fun isTruncated(finishReason: String?): Boolean =
        finishReason == "length" || finishReason == "max_tokens"

    /** What to tell the model when its arguments were not a JSON object at all. */
    private fun badArgumentsMessage(name: String, error: Throwable?): String =
        "The arguments for '" + name + "' were not a valid JSON object (" +
            (error?.message ?: "parse error") +
            "). Re-send the call with a valid JSON object."

    /** A tool that threw still owes the model an explanation it can act on, not a bare class name. */
    private fun toolFailureMessage(name: String, error: Throwable): String {
        val detail = error.message?.takeIf { it.isNotBlank() }
            ?: error::class.simpleName
            ?: "unknown error"
        return "'" + name + "' failed: " + detail + ". Adjust the arguments or try another approach."
    }

    private suspend fun awaitApproval(sessionId: String): ApprovalDecision {
        val deferred = CompletableDeferred<ApprovalDecision>()
        pendingApprovals[sessionId] = deferred
        return try {
            deferred.await()
        } finally {
            pendingApprovals.remove(sessionId)
        }
    }

    private suspend fun awaitPlan(sessionId: String): PlanDecision {
        val deferred = CompletableDeferred<PlanDecision>()
        pendingPlans[sessionId] = deferred
        return try {
            deferred.await()
        } finally {
            pendingPlans.remove(sessionId)
        }
    }

    private fun formatCommandResult(request: CommandRequest, result: CommandResult): String {
        // A background job has not finished, so it has no exit code to report. Saying "exited with
        // code null" would invite the model to re-run the command it just started.
        if (result.running) {
            return buildString {
                appendLine(result.output)
                appendLine()
                appendLine(
                    "Keep working on other steps, then call background_wait(id = \"" + result.jobId +
                        "\") to get the exit code and the end of its output. background_output reads " +
                        "the output so far, background_status checks whether it is still running, " +
                        "and background_stop cancels it. Do not start the same command again while " +
                        "the job runs."
                )
            }.trimEnd()
        }
        // output_policy = "stream" promises the user a live view and the model no copy. The text
        // was handed over anyway, so the two policies behaved identically.
        if (request.outputPolicy == OutputPolicy.STREAM) {
            val header = when {
                result.cancelled -> "The command was cancelled and killed."
                else -> "The command exited with code " + result.exitCode + "."
            }
            return header + "\n\nIts output was streamed to the user and not captured, so you " +
                "have not seen it. Re-run with output_policy set to capture if you need the text."
        }
        val header = when {
            result.cancelled -> "The command was cancelled and killed."
            else -> "The command exited with code " + result.exitCode + "."
        }
        // Build/test/lint logs are mostly progress noise; keep the actionable lines instead. Both
        // paths keep the head *and* the tail: a failing install or build prints its error last, so
        // cutting to the first N characters handed the model the one part that explains nothing.
        val body = if (request.summarize) {
            OutputWindow.headTail(CommandOutputSummarizer.summarize(result.output), 6_000)
        } else {
            OutputWindow.headTail(result.output, 4_000)
        }
        val truncatedNotice = if (result.truncated) "\n[output truncated]" else ""
        val text = if (body.isBlank()) header + truncatedNotice else header + "\n" + body + truncatedNotice
        return if (result.cancelled) text + "\n\n" + CommandRecovery.killedNote(request.command) else text
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}
