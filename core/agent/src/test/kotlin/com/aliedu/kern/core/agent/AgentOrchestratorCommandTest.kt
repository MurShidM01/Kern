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
    fun `a mode change made mid-turn applies to the tools it has not reached`() = runBlocking {
        // The turn started under ASK_CHANGES, but the user switched to Full Access while it ran.
        // The gate has to consult the live mode: reading the snapshot meant the change only took
        // effect in the *next* chat, which is exactly how it felt to the user.
        val executor = TestCommandExecutor(CommandResult(exitCode = 0, output = "ok"))
        val orch = orchestrator(commandTurn("ls -la"), executor)
        val events = mutableListOf<AgentEvent>()

        val job = launch {
            orch.runTurn(request().copy(livePermissionMode = { PermissionMode.FULL_ACCESS }))
                .collect { event -> events.add(event) }
        }
        withTimeout(10_000) { job.join() }

        assertThat(events.filterIsInstance<AgentEvent.CommandApprovalRequired>()).isEmpty()
        assertThat(executor.ran).hasSize(1)
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
    fun `auto-edit still asks before running a command`() = runBlocking {
        // AUTO_EDIT is documented as "apply file edits automatically; ask for anything else". The
        // gate only looked at the impact, so any non-destructive command ran unprompted.
        val executor = TestCommandExecutor(CommandResult(exitCode = 0, output = "ok"))
        val orch = orchestrator(commandTurn("ls -la"), executor)
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

        assertThat(events.filterIsInstance<AgentEvent.CommandApprovalRequired>()).hasSize(1)
        assertThat(ranBeforeApproval).isEqualTo(0)
        assertThat(executor.ran).hasSize(1)
    }

    @Test
    fun `full access runs a non-destructive command without a prompt`() = runBlocking {
        val executor = TestCommandExecutor(CommandResult(exitCode = 0, output = "ok"))
        val events = mutableListOf<AgentEvent>()
        withTimeout(10_000) {
            orchestrator(commandTurn("ls -la"), executor)
                .runTurn(request(PermissionMode.FULL_ACCESS))
                .collect { events.add(it) }
        }
        assertThat(events.filterIsInstance<AgentEvent.CommandApprovalRequired>()).isEmpty()
        assertThat(executor.ran).hasSize(1)
    }

    @Test
    fun `git_status returns the command output, not the proposal text`() = runBlocking {
        // Regression: git_status and git_diff declare themselves read-only and answer with a
        // command request. Only the gated branch executed requests, so the model received
        // "Proposed git status." instead of the repository state.
        File(root, ".git").mkdirs()
        val executor = TestCommandExecutor(CommandResult(exitCode = 0, output = "## main\n M app.kt\n"))
        val turn = FakeAdapter(
            listOf(
                listOf(
                    ProviderEvent.ToolCallRequested(ToolCall("c1", "git_status", "{}")),
                    ProviderEvent.Completed("tool_calls")
                ),
                listOf(ProviderEvent.Delta("done"), ProviderEvent.Completed("stop"))
            )
        )
        val events = mutableListOf<AgentEvent>()
        withTimeout(10_000) {
            orchestrator(turn, executor).runTurn(request()).collect { events.add(it) }
        }

        assertThat(executor.ran.map { it.command }).containsExactly("git status --short --branch")
        val result = events.filterIsInstance<AgentEvent.ToolResultEvent>().last()
        assertThat(result.summary).contains("M app.kt")
        assertThat(result.summary).doesNotContain("Proposed")
        assertThat(result.success).isTrue()
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

    @Test
    fun `a cancelled command is reported as killed, not as an exit`() = runBlocking {
        // Cancelling the turn kills the process group. Reporting a plain "cancelled" made the
        // model retry a half-finished install as if nothing had happened.
        val executor = TestCommandExecutor(
            CommandResult(exitCode = null, output = "E: Sub-process /usr/bin/dpkg returned an error", cancelled = true)
        )
        val orch = orchestrator(commandTurn("apt-get install -y openjdk-17-jdk"), executor)
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

        val summary = events.filterIsInstance<AgentEvent.ToolResultEvent>().last().summary
        assertThat(summary).contains("killed")
        assertThat(events.filterIsInstance<AgentEvent.CommandCompleted>().single().result.cancelled).isTrue()
    }

    @Test
    fun `a background job is reported as started, with how to collect it`() = runBlocking {
        val executor = TestCommandExecutor(
            CommandResult(
                exitCode = null,
                output = "Started background job job-1 (npm install). It is still running.",
                jobId = "job-1",
                running = true
            )
        )
        val orch = orchestrator(commandTurn("npm install"), executor)
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

        val result = events.filterIsInstance<AgentEvent.ToolResultEvent>().last()
        assertThat(result.success).isTrue()
        assertThat(result.summary).contains("background_wait")
        assertThat(result.summary).contains("job-1")
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
