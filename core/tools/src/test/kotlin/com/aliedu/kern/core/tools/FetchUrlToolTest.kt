package com.aliedu.kern.core.tools

import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import com.aliedu.kern.core.workspace.AppManagedWorkspaceAdapter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `fetch_url` must be narrow: http/https only, text only, size-capped, and it must never leak a
 * credential that appears in the URL. A doc page should arrive readable, not as raw HTML soup.
 */
class FetchUrlToolTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var context: ToolContext
    private lateinit var server: MockWebServer
    private val tool = FetchUrlTool()

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
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun url(path: String = "/doc"): String = server.url(path).toString()

    @Test
    fun `an html page is reduced to readable text`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><head><style>p{color:red}</style></head><body><h1>Title</h1><p>Hello <b>world</b></p><script>var x=1</script></body></html>")
        )

        val result = tool.execute(args("""{"url":"${url()}"}"""), context)

        assertThat(result.success).isTrue()
        assertThat(result.output).contains("Title")
        assertThat(result.output).contains("Hello world")
        assertThat(result.output).doesNotContain("<b>")
        assertThat(result.output).doesNotContain("var x=1")
        assertThat(result.output).doesNotContain("color:red")
    }

    @Test
    fun `a json body is returned unchanged`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"name":"kern","ok":true}""")
        )

        val result = tool.execute(args("""{"url":"${url()}","max_chars":5000}"""), context)

        assertThat(result.success).isTrue()
        assertThat(result.output).isEqualTo("""{"name":"kern","ok":true}""")
    }

    @Test
    fun `binary content is refused`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "image/png")
                .setBody("fakepng")
        )

        val result = tool.execute(args("""{"url":"${url()}"}"""), context)

        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("only text")
    }

    @Test
    fun `a non http scheme is rejected before any request`() = runBlocking {
        val result = tool.execute(args("""{"url":"file:///etc/passwd"}"""), context)
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("http")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `an http error status is surfaced`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        val result = tool.execute(args("""{"url":"${url()}"}"""), context)
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("404")
    }

    @Test
    fun `a sensitive query parameter is redacted in error messages`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = tool.execute(
            args("""{"url":"${url()}?api_key=supersecret"}"""),
            context
        )
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).doesNotContain("supersecret")
        assertThat(result.errorMessage).contains("[REDACTED]")
    }

    @Test
    fun `long bodies are truncated at the requested cap`() = runBlocking {
        val long = "x".repeat(5_000)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/plain")
                .setBody(long)
        )

        val result = tool.execute(args("""{"url":"${url()}","max_chars":2000}"""), context)

        assertThat(result.success).isTrue()
        assertThat(result.output).contains("[truncated at 2000 characters]")
        assertThat(result.output.length).isLessThan(2_100)
    }
}
