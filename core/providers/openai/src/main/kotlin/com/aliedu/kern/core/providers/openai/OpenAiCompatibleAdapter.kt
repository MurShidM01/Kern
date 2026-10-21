package com.aliedu.kern.core.providers.openai

import com.aliedu.kern.core.common.logging.KernLog
import com.aliedu.kern.core.common.redaction.Redactor
import com.aliedu.kern.core.model.MessageRole
import com.aliedu.kern.core.model.ProviderCapabilities
import com.aliedu.kern.core.model.ProviderProfile
import com.aliedu.kern.core.providers.api.AgentMessage
import com.aliedu.kern.core.providers.api.AgentRequest
import com.aliedu.kern.core.providers.api.AiProviderAdapter
import com.aliedu.kern.core.providers.api.ConnectionResult
import com.aliedu.kern.core.providers.api.ModelInfo
import com.aliedu.kern.core.providers.api.ProviderError
import com.aliedu.kern.core.providers.api.ProviderErrorType
import com.aliedu.kern.core.providers.api.ProviderErrors
import com.aliedu.kern.core.providers.api.ProviderEvent
import com.aliedu.kern.core.providers.api.ProviderHttpException
import com.aliedu.kern.core.providers.api.ToolCall
import com.aliedu.kern.core.providers.openai.dto.ChatChunkDto
import com.aliedu.kern.core.providers.openai.dto.ChatMessageDto
import com.aliedu.kern.core.providers.openai.dto.ChatRequestDto
import com.aliedu.kern.core.providers.openai.dto.FunctionCallDto
import com.aliedu.kern.core.providers.openai.dto.FunctionDefDto
import com.aliedu.kern.core.providers.openai.dto.ModelsResponseDto
import com.aliedu.kern.core.providers.openai.dto.ToolCallDto
import com.aliedu.kern.core.providers.openai.dto.ToolCallDeltaDto
import com.aliedu.kern.core.providers.openai.dto.ToolDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI-compatible chat adapter (PRD 10). Streams `text/event-stream`, accumulates streamed
 * tool calls, and normalizes all errors to user-safe [ProviderError]s. Secrets are never logged.
 */
@Singleton
class OpenAiCompatibleAdapter @Inject constructor(
    private val baseClient: OkHttpClient,
    private val json: Json
) : AiProviderAdapter {

    /**
     * Verifies the profile against the **chat** endpoint — the request the app actually makes.
     *
     * `GET /models` cannot stand in for this. Some gateways (NVIDIA NIM, for one) answer the model
     * listing with no credential at all, so a profile whose key is missing or wrong would report
     * "connected" and then fail with `401` on the first real message. The model count is still
     * reported when the gateway exposes a listing, but it no longer decides the outcome.
     */
    override suspend fun testConnection(profile: ProviderProfile, apiKey: String?): ConnectionResult =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val failure = probeChat(profile, apiKey)
            if (failure != null) {
                return@withContext ConnectionResult(false, failure, System.currentTimeMillis() - start)
            }
            val latency = System.currentTimeMillis() - start
            val models = runCatching { fetchModelsInternal(profile, apiKey).size }.getOrNull()
            ConnectionResult(
                success = true,
                message = if (models != null) {
                    "Connected. $models model(s) available."
                } else {
                    "Connected (chat endpoint responded)."
                },
                latencyMs = latency,
                modelsProbed = models ?: 0
            )
        }

    /** Sends a one-token chat completion. Returns a user-facing reason on failure, `null` on success. */
    private fun probeChat(profile: ProviderProfile, apiKey: String?): String? = try {
        val request = buildRequest(
            profile = profile,
            apiKey = apiKey,
            url = chatUrl(profile),
            body = ChatRequestDto(
                model = profile.modelId,
                messages = listOf(ChatMessageDto(role = "user", content = "ping")),
                stream = false,
                maxTokens = 1
            )
        )
        clientFor(profile).newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                null
            } else {
                val error = ProviderHttpException(response.code, sanitizeBody(response)).toProviderError()
                explain(error, apiKey)
            }
        }
    } catch (t: Throwable) {
        normalizeError(t).userMessage
    }

    /**
     * The generic message plus the provider's own explanation.
     *
     * Gateways answer a 404 with a precise reason ("model not found", "account not entitled"), and
     * without it "Check the BASE_URL" sends the user looking in the wrong place. The detail is the
     * provider's own sanitized text, redacted against the key in case it echoes one back.
     */
    private fun explain(error: ProviderError, apiKey: String?): String {
        val detail = error.technical?.trim().orEmpty()
        if (detail.isEmpty()) return error.userMessage
        val safe = Redactor.redactText(detail, listOfNotNull(apiKey)).take(MAX_ERROR_CHARS)
        return if (safe.isEmpty() || error.userMessage.contains(safe)) error.userMessage
        else "${error.userMessage} Provider said: $safe"
    }

    override suspend fun listModels(profile: ProviderProfile, apiKey: String?): Result<List<ModelInfo>> =
        withContext(Dispatchers.IO) {
            runCatching { fetchModelsInternal(profile, apiKey) }
        }

    private fun fetchModelsInternal(profile: ProviderProfile, apiKey: String?): List<ModelInfo> {
        val request = buildRequest(profile, apiKey, modelsUrl(profile), get = true)
        clientFor(profile).newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ProviderHttpException(response.code, sanitizeBody(response))
            val body = response.body?.string().orEmpty()
            val parsed = json.decodeFromString<ModelsResponseDto>(body)
            return parsed.data.map { ModelInfo(it.id, it.ownedBy) }
        }
    }

    override fun streamResponse(
        profile: ProviderProfile,
        apiKey: String?,
        request: AgentRequest
    ): Flow<ProviderEvent> = callbackFlow {
        val bodyDto = ChatRequestDto(
            model = request.model,
            messages = request.messages.map { it.toDto(json) },
            stream = true,
            temperature = request.temperature,
            maxTokens = request.maxOutputTokens,
            tools = request.tools.takeIf { it.isNotEmpty() }?.map { schema ->
                ToolDto(
                    function = FunctionDefDto(
                        name = schema.name,
                        description = schema.description,
                        parameters = json.parseToJsonElement(schema.parametersJsonSchema)
                    )
                )
            }
        )
        val httpRequest = buildRequest(profile, apiKey, chatUrl(profile), body = bodyDto)

        val accumulator = ToolCallAccumulator()
        var finished = false

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    if (!finished) {
                        finished = true
                        accumulator.build().forEach { trySend(ProviderEvent.ToolCallRequested(it)) }
                        trySend(ProviderEvent.Completed(null))
                    }
                    return
                }
                val chunk = runCatching { json.decodeFromString<ChatChunkDto>(data) }.getOrNull() ?: return
                chunk.choices.firstOrNull()?.let { choice ->
                    choice.delta?.content?.takeIf { it.isNotEmpty() }?.let {
                        trySend(ProviderEvent.Delta(it))
                    }
                    (choice.delta?.reasoningContent ?: choice.delta?.reasoning ?: choice.delta?.thinking)
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { trySend(ProviderEvent.ReasoningDelta(it)) }
                    choice.delta?.toolCalls?.forEach { accumulator.add(it) }
                    if (choice.finishReason != null && !finished) {
                        finished = true
                        accumulator.build().forEach { trySend(ProviderEvent.ToolCallRequested(it)) }
                        trySend(ProviderEvent.Completed(choice.finishReason))
                    }
                }
                chunk.usage?.let {
                    trySend(ProviderEvent.Usage(it.promptTokens, it.completionTokens, it.totalTokens))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (!finished) {
                    finished = true
                    accumulator.build().forEach { trySend(ProviderEvent.ToolCallRequested(it)) }
                    trySend(ProviderEvent.Completed(null))
                }
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val error: ProviderError = when {
                    response != null && !response.isSuccessful ->
                        ProviderHttpException(response.code, sanitizeBody(response)).toProviderError()
                            .let { it.copy(userMessage = explain(it, apiKey)) }

                    t != null -> normalizeError(t)
                    else -> ProviderError(ProviderErrorType.UNKNOWN, "The provider stream ended unexpectedly.")
                }
                trySend(ProviderEvent.Failed(error))
                close()
            }
        }

        val eventSource = EventSources.createFactory(clientFor(profile)).newEventSource(httpRequest, listener)
        awaitClose { eventSource.cancel() }
    }

    override fun normalizeError(error: Throwable): ProviderError = ProviderErrors.from(error)

    override fun capabilities(profile: ProviderProfile): ProviderCapabilities =
        profile.capabilities.copy(streaming = true, toolCalling = true, modelDiscovery = true)

    // ---- helpers ----------------------------------------------------------------

    private fun clientFor(profile: ProviderProfile): OkHttpClient = baseClient.newBuilder()
        .connectTimeout(profile.connectTimeoutMs.coerceAtLeast(1000), TimeUnit.MILLISECONDS)
        .readTimeout(profile.readTimeoutMs.coerceAtLeast(1000), TimeUnit.MILLISECONDS)
        .writeTimeout(profile.readTimeoutMs.coerceAtLeast(1000), TimeUnit.MILLISECONDS)
        .build()

    private fun buildRequest(
        profile: ProviderProfile,
        apiKey: String?,
        url: String,
        get: Boolean = false,
        body: ChatRequestDto? = null
    ): Request {
        val builder = Request.Builder().url(url)
        // Follow the body: a non-streaming call must not ask for an event stream.
        val accept = if (!get && body?.stream == true) "text/event-stream" else "application/json"
        builder.header("Accept", accept)
        if (!apiKey.isNullOrBlank()) builder.header("Authorization", "Bearer $apiKey")
        profile.customHeaders.forEach { (k, v) -> builder.header(k, v) }
        if (get) {
            builder.get()
        } else {
            val payload = json.encodeToString(ChatRequestDto.serializer(), body!!)
            builder.post(payload.toRequestBody(JSON_MEDIA))
        }
        KernLog.d(TAG, "request -> ${Redactor.redactUrl(url)}")
        return builder.build()
    }

    private fun chatUrl(profile: ProviderProfile): String = normalizeBase(profile.baseUrl) + "/chat/completions"

    private fun modelsUrl(profile: ProviderProfile): String = normalizeBase(profile.baseUrl) + "/models"

    private fun normalizeBase(baseUrl: String): String =
        baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/models")

    private fun sanitizeBody(response: Response): String? = runCatching {
        val raw = response.peekBody(MAX_ERROR_BODY_BYTES).string()
        val message = runCatching {
            json.decodeFromString<com.aliedu.kern.core.providers.openai.dto.ErrorResponseDto>(raw)
                .error?.message
        }.getOrNull()
        val chosen = message ?: raw
        chosen.take(MAX_ERROR_CHARS)
    }.getOrNull()

    private fun AgentMessage.toDto(json: Json): ChatMessageDto = ChatMessageDto(
        role = when (role) {
            MessageRole.SYSTEM -> "system"
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.TOOL -> "tool"
        },
        content = content.ifEmpty { null },
        toolCalls = toolCalls.takeIf { it.isNotEmpty() }?.map {
            ToolCallDto(
                id = it.id,
                function = FunctionCallDto(name = it.name, arguments = it.argumentsJson)
            )
        },
        toolCallId = toolCallId,
        name = name
    )

    private class ToolCallAccumulator {
        private data class Entry(
            var id: String = "",
            var name: String = "",
            val arguments: StringBuilder = StringBuilder()
        )

        private val entries = sortedMapOf<Int, Entry>()

        fun add(delta: ToolCallDeltaDto) {
            val entry = entries.getOrPut(delta.index) { Entry() }
            delta.id?.let { if (it.isNotEmpty()) entry.id = it }
            delta.function?.name?.let { if (it.isNotEmpty()) entry.name = it }
            delta.function?.arguments?.let { entry.arguments.append(it) }
        }

        fun build(): List<ToolCall> = entries.values
            .filter { it.name.isNotEmpty() }
            .map {
                ToolCall(
                    id = it.id.ifEmpty { "call_${it.name}_${System.nanoTime()}" },
                    name = it.name,
                    argumentsJson = it.arguments.toString().ifBlank { "{}" }
                )
            }
    }

    private companion object {
        const val TAG = "OpenAiAdapter"
        const val MAX_ERROR_BODY_BYTES = 4096L
        const val MAX_ERROR_CHARS = 240
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
