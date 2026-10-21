package com.aliedu.kern.core.agent

import com.aliedu.kern.core.common.coroutines.DispatcherProvider
import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.PermissionMode
import com.aliedu.kern.core.model.ProviderCapabilities
import com.aliedu.kern.core.model.ProviderProfile
import com.aliedu.kern.core.model.ProviderProtocol
import com.aliedu.kern.core.model.TodoStatus
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import com.aliedu.kern.core.providers.api.AgentRequest
import com.aliedu.kern.core.providers.api.AiProviderAdapter
import com.aliedu.kern.core.providers.api.ConnectionResult
import com.aliedu.kern.core.providers.api.ModelInfo
import com.aliedu.kern.core.providers.api.ProviderError
import com.aliedu.kern.core.providers.api.ProviderErrorType
import com.aliedu.kern.core.providers.api.ProviderErrors
import com.aliedu.kern.core.providers.api.ProviderEvent
import com.aliedu.kern.core.providers.api.ToolCall
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
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
 * Backs P1-07 / T1-08: the agent tool loop must surface an approval card and write ONLY after the
 * user approves; rejecting leaves the file untouched (PRD 9.1, 9.5).
 */
class AgentOrchestratorTest {

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

    private fun orchestrator(
        fake: AiProviderAdapter,
        commandExecutor: TestCommandExecutor = TestCommandExecutor()
    ): AgentOrchestrator {
        val diffService = DiffService()
        val registry = testToolRegistry(diffService)
        return AgentOrchestrator(
            providerAdapter = fake,
            toolRegistry = registry,
            patchApplier = PatchApplier(diffService),
            commandExecutor = commandExecutor,
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

    private fun request(sessionId: String = "s1") = TurnRequest(
        sessionId = sessionId,
        workspace = workspace,
        adapter = adapter,
        profile = profile(),
        apiKey = "sk-test",
        history = emptyList(),
        userInput = "please edit App.kt"
    )

    @Test
    fun `approved proposal is applied and the file is written`() = runBlocking {
        File(root, "App.kt").writeText("old")
        val fake = FakeAdapter(
            listOf(
                listOf(
                    ProviderEvent.ToolCallRequested(
                        ToolCall("c1", "propose_edit", """{"path":"App.kt","new_content":"new"}""")
                    ),
                    ProviderEvent.Completed("tool_calls")
                ),
                listOf(ProviderEvent.Delta("done"), ProviderEvent.Completed("stop"))
            )
        )
        val orch = orchestrator(fake)
        val events = mutableListOf<AgentEvent>()
        var contentWhenApprovalAsked: String? = null

        val job = launch {
            orch.runTurn(request()).collect { event ->
                events.add(event)
                if (event is AgentEvent.ApprovalRequired) {
                    contentWhenApprovalAsked = File(root, "App.kt").readText()
                    launch { delay(200); orch.resolveApproval("s1", true) }
                }
            }
        }
        withTimeout(10_000) { job.join() }

        assertThat(events.filterIsInstance<AgentEvent.ApprovalRequired>()).hasSize(1)
        assertThat(events.filterIsInstance<AgentEvent.PatchApplied>()).hasSize(1)
        // No write happened while the approval card was on screen.
        assertThat(contentWhenApprovalAsked).isEqualTo("old")
        assertThat(File(root, "App.kt").readText()).isEqualTo("new")
        // The row shows +1 / -1, so the applied result must carry the diff stats.
        val applied = events.filterIsInstance<AgentEvent.ToolResultEvent>().last()
        assertThat(applied.linesAdded).isEqualTo(1)
        assertThat(applied.linesRemoved).isEqualTo(1)
        assertThat(applied.summary).contains("+1 / -1")
    }

    @Test
    fun `rejected proposal never writes to disk`() = runBlocking {
        File(root, "App.kt").writeText("old")
        val fake = FakeAdapter(
            listOf(
                listOf(
                    ProviderEvent.ToolCallRequested(
                        ToolCall("c1", "propose_edit", """{"path":"App.kt","new_content":"new"}""")
                    ),
                    ProviderEvent.Completed("tool_calls")
                ),
                listOf(ProviderEvent.Delta("understood"), ProviderEvent.Completed("stop"))
            )
        )
        val orch = orchestrator(fake)
        val events = mutableListOf<AgentEvent>()

        val job = launch {
            orch.runTurn(request()).collect { event ->
                events.add(event)
                if (event is AgentEvent.ApprovalRequired) {
                    launch { delay(200); orch.resolveApproval("s1", false) }
                }
            }
        }
        withTimeout(10_000) { job.join() }

        assertThat(events.filterIsInstance<AgentEvent.PatchApplied>()).isEmpty()
        assertThat(events.filterIsInstance<AgentEvent.ApprovalRequired>()).hasSize(1)
        assertThat(File(root, "App.kt").readText()).isEqualTo("old")
    }

    @Test
    fun `approving only one file of a multi-file patch leaves the other unchanged`() = runBlocking {
        File(root, "A.kt").writeText("a1")
        File(root, "B.kt").writeText("b1")
        val fake = FakeAdapter(
            listOf(
                listOf(
                    ProviderEvent.ToolCallRequested(
                        ToolCall(
                            "c1",
                            "propose_patch",
                            """{"files":[{"path":"A.kt","new_content":"a2","overwrite":true},""" +
                                """{"path":"B.kt","new_content":"b2","overwrite":true}]}"""
                        )
                    ),
                    ProviderEvent.Completed("tool_calls")
                ),
                listOf(ProviderEvent.Delta("done"), ProviderEvent.Completed("stop"))
            )
        )
        val orch = orchestrator(fake)
        val events = mutableListOf<AgentEvent>()

        val job = launch {
            orch.runTurn(request()).collect { event ->
                events.add(event)
                if (event is AgentEvent.ApprovalRequired) {
                    launch {
                        delay(200)
                        orch.resolveApproval(
                            "s1",
                            AgentOrchestrator.ApprovalDecision(
                                approved = true,
                                acceptedPaths = setOf("A.kt")
                            )
                        )
                    }
                }
            }
        }
        withTimeout(10_000) { job.join() }

        assertThat(events.filterIsInstance<AgentEvent.ApprovalRequired>()).hasSize(1)
        assertThat(File(root, "A.kt").readText()).isEqualTo("a2")
        assertThat(File(root, "B.kt").readText()).isEqualTo("b1")
    }

    @Test
    fun `auto edit permission applies the edit without an approval prompt`() = runBlocking {
        File(root, "App.kt").writeText("old")
        val fake = FakeAdapter(
            listOf(
                listOf(
                    ProviderEvent.ToolCallRequested(
                        ToolCall("c1", "propose_edit", """{"path":"App.kt","new_content":"new"}""")
                    ),
                    ProviderEvent.Completed("tool_calls")
                ),
                listOf(ProviderEvent.Delta("done"), ProviderEvent.Completed("stop"))
            )
        )
        val events = mutableListOf<AgentEvent>()
        withTimeout(10_000) {
            orchestrator(fake)
                .runTurn(request().copy(permissionMode = PermissionMode.AUTO_EDIT))
                .collect { events.add(it) }
        }
        assertThat(events.filterIsInstance<AgentEvent.ApprovalRequired>()).isEmpty()
        assertThat(events.filterIsInstance<AgentEvent.PatchApplied>()).hasSize(1)
        assertThat(File(root, "App.kt").readText()).isEqualTo("new")
    }

    @Test
    fun `unknown tool is reported without crashing the turn`() = runBlocking {
        val fake = FakeAdapter(
            listOf(
                listOf(
                    ProviderEvent.ToolCallRequested(ToolCall("c1", "launch_missiles", "{}")),
                    ProviderEvent.Completed("tool_calls")
                ),
                listOf(ProviderEvent.Delta("recovered"), ProviderEvent.Completed("stop"))
            )
        )
        val events = mutableListOf<AgentEvent>()
        withTimeout(10_000) {
            orchestrator(fake).runTurn(request()).collect { events.add(it) }
        }
        val toolResults = events.filterIsInstance<AgentEvent.ToolResultEvent>()
        assertThat(toolResults.first().success).isFalse()
        assertThat(toolResults.first().summary).contains("Unknown tool")
        // The transcript needs a row for the failure; without one the call looked like a silent stop.
        assertThat(events.filterIsInstance<AgentEvent.ToolInvoked>().map { it.toolName })
            .contains("launch_missiles")
        assertThat(events.filterIsInstance<AgentEvent.Completed>()).isNotEmpty()
    }

    @Test
    fun `an edits array sent as a JSON string still reaches edit_file as an array`() = runBlocking {
        File(root, "App.kt").writeText("val a = 1\n")
        val fake = FakeAdapter(
            listOf(
                listOf(
                    ProviderEvent.ToolCallRequested(
                        ToolCall(
                            "c1",
                            "edit_file",
                            """{"path":"App.kt","edits":"[{\"old_string\":\"val a = 1\",""" +
                                """\"new_string\":\"val a = 2\"}]"}"""
                        )
                    ),
                    ProviderEvent.Completed("tool_calls")
                ),
                listOf(ProviderEvent.Delta("done"), ProviderEvent.Completed("stop"))
            )
        )
        val events = mutableListOf<AgentEvent>()
        withTimeout(10_000) {
            orchestrator(fake)
                .runTurn(request().copy(permissionMode = PermissionMode.AUTO_EDIT))
                .collect { events.add(it) }
        }

        assertThat(events.filterIsInstance<AgentEvent.PatchApplied>()).hasSize(1)
        assertThat(File(root, "App.kt").readText()).isEqualTo("val a = 2\n")
    }

    @Test
    fun `provider failure ends the turn with a failed state`() = runBlocking {
        val fake = FakeAdapter(
            listOf(listOf(ProviderEvent.Failed(ProviderError(ProviderErrorType.NETWORK, "offline"))))
        )
        val events = mutableListOf<AgentEvent>()
        withTimeout(10_000) {
            orchestrator(fake).runTurn(request()).collect { events.add(it) }
        }
        assertThat(events.filterIsInstance<AgentEvent.Failed>()).hasSize(1)
        assertThat(events.filterIsInstance<AgentEvent.State>().map { it.state })
            .contains(AgentState.FAILED)
    }

    @Test
    fun `plain text answer completes without any tool call`() = runBlocking {
        val fake = FakeAdapter(listOf(listOf(ProviderEvent.Delta("hello"), ProviderEvent.Completed("stop"))))
        val events = mutableListOf<AgentEvent>()
        withTimeout(10_000) {
            orchestrator(fake).runTurn(request()).collect { events.add(it) }
        }
        val assistant = events.filterIsInstance<AgentEvent.AssistantMessage>().single()
        assertThat(assistant.text).isEqualTo("hello")
        assertThat(events.filterIsInstance<AgentEvent.Completed>()).isNotEmpty()
        assertThat(events.filterIsInstance<AgentEvent.ApprovalRequired>()).isEmpty()
    }

    @Test
    fun `reasoning is attached to the assistant message of its step`() = runBlocking {
        val fake = FakeAdapter(
            listOf(
                listOf(
                    ProviderEvent.ReasoningDelta("thinking..."),
                    ProviderEvent.Delta("answer"),
                    ProviderEvent.Completed("stop")
                )
            )
        )
        val events = mutableListOf<AgentEvent>()
        withTimeout(10_000) {
            orchestrator(fake).runTurn(request()).collect { events.add(it) }
        }
        val assistant = events.filterIsInstance<AgentEvent.AssistantMessage>().single()
        assertThat(assistant.text).isEqualTo("answer")
        assertThat(assistant.reasoning).isEqualTo("thinking...")
    }

    @Test
    fun `tool-only step emits an anchor message the tool invocation references`() = runBlocking {
        File(root, "App.kt").writeText("x")
        val fake = FakeAdapter(
            listOf(
                listOf(
                    ProviderEvent.ToolCallRequested(ToolCall("c1", "read_file", """{"path":"App.kt"}""")),
                    ProviderEvent.Completed("tool_calls")
                ),
                listOf(ProviderEvent.Delta("done"), ProviderEvent.Completed("stop"))
            )
        )
        val events = mutableListOf<AgentEvent>()
        withTimeout(10_000) {
            orchestrator(fake).runTurn(request()).collect { events.add(it) }
        }
        val anchor = events.filterIsInstance<AgentEvent.AssistantMessage>().first()
        assertThat(anchor.text).isEmpty()
        val invoked = events.filterIsInstance<AgentEvent.ToolInvoked>().single()
        assertThat(invoked.messageId).isEqualTo(anchor.messageId)
    }

    @Test
    fun `update_todos surfaces the task list to the caller`() = runBlocking {
        val fake = FakeAdapter(
            listOf(
                listOf(
                    ProviderEvent.ToolCallRequested(
                        ToolCall(
                            "c1", "update_todos",
                            """{"todos":[{"content":"Read the file","status":"in_progress"},{"content":"Edit it","status":"pending"}]}"""
                        )
                    ),
                    ProviderEvent.Completed("tool_calls")
                ),
                listOf(ProviderEvent.Delta("ok"), ProviderEvent.Completed("stop"))
            )
        )
        val events = mutableListOf<AgentEvent>()
        withTimeout(10_000) {
            orchestrator(fake).runTurn(request()).collect { events.add(it) }
        }
        val updated = events.filterIsInstance<AgentEvent.TodosUpdated>().single()
        assertThat(updated.todos).hasSize(2)
        assertThat(updated.todos[0].content).isEqualTo("Read the file")
        assertThat(updated.todos[0].status).isEqualTo(TodoStatus.IN_PROGRESS)
    }

    @Test
    fun `the agent keeps calling tools well past the old eight-step ceiling`() = runBlocking {
        // The loop used to stop after eight model turns ("Stopped after the maximum number of tool
        // steps"), truncating any task that needed more. Twelve tool turns followed by a final
        // answer must run to completion.
        val toolTurn = listOf(
            ProviderEvent.ToolCallRequested(ToolCall("c1", "read_file", """{"path":"App.kt"}""")),
            ProviderEvent.Completed("tool_calls")
        )
        val fake = FakeAdapter(
            List(12) { toolTurn } +
                listOf(listOf(ProviderEvent.Delta("done"), ProviderEvent.Completed("stop")))
        )

        val events = mutableListOf<AgentEvent>()
        withTimeout(20_000) {
            orchestrator(fake).runTurn(request()).collect { events.add(it) }
        }

        assertThat(events.filterIsInstance<AgentEvent.Failed>()).isEmpty()
        assertThat(events.filterIsInstance<AgentEvent.ToolInvoked>()).hasSize(12)
        assertThat(events.filterIsInstance<AgentEvent.Completed>()).hasSize(1)
    }

    @Test
    fun `read-only tools in one step run concurrently and keep call order`() = runBlocking {
        File(root, "A.kt").writeText("alpha")
        File(root, "B.kt").writeText("bravo")
        val fake = FakeAdapter(
            listOf(
                listOf(
                    ProviderEvent.ToolCallRequested(ToolCall("c1", "read_file", """{"path":"A.kt"}""")),
                    ProviderEvent.ToolCallRequested(ToolCall("c2", "read_file", """{"path":"B.kt"}""")),
                    ProviderEvent.Completed("tool_calls")
                ),
                listOf(ProviderEvent.Delta("done"), ProviderEvent.Completed("stop"))
            )
        )
        val events = mutableListOf<AgentEvent>()
        withTimeout(10_000) {
            orchestrator(fake).runTurn(request()).collect { events.add(it) }
        }

        // Both calls are announced before either result, which is the signature of the parallel path.
        val invokedIndexes = events.withIndex()
            .filter { it.value is AgentEvent.ToolInvoked }
            .map { it.index }
        val firstResultIndex = events.indexOfFirst { it is AgentEvent.ToolResultEvent }
        assertThat(invokedIndexes).hasSize(2)
        assertThat(invokedIndexes.all { it < firstResultIndex }).isTrue()
        // Both reads returned their file's contents, in the model's own call order.
        val results = events.filterIsInstance<AgentEvent.ToolResultEvent>()
        assertThat(results).hasSize(2)
        assertThat(results[0].summary).contains("alpha")
        assertThat(results[1].summary).contains("bravo")
        assertThat(events.filterIsInstance<AgentEvent.Completed>()).hasSize(1)
    }

    @Test
    fun `a mutating call in a step is never run alongside a read-only call`() = runBlocking {
        File(root, "App.kt").writeText("old")
        val fake = FakeAdapter(
            listOf(
                listOf(
                    ProviderEvent.ToolCallRequested(ToolCall("c1", "read_file", """{"path":"App.kt"}""")),
                    ProviderEvent.ToolCallRequested(
                        ToolCall("c2", "propose_edit", """{"path":"App.kt","new_content":"new"}""")
                    ),
                    ProviderEvent.Completed("tool_calls")
                ),
                listOf(ProviderEvent.Delta("done"), ProviderEvent.Completed("stop"))
            )
        )
        val orch = orchestrator(fake)
        val events = mutableListOf<AgentEvent>()
        val job = launch {
            orch.runTurn(request()).collect { event ->
                events.add(event)
                if (event is AgentEvent.ApprovalRequired) launch { delay(100); orch.resolveApproval("s1", true) }
            }
        }
        withTimeout(10_000) { job.join() }

        // The edit still goes through the approval gate, and the file is only written after it.
        assertThat(events.filterIsInstance<AgentEvent.ApprovalRequired>()).hasSize(1)
        assertThat(File(root, "App.kt").readText()).isEqualTo("new")
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
