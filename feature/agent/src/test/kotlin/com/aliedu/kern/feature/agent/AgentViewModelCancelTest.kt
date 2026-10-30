package com.aliedu.kern.feature.agent

import com.aliedu.kern.core.agent.AgentEvent
import com.aliedu.kern.core.agent.AgentOrchestrator
import com.aliedu.kern.core.agent.AgentState
import com.aliedu.kern.core.data.preferences.SettingsRepository
import com.aliedu.kern.core.data.repository.MessageRepository
import com.aliedu.kern.core.data.repository.ProviderRepository
import com.aliedu.kern.core.data.repository.SessionRepository
import com.aliedu.kern.core.data.repository.TodoRepository
import com.aliedu.kern.core.data.repository.ToolEventRepository
import com.aliedu.kern.core.data.repository.WorkspaceRepository
import com.aliedu.kern.core.data.security.CredentialStore
import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.ProviderCapabilities
import com.aliedu.kern.core.model.ProviderProfile
import com.aliedu.kern.core.model.ProviderProtocol
import com.aliedu.kern.core.model.ToolEventStatus
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import com.aliedu.kern.core.workspace.WorkspaceAdapter
import com.aliedu.kern.core.workspace.WorkspaceManager
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the "Stop" bug: cancelling a running turn must move the UI to idle instead
 * of leaving it stuck on "Planning", and it must close the tool row the stopped tool opened. That
 * row is persisted, so a row left RUNNING keeps spinning not just for the rest of the turn but on
 * every later visit to the session, and survives an app restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModelCancelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `cancelling a running turn ends it without offering a retry`() = runTest(dispatcher) {
        val orchestrator = mockk<AgentOrchestrator>()
        every { orchestrator.runTurn(any()) } returns flow {
            emit(AgentEvent.State(AgentState.REQUESTING_MODEL))
            emit(AgentEvent.Delta("partial"))
            awaitCancellation()
        }
        every { orchestrator.resolveApproval(any(), any<Boolean>()) } returns Unit
        every { orchestrator.resolvePlan(any(), any<Boolean>(), any()) } returns Unit

        val toolEventRepo = mockk<ToolEventRepository>(relaxed = true)
        every { toolEventRepo.observeToolEvents(any()) } returns flowOf(emptyList())

        val vm = buildViewModel(orchestrator, toolEventRepo)
        advanceUntilIdle()

        vm.sendMessage("Write a very long story")
        advanceUntilIdle()
        assertThat(vm.state.value.isBusy).isTrue()

        vm.cancel()
        advanceUntilIdle()

        // Stop is a user action, not a failure: the composer goes idle, the partial output is
        // dropped, and no retry prompt is left behind.
        assertThat(vm.state.value.agentState).isEqualTo(AgentState.IDLE)
        assertThat(vm.state.value.canRetry).isFalse()
        assertThat(vm.state.value.error).isNull()
        assertThat(vm.state.value.streamingText).isEmpty()
        assertThat(vm.state.value.isBusy).isFalse()
    }

    @Test
    fun `stopping a turn closes the tool row it left running`() = runTest(dispatcher) {
        val orchestrator = mockk<AgentOrchestrator>()
        every { orchestrator.runTurn(any()) } returns flow {
            emit(AgentEvent.State(AgentState.RUNNING_TOOL, "run_command"))
            emit(
                AgentEvent.ToolInvoked(
                    toolName = "run_command",
                    argsRedacted = "{}",
                    messageId = "m1",
                    callId = "c1"
                )
            )
            awaitCancellation()
        }
        every { orchestrator.resolveApproval(any(), any<Boolean>()) } returns Unit
        every { orchestrator.resolvePlan(any(), any<Boolean>(), any()) } returns Unit

        val toolEventRepo = mockk<ToolEventRepository>(relaxed = true)
        every { toolEventRepo.observeToolEvents(any()) } returns flowOf(emptyList())

        val vm = buildViewModel(orchestrator, toolEventRepo)
        advanceUntilIdle()

        vm.sendMessage("Install the JDK")
        advanceUntilIdle()
        assertThat(vm.state.value.runningTool).isEqualTo("run_command")
        coVerify { toolEventRepo.upsert(any()) }

        vm.cancel()
        advanceUntilIdle()

        // The coroutine that would have written this row's result is gone, so nothing else will
        // ever close it. Leaving it RUNNING is what made the transcript spin after Stop.
        coVerify(atLeast = 1) {
            toolEventRepo.closeOpenEvents(any(), ToolEventStatus.CANCELLED, any())
        }
        assertThat(vm.state.value.runningTool).isNull()
        assertThat(vm.state.value.isBusy).isFalse()
    }

    @Test
    fun `reopening a session closes the row the app died on`() = runTest(dispatcher) {
        val orchestrator = mockk<AgentOrchestrator>(relaxed = true)
        val toolEventRepo = mockk<ToolEventRepository>(relaxed = true)
        every { toolEventRepo.observeToolEvents(any()) } returns flowOf(emptyList())

        // A session that was mid-tool when the process died: nothing is running now, but the row
        // is still marked RUNNING in Room.
        val store = AgentSessionStore().apply {
            scope = CoroutineScope(dispatcher)
            state.value = AgentUiState(sessionId = "s1")
        }

        buildViewModel(orchestrator, toolEventRepo, store)
        advanceUntilIdle()

        coVerify { toolEventRepo.closeOpenEvents("s1", ToolEventStatus.CANCELLED, any()) }
    }

    private fun buildViewModel(
        orchestrator: AgentOrchestrator,
        toolEventRepo: ToolEventRepository,
        store: AgentSessionStore = AgentSessionStore().apply { scope = CoroutineScope(dispatcher) }
    ): AgentViewModel {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.lastWorkspaceId } returns flowOf("ws1")

        val workspaceRepo = mockk<WorkspaceRepository>(relaxed = true)
        coEvery { workspaceRepo.getWorkspace("ws1") } returns workspace()

        val sessionRepo = mockk<SessionRepository>(relaxed = true)

        val messageRepo = mockk<MessageRepository>(relaxed = true)
        every { messageRepo.observeMessages(any()) } returns flowOf(emptyList())

        val todoRepo = mockk<TodoRepository>(relaxed = true)
        every { todoRepo.observeTodos(any()) } returns flowOf(emptyList())

        val providerRepo = mockk<ProviderRepository>(relaxed = true)
        every { providerRepo.observeDefaultProfile() } returns flowOf(profile())

        val credentialStore = mockk<CredentialStore>(relaxed = true)
        coEvery { credentialStore.getApiKey(any()) } returns "test-key"

        val providerAdapter =
            mockk<com.aliedu.kern.core.providers.api.AiProviderAdapter>(relaxed = true)

        val workspaceManager = mockk<WorkspaceManager>(relaxed = true)
        coEvery { workspaceManager.adapterFor(any()) } returns mockk<WorkspaceAdapter>(relaxed = true)

        return AgentViewModel(
            orchestrator,
            workspaceRepo,
            sessionRepo,
            messageRepo,
            toolEventRepo,
            todoRepo,
            providerRepo,
            credentialStore,
            settings,
            providerAdapter,
            workspaceManager,
            FakeDevServers(),
            AgentTurnNotifier.None,
            store
        )
    }

    private fun workspace() = Workspace(
        id = "ws1",
        displayName = "Demo",
        sourceType = WorkspaceSourceType.APP_MANAGED,
        uriString = null,
        localPath = null,
        grantState = GrantState.VALID,
        lastOpenedAt = 0L,
        createdAt = 0L
    )

    private fun profile() = ProviderProfile(
        id = "p1",
        displayName = "Test",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        baseUrl = "https://example.test/v1",
        modelId = "test-model",
        credentialRef = null,
        customHeaders = emptyMap(),
        isDefault = true,
        connectTimeoutMs = 1_000L,
        readTimeoutMs = 1_000L,
        capabilities = ProviderCapabilities(),
        createdAt = 0L,
        updatedAt = 0L
    )
}
