package com.aliedu.kern.core.data.mapper

import com.aliedu.kern.core.data.local.entity.CredentialRecordEntity
import com.aliedu.kern.core.data.local.entity.MessageEntity
import com.aliedu.kern.core.data.local.entity.PendingPatchEntity
import com.aliedu.kern.core.data.local.entity.ProviderProfileEntity
import com.aliedu.kern.core.data.local.entity.SessionEntity
import com.aliedu.kern.core.data.local.entity.TodoEntity
import com.aliedu.kern.core.data.local.entity.ToolEventEntity
import com.aliedu.kern.core.data.local.entity.WorkspaceEntity
import com.aliedu.kern.core.model.AuthMethod
import com.aliedu.kern.core.model.CredentialRecord
import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.Message
import com.aliedu.kern.core.model.MessageRole
import com.aliedu.kern.core.model.MessageStatus
import com.aliedu.kern.core.model.PatchStatus
import com.aliedu.kern.core.model.PendingPatch
import com.aliedu.kern.core.model.ProviderCapabilities
import com.aliedu.kern.core.model.ProviderProfile
import com.aliedu.kern.core.model.ProviderProtocol
import com.aliedu.kern.core.model.Session
import com.aliedu.kern.core.model.SessionStatus
import com.aliedu.kern.core.model.Todo
import com.aliedu.kern.core.model.TodoStatus
import com.aliedu.kern.core.model.ToolEvent
import com.aliedu.kern.core.model.ToolEventStatus
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private inline fun <reified T : Enum<T>> enumOrDefault(name: String, default: T): T =
    runCatching { enumValueOf<T>(name) }.getOrDefault(default)

fun WorkspaceEntity.toDomain(): Workspace = Workspace(
    id = id,
    displayName = displayName,
    sourceType = enumOrDefault(sourceType, WorkspaceSourceType.SELECTED_FOLDER),
    uriString = uriString,
    localPath = localPath,
    grantState = enumOrDefault(grantState, GrantState.UNKNOWN),
    lastOpenedAt = lastOpenedAt,
    createdAt = createdAt,
    realPath = realPath
)

fun Workspace.toEntity(): WorkspaceEntity = WorkspaceEntity(
    id = id,
    displayName = displayName,
    sourceType = sourceType.name,
    uriString = uriString,
    localPath = localPath,
    grantState = grantState.name,
    lastOpenedAt = lastOpenedAt,
    createdAt = createdAt,
    realPath = realPath
)

fun ProviderProfileEntity.toDomain(): ProviderProfile = ProviderProfile(
    id = id,
    displayName = displayName,
    protocol = enumOrDefault(protocol, ProviderProtocol.OPENAI_COMPATIBLE),
    baseUrl = baseUrl,
    modelId = modelId,
    credentialRef = credentialRef,
    customHeaders = runCatching {
        json.decodeFromString<Map<String, String>>(customHeadersJson)
    }.getOrDefault(emptyMap()),
    isDefault = isDefault,
    connectTimeoutMs = connectTimeoutMs,
    readTimeoutMs = readTimeoutMs,
    capabilities = runCatching {
        json.decodeFromString<ProviderCapabilities>(capabilitiesJson)
    }.getOrDefault(ProviderCapabilities()),
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun ProviderProfile.toEntity(): ProviderProfileEntity = ProviderProfileEntity(
    id = id,
    displayName = displayName,
    protocol = protocol.name,
    baseUrl = baseUrl,
    modelId = modelId,
    credentialRef = credentialRef,
    customHeadersJson = json.encodeToString(customHeaders),
    isDefault = isDefault,
    connectTimeoutMs = connectTimeoutMs,
    readTimeoutMs = readTimeoutMs,
    capabilitiesJson = json.encodeToString(capabilities),
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun CredentialRecordEntity.toDomain(): CredentialRecord = CredentialRecord(
    id = id,
    providerProfileId = providerProfileId,
    credentialType = enumOrDefault(credentialType, AuthMethod.API_KEY),
    ciphertext = Base64.getDecoder().decode(ciphertextBase64),
    iv = Base64.getDecoder().decode(ivBase64),
    createdAt = createdAt,
    expiresAt = expiresAt
)

fun CredentialRecord.toEntity(): CredentialRecordEntity = CredentialRecordEntity(
    id = id,
    providerProfileId = providerProfileId,
    credentialType = credentialType.name,
    ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext),
    ivBase64 = Base64.getEncoder().encodeToString(iv),
    createdAt = createdAt,
    expiresAt = expiresAt
)

fun SessionEntity.toDomain(): Session = Session(
    id = id,
    workspaceId = workspaceId,
    providerProfileId = providerProfileId,
    title = title,
    status = enumOrDefault(status, SessionStatus.IDLE),
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastEventAt = lastEventAt
)

fun Session.toEntity(): SessionEntity = SessionEntity(
    id = id,
    workspaceId = workspaceId,
    providerProfileId = providerProfileId,
    title = title,
    status = status.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastEventAt = lastEventAt
)

fun MessageEntity.toDomain(): Message = Message(
    id = id,
    sessionId = sessionId,
    role = enumOrDefault(role, MessageRole.USER),
    content = content,
    status = enumOrDefault(status, MessageStatus.COMPLETE),
    providerMessageId = providerMessageId,
    createdAt = createdAt,
    seq = seq,
    reasoning = reasoning
)

fun Message.toEntity(): MessageEntity = MessageEntity(
    id = id,
    sessionId = sessionId,
    role = role.name,
    content = content,
    status = status.name,
    providerMessageId = providerMessageId,
    createdAt = createdAt,
    seq = seq,
    reasoning = reasoning
)

fun ToolEventEntity.toDomain(): ToolEvent = ToolEvent(
    id = id,
    sessionId = sessionId,
    messageId = messageId,
    toolName = toolName,
    argsRedacted = argsRedacted,
    status = enumOrDefault(status, ToolEventStatus.PENDING),
    resultSummary = resultSummary,
    durationMs = durationMs,
    createdAt = createdAt,
    linesAdded = linesAdded,
    linesRemoved = linesRemoved,
    diff = diff
)

fun ToolEvent.toEntity(): ToolEventEntity = ToolEventEntity(
    id = id,
    sessionId = sessionId,
    messageId = messageId,
    toolName = toolName,
    argsRedacted = argsRedacted,
    status = status.name,
    resultSummary = resultSummary,
    durationMs = durationMs,
    createdAt = createdAt,
    linesAdded = linesAdded,
    linesRemoved = linesRemoved,
    diff = diff
)

fun TodoEntity.toDomain(): Todo = Todo(
    id = id,
    sessionId = sessionId,
    content = content,
    status = enumOrDefault(status, TodoStatus.PENDING),
    order = orderIndex,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Todo.toEntity(): TodoEntity = TodoEntity(
    id = id,
    sessionId = sessionId,
    content = content,
    status = status.name,
    orderIndex = order,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun PendingPatchEntity.toDomain(): PendingPatch = PendingPatch(
    id = id,
    sessionId = sessionId,
    patchHash = patchHash,
    filesJson = filesJson,
    status = enumOrDefault(status, PatchStatus.PROPOSED),
    createdAt = createdAt,
    appliedAt = appliedAt
)

fun PendingPatch.toEntity(): PendingPatchEntity = PendingPatchEntity(
    id = id,
    sessionId = sessionId,
    patchHash = patchHash,
    filesJson = filesJson,
    status = status.name,
    createdAt = createdAt,
    appliedAt = appliedAt
)
