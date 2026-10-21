package com.aliedu.kern.core.data.repository

import com.aliedu.kern.core.data.local.dao.ProviderProfileDao
import com.aliedu.kern.core.data.mapper.toDomain
import com.aliedu.kern.core.data.mapper.toEntity
import com.aliedu.kern.core.model.ProviderProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface ProviderRepository {
    fun observeProfiles(): Flow<List<ProviderProfile>>
    fun observeDefaultProfile(): Flow<ProviderProfile?>
    suspend fun getProfile(id: String): ProviderProfile?
    suspend fun getDefaultProfile(): ProviderProfile?
    suspend fun upsert(profile: ProviderProfile)
    suspend fun setDefault(id: String)
    suspend fun delete(id: String)
}

@Singleton
class DefaultProviderRepository @Inject constructor(
    private val dao: ProviderProfileDao
) : ProviderRepository {

    override fun observeProfiles(): Flow<List<ProviderProfile>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeDefaultProfile(): Flow<ProviderProfile?> =
        dao.observeDefault().map { it?.toDomain() }

    override suspend fun getProfile(id: String): ProviderProfile? = dao.getById(id)?.toDomain()

    override suspend fun getDefaultProfile(): ProviderProfile? = dao.getDefault()?.toDomain()

    override suspend fun upsert(profile: ProviderProfile) = dao.upsert(profile.toEntity())

    override suspend fun setDefault(id: String) {
        dao.clearDefault()
        dao.markDefault(id)
    }

    override suspend fun delete(id: String) = dao.deleteById(id)
}
