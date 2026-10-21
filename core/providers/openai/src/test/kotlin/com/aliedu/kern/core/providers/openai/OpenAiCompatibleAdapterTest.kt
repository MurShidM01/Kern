package com.aliedu.kern.core.providers.openai

import com.aliedu.kern.core.model.MessageRole
import com.aliedu.kern.core.model.ProviderCapabilities
import com.aliedu.kern.core.model.ProviderProfile
import com.aliedu.kern.core.model.ProviderProtocol
import com.aliedu.kern.core.providers.api.AgentMessage
import com.aliedu.kern.core.providers.api.AgentRequest
import com.aliedu.kern.core.providers.api.ProviderErrorType
import com.aliedu.kern.core.providers.api.ProviderEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Backs P1-05 / P1-06 / T1-05, T1-06: live Test Connection, streaming accumulation, tool-call
 * accumulation, and sanitized error normalization against a real HTTP server (MockWebServer).
 */
class OpenAiCompatibleAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: OpenAiCompatibleAdapter

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = OpenAiCompatibleAdapter(OkHttpClient(), json)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun profile(): ProviderProfile = ProviderProfile(
        id = "p1",
        displayName = "Test Gateway",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        modelId = "test-model",
        credentialRef = null,
        customHeaders = emptyMap(),
        isDefault = true,
        connectTimeoutMs = 5000,
        readTimeoutMs = 5000,
        capabilities = ProviderCapabilities(),
        createdAt = 0,
        updatedAt = 0
    )

    @Test
    fun `test connection verifies the chat endpoint and reports the model count`() = runBlocking {
        server.enqueue(json(200, """{"choices":[]}"""))
        server.enqueue(json(200, """{"data":[{"id":"test-model","owned_by":"gw"},{"id":"other"}]}"""))

        val result = adapter.testConnection(profile(), apiKey = "sk-test")

        assertThat(result.success).isTrue()
        assertThat(result.modelsProbed).isEqualTo(2)
        assertThat(result.message).contains("2 model(s)")
        // The chat call is what proves the credential; the listing only decorates the message.
        assertThat(server.takeRequest().path).isEqualTo("/v1/chat/completions")
        assertThat(server.takeRequest().path).isEqualTo("/v1/models")
    }

    @Test
    fun `test connection fails when only an unauthenticated model listing succeeds`() = runBlocking {
        // Gateways such as NVIDIA NIM answer `GET /models` with no credential at all, so a profile
        // whose key is missing must not be reported as connected on the strength of that listing.
        server.enqueue(json(401, """{"error":{"message":"invalid api key"}}"""))
        server.enqueue(json(200, """{"data":[{"id":"test-model"}]}"""))

        val result = adapter.testConnection(profile(), apiKey = null)

        assertThat(result.success).isFalse()
        assertThat(result.message).contains("Authentication failed")
        assertThat(server.takeRequest().path).isEqualTo("/v1/chat/completions")
    }

    @Test
    fun `test connection succeeds when the gateway exposes no model listing`() = runBlocking {
        server.enqueue(json(200, """{"choices":[]}"""))
        server.enqueue(MockResponse().setResponseCode(404))

        val result = adapter.testConnection(profile(), apiKey = "sk-test")

        assertThat(result.success).isTrue()
        assertThat(result.message).contains("chat endpoint")
    }

    @Test
    fun `test connection reports the provider's own reason for a 404`() = runBlocking {
        // "Check the BASE_URL" alone sends the user looking in the wrong place; the gateway's own
        // text ("model not found") is what actually identifies the problem.
        server.enqueue(
            json(404, """{"error":{"message":"model deepseek-ai/nope is not found"}}""")
        )

        val result = adapter.testConnection(profile(), apiKey = "sk-test")

        assertThat(result.success).isFalse()
        assertThat(result.message).contains("The endpoint was not found (HTTP 404)")
        assertThat(result.message).contains("model deepseek-ai/nope is not found")
    }

    @Test
    fun `test connection surfaces an auth error for a wrong key`() = runBlocking {
        server.enqueue(
            json(401, """{"error":{"message":"invalid api key"}}""")
        )

        val result = adapter.testConnection(profile(), apiKey = "sk-wrong")

        assertThat(result.success).isFalse()
        assertThat(result.message).contains("Authentication failed")
    }

    @Test
    fun `streaming deltas are emitted in order and completed once`() = runBlocking {
        server.enqueue(sse("""
            data: {"choices":[{"delta":{"content":"Hel"}}]}

            data: {"choices":[{"delta":{"content":"lo"}}]}

            data: [DONE]

        """.trimIndent()))

        val events = adapter.streamResponse(profile(), "sk-test", request()).toList()

        val text = events.filterIsInstance<ProviderEvent.Delta>().joinToString("") { it.text }
        assertThat(text).isEqualTo("Hello")
        assertThat(events.filterIsInstance<ProviderEvent.Completed>()).hasSize(1)
    }

    @Test
    fun `streamed tool call fragments are accumulated into one call`() = runBlocking {
        server.enqueue(sse("""
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":"{\"pa"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"App.kt\"}"}}]}}]}

            data: [DONE]

        """.trimIndent()))

        val events = adapter.streamResponse(profile(), "sk-test", request()).toList()
        val calls = events.filterIsInstance<ProviderEvent.ToolCallRequested>().map { it.toolCall }

        assertThat(calls).hasSize(1)
        assertThat(calls.first().name).isEqualTo("read_file")
        assertThat(calls.first().argumentsJson).isEqualTo("""{"path":"App.kt"}""")
    }

    @Test
    fun `malformed stream chunk is skipped without aborting the stream`() = runBlocking {
        server.enqueue(sse("""
            data: not-json-at-all

            data: {"choices":[{"delta":{"content":"ok"}}]}

            data: [DONE]

        """.trimIndent()))

        val events = adapter.streamResponse(profile(), "sk-test", request()).toList()
        val text = events.filterIsInstance<ProviderEvent.Delta>().joinToString("") { it.text }

        assertThat(text).isEqualTo("ok")
        assertThat(events.filterIsInstance<ProviderEvent.Failed>()).isEmpty()
    }

    @Test
    fun `an http error on stream is normalized to a sanitized failure`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"boom"}}""")
        )

        val events = adapter.streamResponse(profile(), "sk-test", request()).toList()
        val failure = events.filterIsInstance<ProviderEvent.Failed>().singleOrNull()

        assertThat(failure).isNotNull()
        assertThat(failure!!.error.type).isEqualTo(ProviderErrorType.SERVER)
        assertThat(failure.error.userMessage).doesNotContain("sk-")
    }

    @Test
    fun `unreachable host normalizes to a network error`() {
        val bad = profile().copy(baseUrl = "http://127.0.0.1:1")
        val error = adapter.normalizeError(java.net.ConnectException("refused"))
        assertThat(error.type).isEqualTo(ProviderErrorType.NETWORK)
        // Sanity: the profile copy is only here to document intent; no live call is made.
        assertThat(bad.baseUrl).contains("127.0.0.1:1")
    }

    @Test
    fun `capabilities advertise streaming and tool calling`() {
        val caps = adapter.capabilities(profile())
        assertThat(caps.streaming).isTrue()
        assertThat(caps.toolCalling).isTrue()
        assertThat(caps.modelDiscovery).isTrue()
    }

    private fun request(): AgentRequest = AgentRequest(
        model = "test-model",
        messages = listOf(AgentMessage(MessageRole.USER, "hi")),
        tools = emptyList()
    )

    private fun sse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body + "\n")

    private fun json(code: Int, body: String): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
