package com.aliedu.kern.core.data.mapper

import com.aliedu.kern.core.data.local.entity.MessageEntity
import com.aliedu.kern.core.data.local.entity.ProviderProfileEntity
import com.aliedu.kern.core.data.local.entity.TodoEntity
import com.aliedu.kern.core.data.local.entity.WorkspaceEntity
import com.aliedu.kern.core.model.AuthMethod
import com.aliedu.kern.core.model.CredentialRecord
import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.MessageRole
import com.aliedu.kern.core.model.MessageStatus
import com.aliedu.kern.core.model.ProviderCapabilities
import com.aliedu.kern.core.model.ProviderProfile
import com.aliedu.kern.core.model.ProviderProtocol
import com.aliedu.kern.core.model.Todo
import com.aliedu.kern.core.model.TodoStatus
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import com.aliedu.kern.core.data.local.entity.CredentialRecordEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Backs the persistence requirement: domain <-> Room entity round-trips must be lossless, and
 * unknown enum values must degrade to a safe default rather than crash on read.
 */
class MappersTest {

    @Test
    fun `workspace round-trips through its entity`() {
        val domain = Workspace(
            id = "w1", displayName = "Proj", sourceType = WorkspaceSourceType.SELECTED_FOLDER,
            uriString = "content://tree/abc", localPath = null, grantState = GrantState.VALID,
            lastOpenedAt = 10, createdAt = 5, realPath = "/storage/emulated/0/Proj"
        )
        assertThat(domain.toEntity().toDomain()).isEqualTo(domain)
    }

    @Test
    fun `provider profile round-trips custom headers and capabilities`() {
        val domain = ProviderProfile(
            id = "p1", displayName = "Gateway", protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://gw.example.com/v1", modelId = "gpt-4o",
            credentialRef = "cred1",
            customHeaders = mapOf("X-Org" to "acme", "X-Env" to "prod"),
            isDefault = true, connectTimeoutMs = 15000, readTimeoutMs = 60000,
            capabilities = ProviderCapabilities(streaming = true, toolCalling = true, vision = false, modelDiscovery = true),
            createdAt = 1, updatedAt = 2
        )
        val entity = domain.toEntity()
        assertThat(entity.customHeadersJson).contains("X-Org")
        assertThat(entity.toDomain()).isEqualTo(domain)
    }

    @Test
    fun `message round-trips including sequence`() {
        val entity = MessageEntity(
            id = "m1", sessionId = "s1", role = "ASSISTANT", content = "hi",
            status = "COMPLETE", providerMessageId = "pm", createdAt = 3, seq = 42,
            reasoning = "because"
        )
        val domain = entity.toDomain()
        assertThat(domain.role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(domain.status).isEqualTo(MessageStatus.COMPLETE)
        assertThat(domain.seq).isEqualTo(42)
        assertThat(domain.reasoning).isEqualTo("because")
        assertThat(domain.toEntity()).isEqualTo(entity)
    }

    @Test
    fun `todo round-trips through its entity`() {
        val domain = Todo(
            id = "s1#0", sessionId = "s1", content = "Read the file",
            status = TodoStatus.IN_PROGRESS, order = 2, createdAt = 1, updatedAt = 2
        )
        assertThat(domain.toEntity().toDomain()).isEqualTo(domain)
    }

    @Test
    fun `unknown todo status degrades to pending`() {
        val entity = TodoEntity(
            id = "s1#0", sessionId = "s1", content = "x", status = "WAT",
            orderIndex = 0, createdAt = 0, updatedAt = 0
        )
        assertThat(entity.toDomain().status).isEqualTo(TodoStatus.PENDING)
    }

    @Test
    fun `credential ciphertext and iv survive base64 encoding`() {
        val ciphertext = byteArrayOf(1, 2, 3, 4, 5)
        val iv = byteArrayOf(9, 8, 7)
        val domain = CredentialRecord(
            id = "c1", providerProfileId = "p1", credentialType = AuthMethod.API_KEY,
            ciphertext = ciphertext, iv = iv, createdAt = 1, expiresAt = null
        )
        val entity = domain.toEntity()
        // Secrets are stored as Base64 ciphertext, never plaintext.
        assertThat(entity.ciphertextBase64).doesNotContain("1,2,3")
        val restored = entity.toDomain()
        assertThat(restored.ciphertext).isEqualTo(ciphertext)
        assertThat(restored.iv).isEqualTo(iv)
        assertThat(restored).isEqualTo(domain)
    }

    @Test
    fun `unknown enum strings degrade to defaults instead of crashing`() {
        val entity = WorkspaceEntity(
            id = "w", displayName = "d", sourceType = "TOTALLY_UNKNOWN", uriString = null,
            localPath = null, grantState = "ALSO_UNKNOWN", lastOpenedAt = 0, createdAt = 0
        )
        val domain = entity.toDomain()
        assertThat(domain.sourceType).isEqualTo(WorkspaceSourceType.SELECTED_FOLDER)
        assertThat(domain.grantState).isEqualTo(GrantState.UNKNOWN)
    }

    @Test
    fun `malformed provider json degrades to empty defaults`() {
        val entity = ProviderProfileEntity(
            id = "p", displayName = "d", protocol = "OPENAI_COMPATIBLE", baseUrl = "u",
            modelId = "m", credentialRef = null, customHeadersJson = "not-json",
            isDefault = false, connectTimeoutMs = 1, readTimeoutMs = 1,
            capabilitiesJson = "not-json", createdAt = 0, updatedAt = 0
        )
        val domain = entity.toDomain()
        assertThat(domain.customHeaders).isEmpty()
        assertThat(domain.capabilities).isEqualTo(ProviderCapabilities())
    }
}
