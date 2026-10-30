package com.aliedu.kern.core.tools.devserver

import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import com.aliedu.kern.core.tools.CommandImpact
import com.aliedu.kern.core.tools.CommandRequest
import com.aliedu.kern.core.tools.ToolContext
import com.aliedu.kern.core.workspace.AppManagedWorkspaceAdapter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** PRD §4.3 bullet 8: a server is proposed like any command, but flagged for background launch. */
class ServerToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: ToolContext
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Before
    fun setUp() {
        val root = temp.newFolder("ws")
        val adapter = AppManagedWorkspaceAdapter(root, "ws")
        val workspace = Workspace(
            id = "ws",
            displayName = "Fixture",
            sourceType = WorkspaceSourceType.APP_MANAGED,
            uriString = null,
            localPath = root.absolutePath,
            grantState = GrantState.NOT_APPLICABLE,
            lastOpenedAt = 0,
            createdAt = 0
        )
        context = ToolContext(sessionId = "s1", workspace = workspace, adapter = adapter)
    }

    private fun args(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    @Test
    fun `server_start proposes a detached command with the given port`(): Unit = runBlocking {
        val result = ServerStartTool().execute(
            args("""{"command":"python3 -m http.server 8000","port":8000,"reason":"serve the app"}"""),
            context
        )

        assertThat(result.success).isTrue()
        assertThat(result.requiresApproval).isTrue()
        val request = result.commandRequest!!
        assertThat(request.detach).isTrue()
        assertThat(request.port).isEqualTo(8000)
        assertThat(request.command).isEqualTo("python3 -m http.server 8000")
        assertThat(request.reason).isEqualTo("serve the app")
    }

    @Test
    fun `server_start classifies the command impact like run_command`(): Unit = runBlocking {
        val result = ServerStartTool().execute(args("""{"command":"rm -rf build && npm start"}"""), context)
        assertThat(result.commandRequest!!.impact).isEqualTo(CommandImpact.DESTRUCTIVE)
    }

    @Test
    fun `server_start without a port leaves it for discovery`(): Unit = runBlocking {
        val result = ServerStartTool().execute(args("""{"command":"npm run dev"}"""), context)
        assertThat(result.commandRequest!!.port).isNull()
    }

    @Test
    fun `server_start rejects an out-of-range port rather than passing it on`(): Unit = runBlocking {
        val result = ServerStartTool().execute(args("""{"command":"npm start","port":99999}"""), context)
        assertThat(result.commandRequest!!.port).isNull()
    }

    @Test
    fun `server_start requires a command`(): Unit = runBlocking {
        val result = ServerStartTool().execute(args("{}"), context)
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("command")
    }

    @Test
    fun `server_stop stops a known server`(): Unit = runBlocking {
        val registry = FakeDevServerRegistry()
        registry.setServers(listOf(server(id = "srv1", state = DevServerState.RUNNING)))
        val result = ServerStopTool(registry).execute(args("""{"id":"srv1"}"""), context)

        assertThat(result.success).isTrue()
        assertThat(registry.stopped).containsExactly("srv1")
    }

    @Test
    fun `server_stop reports an unknown id`(): Unit = runBlocking {
        val registry = FakeDevServerRegistry()
        val result = ServerStopTool(registry).execute(args("""{"id":"nope"}"""), context)

        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("nope")
    }
}

internal fun server(
    id: String,
    state: DevServerState,
    port: Int? = 8000,
    command: String = "python3 -m http.server 8000",
    log: String = ""
) = DevServer(
    id = id,
    command = command,
    port = port,
    url = port?.let { localPreviewUrl(it) },
    state = state,
    startedAt = 0,
    sessionId = "sess-$id",
    log = log
)
