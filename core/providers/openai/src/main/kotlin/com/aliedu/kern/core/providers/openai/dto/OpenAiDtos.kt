package com.aliedu.kern.core.providers.openai.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatRequestDto(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val tools: List<ToolDto>? = null
)

@Serializable
data class ChatMessageDto(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null
)

@Serializable
data class ToolCallDto(
    val id: String? = null,
    val type: String = "function",
    val function: FunctionCallDto? = null
)

@Serializable
data class FunctionCallDto(
    val name: String? = null,
    val arguments: String? = null
)

@Serializable
data class ToolDto(
    val type: String = "function",
    val function: FunctionDefDto
)

@Serializable
data class FunctionDefDto(
    val name: String,
    val description: String,
    val parameters: JsonElement
)

@Serializable
data class ChatChunkDto(
    val id: String? = null,
    val choices: List<ChoiceDto> = emptyList(),
    val usage: UsageDto? = null
)

@Serializable
data class ChoiceDto(
    val index: Int = 0,
    val delta: DeltaDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class DeltaDto(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
    /** Some gateways stream chain-of-thought under `thinking`. */
    val thinking: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDeltaDto>? = null
)

@Serializable
data class ToolCallDeltaDto(
    val index: Int = 0,
    val id: String? = null,
    val function: FunctionCallDto? = null
)

@Serializable
data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null
)

@Serializable
data class ModelsResponseDto(
    val data: List<ModelDto> = emptyList()
)

@Serializable
data class ModelDto(
    val id: String,
    @SerialName("owned_by") val ownedBy: String? = null
)

@Serializable
data class ErrorResponseDto(
    val error: ErrorBodyDto? = null
)

@Serializable
data class ErrorBodyDto(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null
)
