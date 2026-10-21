package com.aliedu.kern.feature.agent

import com.aliedu.kern.core.agent.AgentEvent
import com.aliedu.kern.core.agent.AgentOrchestrator
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
import com.aliedu.kern.core.model.Todo
import com.aliedu.kern.core.model.TodoStatus
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import com.aliedu.kern.core.workspace.WorkspaceAdapter
import com.aliedu.kern.core.workspace.WorkspaceManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * The agent's task list (FR-018) must be persisted when the orchestrator emits a TodosUpdated event.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModelTodosTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `TodosUpdated event is persisted for the session`() = runTest(dispatcher) {
        val orchestrator = mockk<AgentOrchestrator>()
        every { orchestrator.runTurn(any()) } returns flow {
            emit(AgentEvent.TodosUpdated(listOf(todo("Read the file", TodoStatus.IN_PROGRESS))))
            emit(AgentEvent.Completed("stop"))
        }
        every { orchestrator.resolveApproval(any(), any<Boolean>()) } returns Unit
        every { orchestrator.resolvePlan(any(), any<Boolean>(), any()) } returns Unit

        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.lastWorkspaceId } returns flowOf("ws1")

        val workspaceRepo = mockk<WorkspaceRepository>(relaxed = true)
        coEvery { workspaceRepo.getWorkspace("ws1") } returns workspace()

        val sessionRepo = mockk<SessionRepository>(relaxed = true)
        val messageRepo = mockk<MessageRepository>(relaxed = true)
        every { messageRepo.observeMessages(any()) } returns flowOf(emptyList())

        val toolEventRepo = mockk<ToolEventRepository>(relaxed = true)
        every { toolEventRepo.observeToolEvents(any()) } returns flowOf(emptyList())

        val todoRepo = mockk<TodoRepository>(relaxed = true)
        every { todoRepo.observeTodos(any()) } returns flowOf(emptyList())

        val providerRepo = mockk<ProviderRepository>(relaxed = true)
        every { providerRepo.observeDefaultProfile() } returns flowOf(profile())

        val credentialStore = mockk<CredentialStore>(relaxed = true)
        coEvery { credentialStore.getApiKey(any()) } returns "key"

        val providerAdapter = mockk<com.aliedu.kern.core.providers.api.AiProviderAdapter>(relaxed = true)

        val workspaceManager = mockk<WorkspaceManager>(relaxed = true)
        coEvery { workspaceManager.adapterFor(any()) } returns mockk<WorkspaceAdapter>(relaxed = true)

        val vm = AgentViewModel(
            orchestrator, workspaceRepo, sessionRepo, messageRepo, toolEventRepo, todoRepo,
            providerRepo, credentialStore, settings, providerAdapter, workspaceManager, FakeDevServers(), AgentTurnNotifier.None, AgentSessionStore().apply { scope = CoroutineScope(dispatcher) }
        )
        advanceUntilIdle()

        vm.sendMessage("plan the work")
        advanceUntilIdle()

        coVerify { todoRepo.replaceForSession(any(), any()) }
    }

    private fun todo(content: String, status: TodoStatus) = Todo(
        id = "s1#0",
        sessionId = "s1",
        content = content,
        status = status,
        order = 0,
        createdAt = 0L,
        updatedAt = 0L
    )

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
