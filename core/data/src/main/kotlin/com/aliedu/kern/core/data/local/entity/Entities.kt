package com.aliedu.kern.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities mirror PRD 14.1. Enum-like columns are stored as their [Enum.name] String and
 * converted in mappers; secrets are stored as Base64 ciphertext, never plaintext (PRD 15.2).
 */

@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val sourceType: String,
    val uriString: String?,
    val localPath: String?,
    val grantState: String,
    val lastOpenedAt: Long,
    val createdAt: Long,
    /** Real `/storage/emulated/0/...` path for a folder workspace, when all-files access is granted. */
    val realPath: String? = null
)

@Entity(tableName = "provider_profiles")
data class ProviderProfileEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val protocol: String,
    val baseUrl: String,
    val modelId: String,
    val credentialRef: String?,
    val customHeadersJson: String,
    val isDefault: Boolean,
    val connectTimeoutMs: Long,
    val readTimeoutMs: Long,
    val capabilitiesJson: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "credential_records",
    foreignKeys = [
        ForeignKey(
            entity = ProviderProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerProfileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("providerProfileId")]
)
data class CredentialRecordEntity(
    @PrimaryKey val id: String,
    val providerProfileId: String,
    val credentialType: String,
    val ciphertextBase64: String,
    val ivBase64: String,
    val createdAt: Long,
    val expiresAt: Long?
)

@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ProviderProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerProfileId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("workspaceId"), Index("providerProfileId")]
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val workspaceId: String?,
    val providerProfileId: String?,
    val title: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastEventAt: Long
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val status: String,
    val providerMessageId: String?,
    val createdAt: Long,
    val seq: Long,
    val reasoning: String?
)

@Entity(
    tableName = "tool_events",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class ToolEventEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val messageId: String?,
    val toolName: String,
    val argsRedacted: String,
    val status: String,
    val resultSummary: String?,
    val durationMs: Long?,
    val createdAt: Long,
    val linesAdded: Int = 0,
    val linesRemoved: Int = 0,
    /** Unified diff of the change this row applied, for the transcript preview. */
    val diff: String? = null
)

/** Agent task-list item (FR-018), persisted per session and replaced wholesale on each update. */
@Entity(
    tableName = "todos",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class TodoEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val content: String,
    val status: String,
    val orderIndex: Int,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "pending_patches",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class PendingPatchEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val patchHash: String,
    val filesJson: String,
    val status: String,
    val createdAt: Long,
    val appliedAt: Long?
)

@Entity(tableName = "local_preferences")
data class LocalPreferenceEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long
)
