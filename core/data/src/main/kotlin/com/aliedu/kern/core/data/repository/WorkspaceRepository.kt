package com.aliedu.kern.core.data.repository

import com.aliedu.kern.core.data.local.dao.WorkspaceDao
import com.aliedu.kern.core.data.mapper.toDomain
import com.aliedu.kern.core.data.mapper.toEntity
import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface WorkspaceRepository {
    fun observeWorkspaces(): Flow<List<Workspace>>
    suspend fun getWorkspace(id: String): Workspace?
    suspend fun upsert(workspace: Workspace)
    suspend fun touch(id: String, timestamp: Long)
    suspend fun updateGrantState(id: String, state: GrantState)

    /** Records (or clears) the real `/storage/emulated/0/...` path behind a folder workspace. */
    suspend fun updateRealPath(id: String, realPath: String?)
    suspend fun delete(id: String)
}

@Singleton
class DefaultWorkspaceRepository @Inject constructor(
    private val dao: WorkspaceDao
) : WorkspaceRepository {

    override fun observeWorkspaces(): Flow<List<Workspace>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getWorkspace(id: String): Workspace? = dao.getById(id)?.toDomain()

    override suspend fun upsert(workspace: Workspace) = dao.upsert(workspace.toEntity())

    override suspend fun touch(id: String, timestamp: Long) = dao.touch(id, timestamp)

    override suspend fun updateGrantState(id: String, state: GrantState) =
        dao.updateGrantState(id, state.name)

    override suspend fun updateRealPath(id: String, realPath: String?) =
        dao.updateRealPath(id, realPath)

    override suspend fun delete(id: String) = dao.deleteById(id)
}
