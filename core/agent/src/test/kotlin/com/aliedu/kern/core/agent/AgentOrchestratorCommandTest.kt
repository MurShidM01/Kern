package com.aliedu.kern.core.agent

import com.aliedu.kern.core.common.coroutines.DispatcherProvider
import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.PermissionMode
import com.aliedu.kern.core.model.ProviderCapabilities
import com.aliedu.kern.core.model.ProviderProfile
import com.aliedu.kern.core.model.ProviderProtocol
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import com.aliedu.kern.core.providers.api.AgentRequest
import com.aliedu.kern.core.providers.api.AiProviderAdapter
import com.aliedu.kern.core.providers.api.ConnectionResult
import com.aliedu.kern.core.providers.api.ModelInfo
import com.aliedu.kern.core.providers.api.ProviderError
import com.aliedu.kern.core.providers.api.ProviderErrors
import com.aliedu.kern.core.providers.api.ProviderEvent
import com.aliedu.kern.core.providers.api.ToolCall
import com.aliedu.kern.core.tools.CommandImpact
import com.aliedu.kern.core.tools.CommandResult
import com.aliedu.kern.core.tools.DeleteFileTool
import com.aliedu.kern.core.tools.EditFileTool
import com.aliedu.kern.core.tools.FetchUrlTool
import com.aliedu.kern.core.tools.GitCommitTool
import com.aliedu.kern.core.tools.GitDiffTool
import com.aliedu.kern.core.tools.GitStatusTool
import com.aliedu.kern.core.tools.ProjectDetector
import com.aliedu.kern.core.tools.RunBuildTool
import com.aliedu.kern.core.tools.RunLintTool
import com.aliedu.kern.core.tools.RunTestsTool
import com.aliedu.kern.core.tools.GlobTool
import com.aliedu.kern.core.tools.GrepTool
import com.aliedu.kern.core.tools.ListFilesTool
import com.aliedu.kern.core.tools.PatchApplier
import com.aliedu.kern.core.tools.ProposeEditTool
import com.aliedu.kern.core.tools.ProposePatchTool
import com.aliedu.kern.core.tools.WriteFileTool
import com.aliedu.kern.core.tools.ReadFileTool
import com.aliedu.kern.core.tools.RunCommandTool
import com.aliedu.kern.core.tools.SearchFilesTool
import com.aliedu.kern.core.tools.ToolRegistry
import com.aliedu.kern.core.tools.UpdateTodosTool
import com.aliedu.kern.core.tools.diff.DiffService
import com.aliedu.kern.core.workspace.AppManagedWorkspaceAdapter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Backs PRD FR-017: a command is proposed, approved by the user, and only then executed; a
 * DESTRUCTIVE command never auto-runs even in a permissive permission mode.
 */
class AgentOrchestratorCommandTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var adapter: AppManagedWorkspaceAdapter
    private lateinit var workspace: Workspace

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = Dispatchers.Default
        override val io: CoroutineDispatcher get() = Dispatchers.Default
        override val default: CoroutineDispatcher get() = Dispatchers.Default
    }

    @Before
    fun setUp() {
        root = temp.newFolder("ws")
        adapter = AppManagedWorkspaceAdapter(root, "ws")
        workspace = Workspace(
            id = "ws",
            displayName = "Fixture",
            sourceType = WorkspaceSourceType.APP_MANAGED,
            uriString = null,
            localPath = root.absolutePath,
            grantState = GrantState.NOT_APPLICABLE,
            lastOpenedAt = 0,
            createdAt = 0
        )
    }

    private fun orchestrator(fake: AiProviderAdapter, executor: TestCommandExecutor): AgentOrchestrator {
        val diffService = DiffService()
        val registry = testToolRegistry(diffService)
        return AgentOrchestrator(
            providerAdapter = fake,
            toolRegistry = registry,
            patchApplier = PatchApplier(diffService),
            commandExecutor = executor,
            contextBuilder = ContextBuilder(ContextCompactor()),
            projectRulesLoader = ProjectRulesLoader(),
            notes = WorkspaceNotes(temp.newFolder()),
            dispatchers = testDispatchers
        )
    }

    private fun profile() = ProviderProfile(
        id = "p", displayName = "gw", protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        baseUrl = "http://localhost", modelId = "m", credentialRef = null,
        customHeaders = emptyMap(), isDefault = true, connectTimeoutMs = 1000,
        readTimeoutMs = 1000, capabilities = ProviderCapabilities(), createdAt = 0, updatedAt = 0
    )

    private fun request(mode: PermissionMode = PermissionMode.ASK_CHANGES) = TurnRequest(
        sessionId = "s1",
        workspace = workspace,
        adapter = adapter,
        profile = profile(),
        apiKey = "sk-test",
        history = emptyList(),
        userInput = "run the tests",
        permissionMode = mode
    )

    private fun commandTurn(command: String) = FakeAdapter(
        listOf(
            listOf(
                ProviderEvent.ToolCallRequested(ToolCall("c1", "run_command", """{"command":"$command"}""")),
                ProviderEvent.Completed("tool_calls")
            ),
            listOf(ProviderEvent.Delta("done"), ProviderEvent.Completed("stop"))
        )
    )

    @Test
    fun `an approved command is executed and its result reported`() = runBlocking {
        val executor = TestCommandExecutor(CommandResult(exitCode = 0, output = "all tests passed"))
        val orch = orchestrator(commandTurn("ls -la"), executor)
        val events = mutableListOf<AgentEvent>()

        val job = launch {
            orch.runTurn(request()).collect { event ->
                events.add(event)
                if (event is AgentEvent.CommandApprovalRequired) {
                    launch { delay(100); orch.resolveApproval("s1", true) }
                }
            }
        }
        withTimeout(10_000) { job.join() }

        val approval = events.filterIsInstance<AgentEvent.CommandApprovalRequired>().single()
        assertThat(approval.request.command).isEqualTo("ls -la")
        assertThat(approval.request.impact).isEqualTo(CommandImpact.READ_ONLY)
        assertThat(executor.ran).hasSize(1)
        assertThat(events.filterIsInstance<AgentEvent.CommandCompleted>()).hasSize(1)
        assertThat(events.filterIsInstance<AgentEvent.ToolResultEvent>().last().success).isTrue()
    }

    @Test
    fun `a rejected command is never executed`() = runBlocking {
        val executor = TestCommandExecutor()
        val orch = orchestrator(commandTurn("ls -la"), executor)
        val events = mutableListOf<AgentEvent>()

        val job = launch {
            orch.runTurn(request()).collect { event ->
                events.add(event)
                if (event is AgentEvent.CommandApprovalRequired) {
                    launch { delay(100); orch.resolveApproval("s1", false) }
                }
            }
        }
        withTimeout(10_000) { job.join() }

        assertThat(executor.ran).isEmpty()
        assertThat(events.filterIsInstance<AgentEvent.CommandCompleted>()).isEmpty()
        assertThat(events.filterIsInstance<AgentEvent.ToolResultEvent>().last().success).isFalse()
    }

    @Test
    fun `a destructive command still requires approval in auto-edit mode`() = runBlocking {
        val executor = TestCommandExecutor()
        val orch = orchestrator(commandTurn("rm -rf build"), executor)
        val events = mutableListOf<AgentEvent>()
        var ranBeforeApproval = -1

        val job = launch {
            orch.runTurn(request(PermissionMode.AUTO_EDIT)).collect { event ->
                events.add(event)
                if (event is AgentEvent.CommandApprovalRequired) {
                    ranBeforeApproval = executor.ran.size
                    launch { delay(100); orch.resolveApproval("s1", true) }
                }
            }
        }
        withTimeout(10_000) { job.join() }

        val approval = events.filterIsInstance<AgentEvent.CommandApprovalRequired>().single()
        assertThat(approval.request.impact).isEqualTo(CommandImpact.DESTRUCTIVE)
        assertThat(ranBeforeApproval).isEqualTo(0) // not run before the user approved
        assertThat(executor.ran).hasSize(1)        // run only after approval
    }

    @Test
    fun `a read-only command auto-runs in auto-edit mode without a prompt`() = runBlocking {
        val executor = TestCommandExecutor(CommandResult(exitCode = 0, output = "ok"))
        val events = mutableListOf<AgentEvent>()
        withTimeout(10_000) {
            orchestrator(commandTurn("ls -la"), executor)
                .runTurn(request(PermissionMode.AUTO_EDIT))
                .collect { events.add(it) }
        }
        assertThat(events.filterIsInstance<AgentEvent.CommandApprovalRequired>()).isEmpty()
        assertThat(executor.ran).hasSize(1)
        assertThat(events.filterIsInstance<AgentEvent.CommandCompleted>()).hasSize(1)
    }

    @Test
    fun `a failed command is reported as an unsuccessful tool result`() = runBlocking {
        val executor = TestCommandExecutor(CommandResult(exitCode = 1, output = "boom"))
        val orch = orchestrator(commandTurn("ls missing"), executor)
        val events = mutableListOf<AgentEvent>()

        val job = launch {
            orch.runTurn(request()).collect { event ->
                events.add(event)
                if (event is AgentEvent.CommandApprovalRequired) {
                    launch { delay(100); orch.resolveApproval("s1", true) }
                }
            }
        }
        withTimeout(10_000) { job.join() }

        val completed = events.filterIsInstance<AgentEvent.CommandCompleted>().single()
        assertThat(completed.result.exitCode).isEqualTo(1)
        assertThat(events.filterIsInstance<AgentEvent.ToolResultEvent>().last().success).isFalse()
    }

    @Test
    fun `an approved command streams its output before it completes`() = runBlocking {
        val executor = TestCommandExecutor(
            result = CommandResult(exitCode = 0, output = "line1\nline2\n"),
            chunks = listOf("line1\n", "line2\n")
        )
        val orch = orchestrator(commandTurn("make"), executor)
        val events = mutableListOf<AgentEvent>()

        val job = launch {
            orch.runTurn(request()).collect { event ->
                events.add(event)
                if (event is AgentEvent.CommandApprovalRequired) {
                    launch { delay(100); orch.resolveApproval("s1", true) }
                }
            }
        }
        withTimeout(10_000) { job.join() }

        val chunks = events.filterIsInstance<AgentEvent.CommandOutput>()
        assertThat(chunks.map { it.chunk }).containsExactly("line1\n", "line2\n").inOrder()
        assertThat(chunks.all { it.command == "make" }).isTrue()
        // Live output arrives before the completion event, so the UI shows progress, not a spinner.
        val lastChunk = events.indexOfLast { it is AgentEvent.CommandOutput }
        val completed = events.indexOfFirst { it is AgentEvent.CommandCompleted }
        assertThat(lastChunk).isLessThan(completed)
    }

    /** Replays a fixed list of event batches, one batch per model turn. */
    private class FakeAdapter(
        private val turns: List<List<ProviderEvent>>
    ) : AiProviderAdapter {
        private var calls = 0

        override suspend fun testConnection(profile: ProviderProfile, apiKey: String?): ConnectionResult =
            ConnectionResult(true, "ok", 1)

        override fun streamResponse(
            profile: ProviderProfile,
            apiKey: String?,
            request: AgentRequest
        ): Flow<ProviderEvent> = flow {
            val batch = turns[calls.coerceAtMost(turns.size - 1)]
            calls++
            batch.forEach { emit(it) }
        }

        override suspend fun listModels(profile: ProviderProfile, apiKey: String?): Result<List<ModelInfo>> =
            Result.success(emptyList())

        override fun normalizeError(error: Throwable): ProviderError = ProviderErrors.from(error)

        override fun capabilities(profile: ProviderProfile): ProviderCapabilities = ProviderCapabilities()
    }
}
