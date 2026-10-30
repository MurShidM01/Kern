package com.aliedu.kern.core.model

/** Whether a persisted SAF grant is still valid at the time of use (PRD 11.2). */
enum class GrantState { VALID, REVOKED, UNKNOWN, NOT_APPLICABLE }

/** PRD 14.1 Workspace. File contents are never stored in this row. */
data class Workspace(
    val id: String,
    val displayName: String,
    val sourceType: WorkspaceSourceType,
    val uriString: String?,
    val localPath: String?,
    val grantState: GrantState,
    val lastOpenedAt: Long,
    val createdAt: Long,
    /**
     * The real filesystem path behind a folder workspace when it lives under primary external storage
     * (`/storage/emulated/0/...`). Present only while all-files access is granted; it lets Files,
     * Editor, Agent tools and the Linux terminal share one tree instead of an execution mirror.
     * Null for app-managed workspaces and for folders that cannot be mapped.
     */
    val realPath: String? = null
)

/** Capability flags discovered/configured for a provider profile (PRD 10.1). */
@kotlinx.serialization.Serializable
data class ProviderCapabilities(
    val streaming: Boolean = true,
    val toolCalling: Boolean = false,
    val vision: Boolean = false,
    val modelDiscovery: Boolean = false
)

/** PRD 14.1 ProviderProfile. Never contains secret material. */
data class ProviderProfile(
    val id: String,
    val displayName: String,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val modelId: String,
    val credentialRef: String?,
    val customHeaders: Map<String, String>,
    val isDefault: Boolean,
    val connectTimeoutMs: Long,
    val readTimeoutMs: Long,
    val capabilities: ProviderCapabilities,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * PRD 14.1 CredentialRecord. [ciphertext] is AES-GCM output whose key lives in the Android
 * Keystore; the plaintext secret is never persisted or logged (PRD 15.2).
 */
class CredentialRecord(
    val id: String,
    val providerProfileId: String,
    val credentialType: AuthMethod,
    val ciphertext: ByteArray,
    val iv: ByteArray,
    val createdAt: Long,
    val expiresAt: Long?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CredentialRecord) return false
        return id == other.id &&
            providerProfileId == other.providerProfileId &&
            credentialType == other.credentialType &&
            ciphertext.contentEquals(other.ciphertext) &&
            iv.contentEquals(other.iv) &&
            createdAt == other.createdAt &&
            expiresAt == other.expiresAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + providerProfileId.hashCode()
        result = 31 * result + credentialType.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (expiresAt?.hashCode() ?: 0)
        return result
    }
}

/** PRD 14.1 Session. */
data class Session(
    val id: String,
    val workspaceId: String?,
    val providerProfileId: String?,
    val title: String,
    val status: SessionStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val lastEventAt: Long
)

/** PRD 14.1 Message. [seq] provides stable ordering independent of timestamp ties. */
data class Message(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val status: MessageStatus,
    val providerMessageId: String?,
    val createdAt: Long,
    val seq: Long,
    /** Assistant chain-of-thought shown above the message; never replayed to the model. */
    val reasoning: String? = null
)

/** PRD 14.1 ToolEvent. [argsRedacted] must already have secrets removed. */
data class ToolEvent(
    val id: String,
    val sessionId: String,
    val messageId: String?,
    val toolName: String,
    val argsRedacted: String,
    val status: ToolEventStatus,
    val resultSummary: String?,
    val durationMs: Long?,
    val createdAt: Long,
    /** Lines a file-writing tool added; 0 for every tool that does not edit files. */
    val linesAdded: Int = 0,
    /** Lines a file-writing tool removed. */
    val linesRemoved: Int = 0,
    /**
     * The unified diff a file-writing tool applied, kept so the transcript can show the same
     * green/red preview the approval sheet showed — the row is the record of the change, and a raw
     * tool-call JSON is not a reviewable one. Null for every tool that does not edit files.
     */
    val diff: String? = null
)

/** One item in the agent's task list (FR-018). [order] is the display position. */
data class Todo(
    val id: String,
    val sessionId: String,
    val content: String,
    val status: TodoStatus,
    val order: Int,
    val createdAt: Long,
    val updatedAt: Long
)

/** PRD 14.1 PendingPatch. Approval is tied to [patchHash] + current file versions. */
data class PendingPatch(
    val id: String,
    val sessionId: String,
    val patchHash: String,
    val filesJson: String,
    val status: PatchStatus,
    val createdAt: Long,
    val appliedAt: Long?
)

/** PRD 14.1 LocalPreference. Never stores secrets in plaintext. */
data class LocalPreference(
    val key: String,
    val value: String,
    val updatedAt: Long
)
