package com.aliedu.kern.feature.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliedu.kern.core.agent.AgentEvent
import com.aliedu.kern.core.agent.AgentOrchestrator
import com.aliedu.kern.core.agent.AgentState
import com.aliedu.kern.core.agent.TurnRequest
import com.aliedu.kern.core.data.preferences.SettingsRepository
import com.aliedu.kern.core.data.repository.MessageRepository
import com.aliedu.kern.core.data.repository.ProviderRepository
import com.aliedu.kern.core.data.repository.SessionRepository
import com.aliedu.kern.core.data.repository.TodoRepository
import com.aliedu.kern.core.data.repository.ToolEventRepository
import com.aliedu.kern.core.data.repository.WorkspaceRepository
import com.aliedu.kern.core.data.security.CredentialStore
import com.aliedu.kern.core.model.FileKind
import com.aliedu.kern.core.model.Message
import com.aliedu.kern.core.model.MessageRole
import com.aliedu.kern.core.model.MessageStatus
import com.aliedu.kern.core.model.PermissionMode
import com.aliedu.kern.core.model.ProviderProfile
import com.aliedu.kern.core.model.Session
import com.aliedu.kern.core.model.SessionStatus
import com.aliedu.kern.core.model.Todo
import com.aliedu.kern.core.model.ToolEvent
import com.aliedu.kern.core.model.ToolEventStatus
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.providers.api.AiProviderAdapter
import com.aliedu.kern.core.providers.api.ConnectionResult
import com.aliedu.kern.core.tools.CommandRequest
import com.aliedu.kern.core.tools.PatchProposal
import com.aliedu.kern.core.tools.PlanProposal
import com.aliedu.kern.core.tools.devserver.DevServer
import com.aliedu.kern.core.tools.devserver.DevServerRegistry
import com.aliedu.kern.core.tools.devserver.DevServerState
import com.aliedu.kern.core.workspace.WorkspaceAdapter
import com.aliedu.kern.core.workspace.WorkspaceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/** One selectable model, with the provider profile that serves it. */
data class ModelOption(
    val profileId: String,
    val provider: String,
    val modelId: String
) {
    /** Key for per-row progress: two providers can advertise the same model id. */
    val key: String get() = "$profileId|$modelId"
}

data class AgentUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val workspace: Workspace? = null,
    val sessionId: String? = null,
    val messages: List<Message> = emptyList(),
    val toolEvents: List<ToolEvent> = emptyList(),
    val streamingText: String = "",
    val reasoningText: String = "",
    val agentState: AgentState = AgentState.IDLE,
    val runningTool: String? = null,
    val pendingApproval: PatchProposal? = null,
    val pendingApprovalReason: String? = null,
    /** A shell command awaiting approval (PRD FR-017). */
    val pendingCommand: CommandRequest? = null,
    val pendingCommandReason: String? = null,
    /** A plan the agent proposed and is waiting for the user to approve or revise. */
    val pendingPlan: PlanProposal? = null,
    val providerLabel: String? = null,
    val noProvider: Boolean = false,
    val error: String? = null,
    val lastOutboundContext: String? = null,
    /** Chat appearance/behaviour preferences mirrored from Settings so the screen reacts live. */
    val chatFontSizeSp: Float = 12f,
    val showReasoning: Boolean = true,
    val autoScroll: Boolean = true,
    val permissionMode: PermissionMode = PermissionMode.ASK_CHANGES,
    /** Provider profiles offered by the composer's model picker. */
    val profiles: List<ProviderProfile> = emptyList(),
    val activeProfileId: String? = null,
    /** Id of a profile currently being connection-tested from the picker, plus the last failure. */
    val modelTestingId: String? = null,
    val modelTestError: String? = null,
    /** Every model the configured providers advertise on `BASE_URL/models`. */
    val models: List<ModelOption> = emptyList(),
    val modelsLoading: Boolean = false,
    /** Providers whose model list could not be fetched, one line each. */
    val modelsError: String? = null,
    /** Flat, capped list of workspace file paths offered as '@' mentions in the composer. */
    val mentionFiles: List<String> = emptyList(),
    /** The agent's task list for this session (FR-018), shown in the composer's Tasks chip. */
    val todos: List<Todo> = emptyList(),
    /** The command currently running, with its live output tail, while a tool is executing. */
    val liveCommand: String? = null,
    val liveCommandOutput: String = "",
    /** True when the last turn failed or was interrupted and there is a request to re-run. */
    val canRetry: Boolean = false,
    /**
     * Servers the agent started that are accepting connections. Non-empty is what puts the preview
     * icon in the app bar (PRD §4.3 bullet 8).
     */
    val runningServers: List<DevServer> = emptyList()
) {
    val isBusy: Boolean get() = agentState == AgentState.REQUESTING_MODEL ||
        agentState == AgentState.ASSISTANT_RESPONSE ||
        agentState == AgentState.RUNNING_TOOL ||
        agentState == AgentState.VALIDATING_TOOL_CALL ||
        agentState == AgentState.PREPARING_CONTEXT
}

@HiltViewModel
class AgentViewModel @Inject constructor(
    private val orchestrator: AgentOrchestrator,
    private val workspaceRepository: WorkspaceRepository,
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val toolEventRepository: ToolEventRepository,
    private val todoRepository: TodoRepository,
    private val providerRepository: ProviderRepository,
    private val credentialStore: CredentialStore,
    private val settingsRepository: SettingsRepository,
    private val providerAdapter: AiProviderAdapter,
    private val workspaceManager: WorkspaceManager,
    private val devServers: DevServerRegistry,
    private val turnNotifier: AgentTurnNotifier,
    private val sessionStore: AgentSessionStore
) : ViewModel() {

    // The turn state lives in [sessionStore] so it survives this ViewModel being cleared when the
    // user navigates away; the getter keeps every existing `_state.value` write unchanged.
    private val _state: MutableStateFlow<AgentUiState> get() = sessionStore.state
    val state: StateFlow<AgentUiState> get() = sessionStore.state.asStateFlow()

    private var adapter: WorkspaceAdapter? = null
    private var activeProfile: ProviderProfile? = null
    private var turnJob: Job?
        get() = sessionStore.turnJob
        set(value) {
            sessionStore.turnJob = value
        }
    private var messagesJob: Job? = null
    private var toolEventsJob: Job? = null
    private var todosJob: Job? = null

    // Tool events are closed out by id rather than by re-reading the observer's list, so a slow
    // Room emission can never leave a tool row stuck on its spinner.
    private val runningToolIds = mutableMapOf<String, String>()
    private val runningToolStartedAt = mutableMapOf<String, Long>()

    init {
        viewModelScope.launch {
            settingsRepository.lastWorkspaceId.collect { id ->
                val workspace = id?.let { workspaceRepository.getWorkspace(it) }
                adapter = workspace?.let { runCatching { workspaceManager.adapterFor(it) }.getOrNull() }
                _state.value = _state.value.copy(loading = false, workspace = workspace)
                adapter?.let { loadMentionFiles(it) }
            }
        }
        viewModelScope.launch {
            providerRepository.observeDefaultProfile().collect { profile ->
                activeProfile = profile
                _state.value = _state.value.copy(
                    providerLabel = profile?.modelId,
                    noProvider = profile == null,
                    activeProfileId = profile?.id
                )
            }
        }
        viewModelScope.launch {
            providerRepository.observeProfiles().collect { list ->
                _state.value = _state.value.copy(profiles = list)
            }
        }
        viewModelScope.launch {
            settingsRepository.permissionMode.collect { mode ->
                _state.value = _state.value.copy(permissionMode = mode)
            }
        }
        viewModelScope.launch {
            combine(
                settingsRepository.chatFontSizeSp,
                settingsRepository.showReasoning,
                settingsRepository.autoScroll
            ) { fontSize, reasoning, autoScroll -> Triple(fontSize, reasoning, autoScroll) }
                .collect { (fontSize, reasoning, autoScroll) ->
                    _state.value = _state.value.copy(
                        chatFontSizeSp = fontSize,
                        showReasoning = reasoning,
                        autoScroll = autoScroll
                    )
                }
        }
        viewModelScope.launch {
            devServers.servers.collect { servers ->
                _state.value = _state.value.copy(
                    runningServers = servers.filter { it.state == DevServerState.RUNNING && it.url != null }
                )
            }
        }
    }

    fun sendMessage(text: String) {
        val input = text.trim()
        if (input.isEmpty()) return
        val workspace = _state.value.workspace
        val profile = activeProfile
        val wsAdapter = adapter
        if (workspace == null || wsAdapter == null) {
            _state.value = _state.value.copy(error = "Select a workspace first.")
            return
        }
        if (profile == null) {
            _state.value = _state.value.copy(error = "Configure an AI provider first.")
            return
        }

        turnJob = sessionStore.scope.launch {
            val sessionId = ensureSession(workspace, profile, input)
            val history = messageRepository.let { repo ->
                // Snapshot current history before appending the new user message.
                _state.value.messages.filter { it.sessionId == sessionId }
            }

            messageRepository.append(
                Message(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = MessageRole.USER,
                    content = input,
                    status = MessageStatus.COMPLETE,
                    providerMessageId = null,
                    createdAt = System.currentTimeMillis(),
                    seq = messageRepository.nextSeq(sessionId)
                )
            )
            sessionRepository.updateStatus(sessionId, SessionStatus.ACTIVE, System.currentTimeMillis())

            runTurn(sessionId, workspace, profile, input, history)
        }
    }

    /**
     * Re-runs the last user request without appending a new one (PRD §4.3 bullet 1). The persisted
     * task list is fed back into context, so an interrupted turn resumes its plan rather than
     * restarting from scratch.
     */
    fun retryLastTurn() {
        val sessionId = _state.value.sessionId ?: return
        val workspace = _state.value.workspace ?: return
        val profile = activeProfile ?: return
        val wsAdapter = adapter ?: return
        if (_state.value.isBusy) return

        val messages = _state.value.messages.filter { it.sessionId == sessionId }
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex < 0) return
        val input = messages[lastUserIndex].content
        // History before the request being retried; the failed/cancelled reply is dropped.
        val history = messages.take(lastUserIndex)

        turnJob = sessionStore.scope.launch {
            sessionRepository.updateStatus(sessionId, SessionStatus.ACTIVE, System.currentTimeMillis())
            _state.value = _state.value.copy(canRetry = false, error = null)
            runTurn(sessionId, workspace, profile, input, history)
        }
    }

    /** Streams one turn and persists its terminal state. Shared by send and retry. */
    private suspend fun runTurn(
        sessionId: String,
        workspace: Workspace,
        profile: ProviderProfile,
        input: String,
        history: List<Message>
    ) {
        val wsAdapter = adapter ?: return
        val apiKey = credentialStore.getApiKey(profile.id)

        _state.value = _state.value.copy(
            streamingText = "",
            reasoningText = "",
            error = null,
            canRetry = false,
            lastOutboundContext = "Sending to ${profile.displayName} (${profile.modelId}). " +
                "Workspace files are read on demand by tools; no files are uploaded automatically."
        )

        val partial = StringBuilder()
        // [partial] is reset every time a message is committed, so it is empty by the time the turn
        // ends. Keep the last committed reply separately for the "task finished" notification.
        val lastReply = StringBuilder()
        // Promote to the foreground for the whole turn: Android otherwise freezes the process when
        // the user switches apps, which used to kill the provider stream half-way through a task.
        runCatching { turnNotifier.onTurnStarted(workspace.displayName) }
        try {
            orchestrator.runTurn(
                TurnRequest(
                    sessionId = sessionId,
                    workspace = workspace,
                    adapter = wsAdapter,
                    profile = profile,
                    apiKey = apiKey,
                    history = history,
                    userInput = input,
                    permissionMode = _state.value.permissionMode,
                    todos = _state.value.todos
                )
            ).collect { event -> handleEvent(sessionId, event, partial, lastReply) }
            // The turn ran to completion (not cancelled, not failed): the user may have left the
            // screen while it worked, so tell them it is done. Never let a notification failure
            // turn a finished turn into a failed one.
            runCatching {
                turnNotifier.onTurnFinished(
                    workspaceName = workspace.displayName,
                    reply = lastReply.toString().ifBlank { partial.toString() }
                )
            }
        } catch (cancel: CancellationException) {
            // Update the UI first: once this job is cancelled, any suspend call below would
            // immediately re-throw CancellationException, so the state change must not wait
            // on persistence. Persistence runs in a NonCancellable block.
            _state.value = _state.value.copy(
                agentState = AgentState.CANCELLED,
                streamingText = "",
                canRetry = true,
                liveCommand = null,
                liveCommandOutput = ""
            )
            withContext(NonCancellable) {
                persistAssistant(sessionId, partial.toString(), MessageStatus.CANCELLED)
                sessionRepository.updateStatus(
                    sessionId,
                    SessionStatus.INTERRUPTED,
                    System.currentTimeMillis()
                )
            }
            throw cancel
        } catch (t: Throwable) {
            persistAssistant(sessionId, partial.toString(), MessageStatus.FAILED)
            _state.value = _state.value.copy(
                agentState = AgentState.FAILED,
                error = t.message ?: "The agent failed.",
                streamingText = "",
                canRetry = true,
                liveCommand = null,
                liveCommandOutput = ""
            )
        } finally {
            runCatching { turnNotifier.onTurnStopped() }
        }
    }

    private suspend fun handleEvent(
        sessionId: String,
        event: AgentEvent,
        partial: StringBuilder,
        lastReply: StringBuilder
    ) {
        when (event) {
            is AgentEvent.State -> {
                _state.value = _state.value.copy(
                    agentState = event.state,
                    runningTool = if (event.state == AgentState.RUNNING_TOOL) event.detail else null
                )
            }

            is AgentEvent.Delta -> {
                partial.append(event.text)
                _state.value = _state.value.copy(streamingText = partial.toString())
            }

            is AgentEvent.ReasoningDelta -> {
                _state.value = _state.value.copy(
                    reasoningText = _state.value.reasoningText + event.text
                )
            }

            is AgentEvent.AssistantMessage -> {
                persistAssistant(
                    sessionId = sessionId,
                    text = event.text,
                    status = MessageStatus.COMPLETE,
                    reasoning = event.reasoning,
                    messageId = event.messageId
                )
                lastReply.setLength(0)
                lastReply.append(event.text)
                partial.setLength(0)
                _state.value = _state.value.copy(streamingText = "", reasoningText = "")
            }

            is AgentEvent.ToolInvoked -> {
                val id = UUID.randomUUID().toString()
                // Key by the model's call id: one step can call the same tool twice, and keying by
                // name closed only the last of them, leaving the others spinning forever.
                val key = event.callId ?: event.toolName
                runningToolIds[key] = id
                runningToolStartedAt[key] = System.currentTimeMillis()
                toolEventRepository.upsert(
                    ToolEvent(
                        id = id,
                        sessionId = sessionId,
                        messageId = event.messageId,
                        toolName = event.toolName,
                        argsRedacted = event.argsRedacted,
                        status = ToolEventStatus.RUNNING,
                        resultSummary = null,
                        durationMs = null,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }

            is AgentEvent.ToolResultEvent -> {
                // Close out the event we opened for this call so the transcript stops its spinner.
                val key = event.callId ?: event.toolName
                val id = runningToolIds.remove(key)
                val started = runningToolStartedAt.remove(key)
                if (id != null) {
                    toolEventRepository.updateResult(
                        id = id,
                        status = if (event.success) ToolEventStatus.COMPLETED
                        else ToolEventStatus.FAILED,
                        summary = event.summary,
                        durationMs = started?.let { System.currentTimeMillis() - it },
                        linesAdded = event.linesAdded,
                        linesRemoved = event.linesRemoved
                    )
                }
            }

            is AgentEvent.TodosUpdated -> {
                todoRepository.replaceForSession(sessionId, event.todos)
            }

            is AgentEvent.ApprovalRequired -> {
                _state.value = _state.value.copy(
                    pendingApproval = event.proposal,
                    pendingApprovalReason = event.reason
                )
            }

            is AgentEvent.PatchApplied -> {
                _state.value = _state.value.copy(pendingApproval = null, pendingApprovalReason = null)
            }

            is AgentEvent.CommandApprovalRequired -> {
                _state.value = _state.value.copy(
                    pendingCommand = event.request,
                    pendingCommandReason = event.reason
                )
            }

            is AgentEvent.PlanProposed -> {
                _state.value = _state.value.copy(pendingPlan = event.plan)
            }

            is AgentEvent.PlanResolved -> {
                _state.value = _state.value.copy(pendingPlan = null)
            }

            is AgentEvent.CommandCompleted -> {
                _state.value = _state.value.copy(
                    pendingCommand = null,
                    pendingCommandReason = null,
                    liveCommand = null,
                    liveCommandOutput = ""
                )
            }

            is AgentEvent.CommandOutput -> {
                // Keep only the tail: a build can print megabytes, and the panel scrolls to the end.
                val merged = (_state.value.liveCommandOutput + event.chunk).takeLast(LIVE_OUTPUT_CHARS)
                _state.value = _state.value.copy(
                    liveCommand = event.command,
                    liveCommandOutput = merged
                )
            }

            is AgentEvent.Failed -> {
                _state.value = _state.value.copy(
                    error = event.message,
                    agentState = AgentState.FAILED,
                    canRetry = true
                )
            }

            is AgentEvent.Completed -> {
                _state.value = _state.value.copy(canRetry = false)
                sessionRepository.updateStatus(sessionId, SessionStatus.COMPLETED, System.currentTimeMillis())
            }
        }
    }

    private suspend fun persistAssistant(
        sessionId: String,
        text: String,
        status: MessageStatus,
        reasoning: String? = null,
        messageId: String? = null
    ) {
        if (text.isBlank() && reasoning.isNullOrBlank() && status != MessageStatus.COMPLETE) return
        messageRepository.append(
            Message(
                // Reuse the orchestrator's step id so tool events (keyed by the same id) anchor inline.
                id = messageId ?: UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = text,
                status = status,
                providerMessageId = null,
                createdAt = System.currentTimeMillis(),
                seq = messageRepository.nextSeq(sessionId),
                reasoning = reasoning
            )
        )
    }

    private suspend fun ensureSession(
        workspace: Workspace,
        profile: ProviderProfile,
        firstInput: String
    ): String {
        val existing = _state.value.sessionId
        if (existing != null) return existing
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        sessionRepository.upsert(
            Session(
                id = id,
                workspaceId = workspace.id,
                providerProfileId = profile.id,
                title = firstInput.take(60),
                status = SessionStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
                lastEventAt = now
            )
        )
        _state.value = _state.value.copy(sessionId = id)
        observeSession(id)
        return id
    }

    private fun observeSession(sessionId: String) {
        messagesJob?.cancel()
        toolEventsJob?.cancel()
        todosJob?.cancel()
        messagesJob = viewModelScope.launch {
            messageRepository.observeMessages(sessionId).collect { list ->
                _state.value = _state.value.copy(messages = list)
            }
        }
        toolEventsJob = viewModelScope.launch {
            toolEventRepository.observeToolEvents(sessionId).collect { list ->
                _state.value = _state.value.copy(toolEvents = list)
            }
        }
        todosJob = viewModelScope.launch {
            todoRepository.observeTodos(sessionId).collect { list ->
                _state.value = _state.value.copy(todos = list)
            }
        }
    }

    fun approvePendingChange() {
        val sessionId = _state.value.sessionId ?: return
        _state.value = _state.value.copy(
            pendingApproval = null,
            pendingApprovalReason = null,
            pendingCommand = null,
            pendingCommandReason = null
        )
        orchestrator.resolveApproval(sessionId, true)
    }

    /**
     * Approves only the ticked files of a multi-file change. When every file is selected this is the
     * same as [approvePendingChange]; otherwise the orchestrator applies just the accepted subset.
     */
    fun approvePendingChange(acceptedPaths: Set<String>) {
        val sessionId = _state.value.sessionId ?: return
        val proposal = _state.value.pendingApproval
        if (proposal != null && acceptedPaths.size < proposal.files.size) {
            _state.value = _state.value.copy(
                pendingApproval = null,
                pendingApprovalReason = null
            )
            orchestrator.resolveApproval(
                sessionId,
                AgentOrchestrator.ApprovalDecision(approved = true, acceptedPaths = acceptedPaths)
            )
            return
        }
        approvePendingChange()
    }

    fun rejectPendingChange() {
        val sessionId = _state.value.sessionId ?: return
        _state.value = _state.value.copy(
            pendingApproval = null,
            pendingApprovalReason = null,
            pendingCommand = null,
            pendingCommandReason = null
        )
        orchestrator.resolveApproval(sessionId, false)
    }

    /** Approves the proposed plan; the orchestrator saves it and the agent starts executing. */
    fun approvePlan() {
        val sessionId = _state.value.sessionId ?: return
        _state.value = _state.value.copy(pendingPlan = null)
        orchestrator.resolvePlan(sessionId, approved = true)
    }

    /** Sends the plan back with the user's changes; the agent revises it and proposes again. */
    fun requestPlanChanges(feedback: String) {
        val sessionId = _state.value.sessionId ?: return
        val trimmed = feedback.trim()
        _state.value = _state.value.copy(pendingPlan = null)
        orchestrator.resolvePlan(sessionId, approved = false, feedback = trimmed.ifEmpty { null })
    }

    /** Rejects the plan outright; the agent must ask what to do instead. */
    fun rejectPlan() {
        val sessionId = _state.value.sessionId ?: return
        _state.value = _state.value.copy(pendingPlan = null)
        orchestrator.resolvePlan(sessionId, approved = false)
    }

    fun cancel() {
        turnJob?.cancel()
        val sessionId = _state.value.sessionId
        if (sessionId != null) {
            orchestrator.resolveApproval(sessionId, false)
            orchestrator.resolvePlan(sessionId, approved = false)
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun setPermissionMode(mode: PermissionMode) {
        _state.value = _state.value.copy(permissionMode = mode)
        viewModelScope.launch { settingsRepository.setPermissionMode(mode) }
    }

    /** Enables/disables showing the model's reasoning (chain of thought) for this and future turns. */
    fun setThinking(enabled: Boolean) {
        _state.value = _state.value.copy(showReasoning = enabled)
        viewModelScope.launch { settingsRepository.setShowReasoning(enabled) }
    }

    fun clearModelTestError() {
        _state.value = _state.value.copy(modelTestError = null)
    }

    /**
     * Fetches `BASE_URL/models` for every configured provider so the picker can offer the whole
     * catalogue instead of one model per profile. A provider that fails is reported by name and does
     * not hide the models the others returned.
     */
    fun loadModels() {
        if (_state.value.modelsLoading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(modelsLoading = true, modelsError = null)
            val profiles = _state.value.profiles
            val collected = ArrayList<ModelOption>()
            val failures = ArrayList<String>()
            for (profile in profiles) {
                val key = credentialStore.getApiKey(profile.id)
                providerAdapter.listModels(profile, key)
                    .onSuccess { list ->
                        list.forEach { info ->
                            collected.add(ModelOption(profile.id, profile.displayName, info.id))
                        }
                    }
                    .onFailure { error ->
                        failures.add("${profile.displayName}: ${error.message ?: "could not list models"}")
                    }
            }
            _state.value = _state.value.copy(
                models = collected
                    .distinctBy { it.key }
                    .sortedWith(compareBy({ it.provider.lowercase() }, { it.modelId.lowercase() })),
                modelsLoading = false,
                modelsError = failures.takeIf { it.isNotEmpty() }?.joinToString("\n")
            )
        }
    }

    /**
     * Selects [modelId] from [profileId]. The connection is tested with that exact model first, so a
     * model the provider advertises but cannot serve never becomes the active one.
     */
    fun selectModel(profileId: String, modelId: String) {
        val profile = _state.value.profiles.firstOrNull { it.id == profileId } ?: return
        val candidate = if (profile.modelId == modelId) profile else profile.copy(modelId = modelId)
        viewModelScope.launch {
            _state.value = _state.value.copy(modelTestingId = "$profileId|$modelId", modelTestError = null)
            val key = credentialStore.getApiKey(candidate.id)
            val result = runCatching { providerAdapter.testConnection(candidate, key) }
                .getOrElse { ConnectionResult(false, it.message ?: "Connection failed.", 0L) }
            if (result.success) {
                if (candidate != profile) providerRepository.upsert(candidate)
                providerRepository.setDefault(candidate.id)
                _state.value = _state.value.copy(modelTestingId = null, modelTestError = null)
            } else {
                _state.value = _state.value.copy(
                    modelTestingId = null,
                    modelTestError = "${candidate.displayName} · $modelId — model not available: ${result.message}"
                )
            }
        }
    }

    /**
     * Tests the connection to a provider profile *before* selecting it. On success the profile becomes
     * the active default; on failure it surfaces a "not available" message and leaves selection alone.
     */
    fun selectModel(profileId: String) {
        val profile = _state.value.profiles.firstOrNull { it.id == profileId } ?: return
        if (profileId == _state.value.activeProfileId) return
        viewModelScope.launch {
            _state.value = _state.value.copy(modelTestingId = profileId, modelTestError = null)
            val key = credentialStore.getApiKey(profile.id)
            val result = runCatching { providerAdapter.testConnection(profile, key) }
                .getOrElse { ConnectionResult(false, it.message ?: "Connection failed.", 0L) }
            if (result.success) {
                providerRepository.setDefault(profileId)
                _state.value = _state.value.copy(modelTestingId = null, modelTestError = null)
            } else {
                _state.value = _state.value.copy(
                    modelTestingId = null,
                    modelTestError = "${profile.displayName} · ${profile.modelId} — model not available: ${result.message}"
                )
            }
        }
    }

    /** Recursively lists workspace files (capped) so the composer can offer '@' mentions. */
    private suspend fun loadMentionFiles(workspaceAdapter: WorkspaceAdapter) {
        val out = ArrayList<String>()
        suspend fun walk(path: String, depth: Int) {
            if (depth > MAX_MENTION_DEPTH || out.size >= MAX_MENTION_FILES) return
            val nodes = runCatching { workspaceAdapter.list(path) }.getOrNull() ?: return
            for (node in nodes) {
                if (out.size >= MAX_MENTION_FILES) return
                if (node.kind == FileKind.DIRECTORY) walk(node.path, depth + 1) else out.add(node.path)
            }
        }
        walk("", 0)
        _state.value = _state.value.copy(mentionFiles = out.sorted())
    }

    fun startNewSession() {
        messagesJob?.cancel()
        toolEventsJob?.cancel()
        todosJob?.cancel()
        runningToolIds.clear()
        runningToolStartedAt.clear()
        _state.value = _state.value.copy(
            sessionId = null,
            messages = emptyList(),
            toolEvents = emptyList(),
            todos = emptyList(),
            streamingText = "",
            reasoningText = "",
            agentState = AgentState.IDLE,
            error = null
        )
    }

    /** Resumes a persisted session, restoring its workspace and message history (T1-12). */
    fun loadSession(sessionId: String) {
        if (_state.value.sessionId == sessionId) return
        viewModelScope.launch {
            val session = sessionRepository.getSession(sessionId) ?: return@launch
            session.workspaceId?.let { workspaceId ->
                if (_state.value.workspace?.id != workspaceId) {
                    val workspace = workspaceRepository.getWorkspace(workspaceId)
                    if (workspace != null) {
                        settingsRepository.setLastWorkspaceId(workspaceId)
                        adapter = runCatching { workspaceManager.adapterFor(workspace) }.getOrNull()
                        _state.value = _state.value.copy(workspace = workspace)
                    }
                }
            }
            _state.value = _state.value.copy(sessionId = sessionId, streamingText = "", error = null)
            observeSession(sessionId)
        }
    }

    /**
     * Pull-to-refresh: re-resolve the workspace adapter (a folder reconnected outside the app),
     * rebuild the '@' mention list, and re-subscribe to this session's transcript.
     */
    fun refresh() {
        val id = _state.value.sessionId
        viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true)
            _state.value.workspace?.let { workspace ->
                adapter = runCatching { workspaceManager.adapterFor(workspace) }.getOrNull()
                adapter?.let { loadMentionFiles(it) }
            }
            if (id != null) observeSession(id)
            _state.value = _state.value.copy(refreshing = false)
        }
    }

    private companion object {
        const val MAX_MENTION_FILES = 400
        const val MAX_MENTION_DEPTH = 6
        /** How much live command output to keep in the UI buffer. */
        const val LIVE_OUTPUT_CHARS = 8_000
    }
}
