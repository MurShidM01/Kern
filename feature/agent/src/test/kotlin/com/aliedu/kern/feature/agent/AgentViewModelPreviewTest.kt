package com.aliedu.kern.feature.agent

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
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import com.aliedu.kern.core.providers.api.AiProviderAdapter
import com.aliedu.kern.core.tools.devserver.DevServer
import com.aliedu.kern.core.tools.devserver.DevServerState
import com.aliedu.kern.core.workspace.WorkspaceAdapter
import com.aliedu.kern.core.workspace.WorkspaceManager
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * The preview icon appears in the agent's app bar only while something is really serving, so the
 * state it keys off has to be exactly "running, with a URL".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModelPreviewTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun server(id: String, state: DevServerState, port: Int?) = DevServer(
        id = id,
        command = "serve $port",
        port = port,
        url = if (state == DevServerState.RUNNING && port != null) "http://127.0.0.1:$port" else null,
        state = state,
        startedAt = 0L,
        sessionId = "sess-$id"
    )

    private fun viewModel(devServers: FakeDevServers): AgentViewModel {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.lastWorkspaceId } returns flowOf("ws1")
        val workspaceRepo = mockk<WorkspaceRepository>(relaxed = true)
        coEvery { workspaceRepo.getWorkspace("ws1") } returns Workspace(
            id = "ws1",
            displayName = "Demo",
            sourceType = WorkspaceSourceType.APP_MANAGED,
            uriString = null,
            localPath = null,
            grantState = GrantState.VALID,
            lastOpenedAt = 0L,
            createdAt = 0L
        )
        val messageRepo = mockk<MessageRepository>(relaxed = true)
        every { messageRepo.observeMessages(any()) } returns flowOf(emptyList())
        val toolEventRepo = mockk<ToolEventRepository>(relaxed = true)
        every { toolEventRepo.observeToolEvents(any()) } returns flowOf(emptyList())
        val todoRepo = mockk<TodoRepository>(relaxed = true)
        every { todoRepo.observeTodos(any()) } returns flowOf(emptyList())
        val providerRepo = mockk<ProviderRepository>(relaxed = true)
        every { providerRepo.observeDefaultProfile() } returns flowOf(
            ProviderProfile(
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
        )
        val workspaceManager = mockk<WorkspaceManager>(relaxed = true)
        coEvery { workspaceManager.adapterFor(any()) } returns mockk<WorkspaceAdapter>(relaxed = true)

        return AgentViewModel(
            mockk<AgentOrchestrator>(relaxed = true),
            workspaceRepo,
            mockk<SessionRepository>(relaxed = true),
            messageRepo,
            toolEventRepo,
            todoRepo,
            providerRepo,
            mockk<CredentialStore>(relaxed = true),
            settings,
            mockk<AiProviderAdapter>(relaxed = true),
            workspaceManager,
            devServers,
            AgentTurnNotifier.None, AgentSessionStore().apply { scope = CoroutineScope(dispatcher) }
        )
    }

    @Test
    fun `no servers means no preview entry point`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeDevServers())
        advanceUntilIdle()

        assertThat(viewModel.state.value.runningServers).isEmpty()
    }

    @Test
    fun `only running servers with a url are offered`() = runTest(dispatcher) {
        val devServers = FakeDevServers()
        val viewModel = viewModel(devServers)
        advanceUntilIdle()

        devServers.setServers(
            listOf(
                server("a", DevServerState.STARTING, 3000),
                server("b", DevServerState.RUNNING, 4000),
                server("c", DevServerState.STOPPED, 5000),
                server("d", DevServerState.FAILED, null)
            )
        )
        advanceUntilIdle()

        assertThat(viewModel.state.value.runningServers.map { it.id }).containsExactly("b")
    }

    @Test
    fun `a server that stops takes the preview entry point away`() = runTest(dispatcher) {
        val devServers = FakeDevServers()
        val viewModel = viewModel(devServers)
        devServers.setServers(listOf(server("a", DevServerState.RUNNING, 3000)))
        advanceUntilIdle()
        assertThat(viewModel.state.value.runningServers).hasSize(1)

        devServers.setServers(listOf(server("a", DevServerState.STOPPED, 3000)))
        advanceUntilIdle()

        assertThat(viewModel.state.value.runningServers).isEmpty()
    }
}
