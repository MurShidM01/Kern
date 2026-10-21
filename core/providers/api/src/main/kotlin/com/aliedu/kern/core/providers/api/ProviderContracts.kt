package com.aliedu.kern.core.providers.api

import com.aliedu.kern.core.model.MessageRole
import com.aliedu.kern.core.model.ProviderCapabilities
import com.aliedu.kern.core.model.ProviderProfile
import kotlinx.coroutines.flow.Flow

/**
 * Provider-neutral contracts (PRD 10.2, 13.4). UI and agent tool contracts depend only on these
 * types; no provider-specific DTO leaks into the domain or Compose layer (PRD 13.6).
 */

/** A single message in a provider request. [toolCalls] is set on assistant messages. */
data class AgentMessage(
    val role: MessageRole,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null
)

/** A tool the model may call, described by a JSON-schema parameter object. */
data class ToolSchema(
    val name: String,
    val description: String,
    val parametersJsonSchema: String
)

/** A concrete tool invocation requested by the model. */
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

data class AgentRequest(
    val model: String,
    val messages: List<AgentMessage>,
    val tools: List<ToolSchema> = emptyList(),
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val providerOptions: Map<String, String> = emptyMap()
)

/** Events emitted while streaming a response (PRD 10.2 ProviderEvent). */
sealed interface ProviderEvent {
    data class Delta(val text: String) : ProviderEvent

    /** Chain-of-thought / reasoning tokens some models stream separately from the answer. */
    data class ReasoningDelta(val text: String) : ProviderEvent

    data class ToolCallRequested(val toolCall: ToolCall) : ProviderEvent
    data class Usage(
        val promptTokens: Int?,
        val completionTokens: Int?,
        val totalTokens: Int?
    ) : ProviderEvent

    data class Completed(val finishReason: String?) : ProviderEvent
    data class Failed(val error: ProviderError) : ProviderEvent
}

enum class ProviderErrorType {
    NETWORK, TIMEOUT, AUTH, RATE_LIMIT, SERVER, MALFORMED, CANCELLED, UNSUPPORTED, UNKNOWN
}

/** Sanitized, user-safe provider error (PRD 10.2 normalizeError, PRIV-04). */
data class ProviderError(
    val type: ProviderErrorType,
    val userMessage: String,
    val technical: String? = null
)

data class ConnectionResult(
    val success: Boolean,
    val message: String,
    val latencyMs: Long,
    val modelsProbed: Int = 0
)

data class ModelInfo(val id: String, val ownedBy: String? = null)

interface AiProviderAdapter {
    suspend fun testConnection(profile: ProviderProfile, apiKey: String?): ConnectionResult
    fun streamResponse(
        profile: ProviderProfile,
        apiKey: String?,
        request: AgentRequest
    ): Flow<ProviderEvent>

    suspend fun listModels(profile: ProviderProfile, apiKey: String?): Result<List<ModelInfo>>
    fun normalizeError(error: Throwable): ProviderError
    fun capabilities(profile: ProviderProfile): ProviderCapabilities
}

/** Normalizes arbitrary exceptions into user-safe [ProviderError]s. */
object ProviderErrors {
    fun from(throwable: Throwable): ProviderError = when (throwable) {
        is ProviderHttpException -> throwable.toProviderError()
        is java.net.SocketTimeoutException -> ProviderError(
            ProviderErrorType.TIMEOUT,
            "The provider did not respond in time. Check the network and try again."
        )

        is java.net.UnknownHostException -> ProviderError(
            ProviderErrorType.NETWORK,
            "Could not reach the provider host. Check the BASE_URL and your connection."
        )

        is java.net.ConnectException -> ProviderError(
            ProviderErrorType.NETWORK,
            "Could not connect to the provider. Verify the endpoint and that it is reachable."
        )

        is javax.net.ssl.SSLException -> ProviderError(
            ProviderErrorType.NETWORK,
            "Secure connection failed. Verify the endpoint uses a valid HTTPS certificate."
        )

        is kotlinx.coroutines.CancellationException -> ProviderError(
            ProviderErrorType.CANCELLED,
            "The request was cancelled."
        )

        is java.io.IOException -> ProviderError(
            ProviderErrorType.NETWORK,
            "A network error occurred while contacting the provider."
        )

        else -> ProviderError(
            ProviderErrorType.UNKNOWN,
            "Something went wrong while contacting the provider."
        )
    }
}

/** Thrown by adapters for non-2xx HTTP responses, carrying a sanitized message. */
class ProviderHttpException(
    val statusCode: Int,
    val sanitizedBody: String?
) : Exception("HTTP $statusCode") {
    fun toProviderError(): ProviderError {
        val type = when (statusCode) {
            401, 403 -> ProviderErrorType.AUTH
            404 -> ProviderErrorType.UNSUPPORTED
            408 -> ProviderErrorType.TIMEOUT
            429 -> ProviderErrorType.RATE_LIMIT
            in 500..599 -> ProviderErrorType.SERVER
            else -> ProviderErrorType.UNKNOWN
        }
        val message = when (type) {
            ProviderErrorType.AUTH -> "Authentication failed (HTTP $statusCode). Check the API key."
            ProviderErrorType.UNSUPPORTED -> "The endpoint was not found (HTTP 404). Check the BASE_URL."
            ProviderErrorType.RATE_LIMIT -> "Rate limited by the provider (HTTP 429). Try again shortly."
            ProviderErrorType.SERVER -> "The provider returned a server error (HTTP $statusCode)."
            ProviderErrorType.TIMEOUT -> "The provider timed out (HTTP 408)."
            else -> "The provider returned HTTP $statusCode."
        }
        return ProviderError(type, message, sanitizedBody)
    }
}
