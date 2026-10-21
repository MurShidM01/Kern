package com.aliedu.kern.core.data.security

import com.aliedu.kern.core.data.local.dao.CredentialRecordDao
import com.aliedu.kern.core.data.mapper.toDomain
import com.aliedu.kern.core.data.mapper.toEntity
import com.aliedu.kern.core.model.AuthMethod
import com.aliedu.kern.core.model.CredentialRecord
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores and retrieves provider credentials. Plaintext only ever exists in memory; what is
 * persisted is AES-GCM ciphertext whose key lives in the Android Keystore (PRD 14.1, 15.2).
 */
interface CredentialStore {
    suspend fun saveApiKey(profileId: String, apiKey: String)
    suspend fun getApiKey(profileId: String): String?
    suspend fun hasCredential(profileId: String): Boolean
    suspend fun delete(profileId: String)
}

@Singleton
class RoomKeystoreCredentialStore @Inject constructor(
    private val dao: CredentialRecordDao,
    private val cipher: CredentialCipher
) : CredentialStore {

    override suspend fun saveApiKey(profileId: String, apiKey: String) {
        val encrypted = cipher.encrypt(apiKey)
        val existing = dao.getByProfileId(profileId)
        val record = CredentialRecord(
            id = existing?.id ?: UUID.randomUUID().toString(),
            providerProfileId = profileId,
            credentialType = AuthMethod.API_KEY,
            ciphertext = encrypted.ciphertext,
            iv = encrypted.iv,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            expiresAt = null
        )
        dao.upsert(record.toEntity())
    }

    override suspend fun getApiKey(profileId: String): String? {
        val entity = dao.getByProfileId(profileId) ?: return null
        val record = entity.toDomain()
        return runCatching { cipher.decrypt(EncryptedPayload(record.ciphertext, record.iv)) }
            .getOrNull()
    }

    override suspend fun hasCredential(profileId: String): Boolean =
        dao.getByProfileId(profileId) != null

    override suspend fun delete(profileId: String) = dao.deleteByProfileId(profileId)
}
