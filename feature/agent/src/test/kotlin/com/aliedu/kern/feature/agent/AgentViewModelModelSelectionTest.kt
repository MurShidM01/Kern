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
import com.aliedu.kern.core.model.ProviderCapabilities
import com.aliedu.kern.core.model.ProviderProfile
import com.aliedu.kern.core.model.ProviderProtocol
import com.aliedu.kern.core.providers.api.AiProviderAdapter
import com.aliedu.kern.core.providers.api.ConnectionResult
import com.aliedu.kern.core.workspace.WorkspaceManager
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
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
 * The composer's model picker must TEST the connection before switching (requirement: "select only
 * after a successful connection; otherwise show model not available").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModelModelSelectionTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun profile(id: String) = ProviderProfile(
        id = id,
        displayName = "NaraRouter",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        baseUrl = "https://example.test/v1",
        modelId = "model-$id",
        credentialRef = id,
        customHeaders = emptyMap(),
        isDefault = false,
        connectTimeoutMs = 1_000L,
        readTimeoutMs = 1_000L,
        capabilities = ProviderCapabilities(),
        createdAt = 0L,
        updatedAt = 0L
    )

    private class Fixture(
        val vm: AgentViewModel,
        val providerRepo: ProviderRepository,
        val adapter: AiProviderAdapter
    )

    private fun build(adapterResult: ConnectionResult): Fixture {
        val orchestrator = mockk<AgentOrchestrator>(relaxed = true)
        val workspaceRepo = mockk<WorkspaceRepository>(relaxed = true)
        val sessionRepo = mockk<SessionRepository>(relaxed = true)
        val messageRepo = mockk<MessageRepository>(relaxed = true)
        val toolEventRepo = mockk<ToolEventRepository>(relaxed = true)
        val todoRepo = mockk<TodoRepository>(relaxed = true)
        every { todoRepo.observeTodos(any()) } returns flowOf(emptyList())
        val providerRepo = mockk<ProviderRepository>(relaxed = true)
        every { providerRepo.observeProfiles() } returns flowOf(listOf(profile("a"), profile("b")))
        every { providerRepo.observeDefaultProfile() } returns flowOf(profile("a"))
        val credentialStore = mockk<CredentialStore>(relaxed = true)
        coEvery { credentialStore.getApiKey(any()) } returns "key"
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.lastWorkspaceId } returns flowOf(null)
        val adapter = mockk<AiProviderAdapter>(relaxed = true)
        coEvery { adapter.testConnection(any(), any()) } returns adapterResult
        val workspaceManager = mockk<WorkspaceManager>(relaxed = true)
        val vm = AgentViewModel(
            orchestrator, workspaceRepo, sessionRepo, messageRepo, toolEventRepo, todoRepo,
            providerRepo, credentialStore, settings, adapter, workspaceManager, FakeDevServers(), AgentTurnNotifier.None, AgentSessionStore().apply { scope = CoroutineScope(dispatcher) }
        )
        return Fixture(vm, providerRepo, adapter)
    }

    @Test
    fun `selecting a reachable model makes it the default`() = runTest(dispatcher) {
        val f = build(ConnectionResult(true, "Connected.", 12))
        advanceUntilIdle()

        f.vm.selectModel("b")
        advanceUntilIdle()

        coVerify { f.providerRepo.setDefault("b") }
        assertThat(f.vm.state.value.modelTestError).isNull()
        assertThat(f.vm.state.value.modelTestingId).isNull()
    }

    @Test
    fun `selecting an unreachable model reports not available and does not switch`() = runTest(dispatcher) {
        val f = build(ConnectionResult(false, "Could not connect.", 8))
        advanceUntilIdle()

        f.vm.selectModel("b")
        advanceUntilIdle()

        coVerify(exactly = 0) { f.providerRepo.setDefault(any()) }
        assertThat(f.vm.state.value.modelTestError).contains("not available")
        assertThat(f.vm.state.value.modelTestError).contains("model-b")
    }
}
