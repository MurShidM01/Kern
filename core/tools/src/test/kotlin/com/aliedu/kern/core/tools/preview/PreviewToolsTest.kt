package com.aliedu.kern.core.tools.preview

import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import com.aliedu.kern.core.tools.ToolContext
import com.aliedu.kern.core.tools.devserver.DevServerState
import com.aliedu.kern.core.tools.devserver.FakeDevServerRegistry
import com.aliedu.kern.core.tools.devserver.server
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
import java.io.File

/** PRD §4.3 bullet 8: the agent drives and inspects the live preview. */
class PreviewToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var context: ToolContext
    private lateinit var controller: PreviewController
    private lateinit var registry: FakeDevServerRegistry
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Before
    fun setUp() {
        root = temp.newFolder("ws")
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
        controller = PreviewController()
        registry = FakeDevServerRegistry()
    }

    private fun args(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    private fun attachHost() {
        controller.attach(object : PreviewHost {
            val loaded = mutableListOf<String>()
            override suspend fun load(url: String) {
                loaded.add(url)
            }

            override suspend fun reload() = Unit
            override suspend fun goBack() = Unit
            override suspend fun goForward() = Unit
            override suspend fun evaluate(js: String): String? = "42"
            override suspend fun screenshot(): ByteArray? = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        })
    }

    // ---- preview_open ----

    @Test
    fun `preview_open defaults to the first running server`(): Unit = runBlocking {
        registry.setServers(
            listOf(
                server(id = "srv0", state = DevServerState.FAILED, port = 3000),
                server(id = "srv1", state = DevServerState.RUNNING, port = 8000)
            )
        )
        attachHost()

        val result = PreviewOpenTool(controller, registry).execute(args("{}"), context)

        assertThat(result.success).isTrue()
        assertThat(controller.state.value.url).isEqualTo("http://127.0.0.1:8000")
        assertThat(result.output).contains("http://127.0.0.1:8000")
    }

    @Test
    fun `preview_open accepts an explicit url`(): Unit = runBlocking {
        attachHost()
        val result = PreviewOpenTool(controller, registry).execute(
            args("""{"url":"http://127.0.0.1:5173/"}"""),
            context
        )

        assertThat(result.success).isTrue()
        assertThat(controller.state.value.url).isEqualTo("http://127.0.0.1:5173/")
    }

    @Test
    fun `preview_open fails when nothing is running`(): Unit = runBlocking {
        val result = PreviewOpenTool(controller, registry).execute(args("{}"), context)
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("server_start")
    }

    @Test
    fun `preview_open asks the app to show the preview`(): Unit = runBlocking {
        registry.setServers(listOf(server(id = "srv1", state = DevServerState.RUNNING)))
        val before = controller.openSignal.value

        PreviewOpenTool(controller, registry).execute(args("{}"), context)

        assertThat(controller.openSignal.value).isEqualTo(before + 1)
    }

    // ---- preview_status ----

    @Test
    fun `preview_status lists servers and the current preview`(): Unit = runBlocking {
        registry.setServers(
            listOf(
                server(id = "srv1", state = DevServerState.RUNNING, port = 8000),
                server(id = "srv2", state = DevServerState.FAILED, port = null, command = "npm run dev")
            )
        )
        controller.onPageStarted("http://127.0.0.1:8000/")

        val result = PreviewStatusTool(controller, registry).execute(args("{}"), context)

        assertThat(result.success).isTrue()
        assertThat(result.output).contains("http://127.0.0.1:8000")
        assertThat(result.output).contains("id=srv1")
        assertThat(result.output).contains("RUNNING")
        assertThat(result.output).contains("id=srv2")
        assertThat(result.output).contains("FAILED")
    }

    @Test
    fun `preview_status says when nothing is loaded`(): Unit = runBlocking {
        val result = PreviewStatusTool(controller, registry).execute(args("{}"), context)
        assertThat(result.output).contains("No servers are running")
        assertThat(result.output).contains("nothing loaded")
    }

    // ---- preview_console ----

    @Test
    fun `preview_console returns captured lines with their level`(): Unit = runBlocking {
        controller.onConsole(ConsoleLevel.LOG, "hello", "app.js", 3)
        controller.onConsole(ConsoleLevel.ERROR, "boom", "app.js", 9)

        val result = PreviewConsoleTool(controller).execute(args("{}"), context)

        assertThat(result.success).isTrue()
        assertThat(result.output).contains("[LOG] (app.js:3) hello")
        assertThat(result.output).contains("[ERROR] (app.js:9) boom")
    }

    @Test
    fun `preview_console filters by level`(): Unit = runBlocking {
        controller.onConsole(ConsoleLevel.LOG, "hello", null, 0)
        controller.onConsole(ConsoleLevel.ERROR, "boom", null, 0)

        val result = PreviewConsoleTool(controller).execute(args("""{"level":"error"}"""), context)

        assertThat(result.output).contains("boom")
        assertThat(result.output).doesNotContain("hello")
    }

    @Test
    fun `preview_console keeps only the newest lines up to the limit`(): Unit = runBlocking {
        repeat(10) { controller.onConsole(ConsoleLevel.LOG, "line $it", null, 0) }

        val result = PreviewConsoleTool(controller).execute(args("""{"limit":3}"""), context)

        assertThat(result.output).contains("line 9")
        assertThat(result.output).contains("line 7")
        assertThat(result.output).doesNotContain("line 6")
        assertThat(result.output).contains("showing the newest 3 of 10")
    }

    @Test
    fun `preview_console clears the buffer when asked`(): Unit = runBlocking {
        controller.onConsole(ConsoleLevel.LOG, "hello", null, 0)

        PreviewConsoleTool(controller).execute(args("""{"clear":true}"""), context)

        assertThat(controller.console.value).isEmpty()
    }

    @Test
    fun `preview_console reports an empty buffer honestly`(): Unit = runBlocking {
        val result = PreviewConsoleTool(controller).execute(args("{}"), context)
        assertThat(result.output).contains("console is empty")
    }

    // ---- preview_eval ----

    @Test
    fun `preview_eval returns the value the page produced`(): Unit = runBlocking {
        attachHost()
        val result = PreviewEvalTool(controller).execute(args("""{"js":"1 + 41"}"""), context)

        assertThat(result.success).isTrue()
        assertThat(result.output).isEqualTo("42")
    }

    @Test
    fun `preview_eval explains that the preview must be open`(): Unit = runBlocking {
        val result = PreviewEvalTool(controller).execute(args("""{"js":"document.title"}"""), context)

        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("preview_open")
    }

    @Test
    fun `preview_eval requires the js argument`(): Unit = runBlocking {
        attachHost()
        val result = PreviewEvalTool(controller).execute(args("{}"), context)
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("js")
    }

    // ---- preview_screenshot ----

    @Test
    fun `preview_screenshot writes a png into the workspace`(): Unit = runBlocking {
        attachHost()
        val result = PreviewScreenshotTool(controller).execute(args("""{"path":"shot.png"}"""), context)

        assertThat(result.success).isTrue()
        assertThat(File(root, "shot.png").readBytes()).hasLength(4)
    }

    @Test
    fun `preview_screenshot refuses when the preview is closed`(): Unit = runBlocking {
        val result = PreviewScreenshotTool(controller).execute(args("{}"), context)
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("preview_open")
    }
}
