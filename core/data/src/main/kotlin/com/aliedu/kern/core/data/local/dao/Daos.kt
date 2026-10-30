package com.aliedu.kern.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.aliedu.kern.core.data.local.entity.CredentialRecordEntity
import com.aliedu.kern.core.data.local.entity.LocalPreferenceEntity
import com.aliedu.kern.core.data.local.entity.MessageEntity
import com.aliedu.kern.core.data.local.entity.PendingPatchEntity
import com.aliedu.kern.core.data.local.entity.ProviderProfileEntity
import com.aliedu.kern.core.data.local.entity.SessionEntity
import com.aliedu.kern.core.data.local.entity.TodoEntity
import com.aliedu.kern.core.data.local.entity.ToolEventEntity
import com.aliedu.kern.core.data.local.entity.WorkspaceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun getById(id: String): WorkspaceEntity?

    /**
     * `@Upsert`, not `@Insert(REPLACE)`: REPLACE deletes the existing row and inserts a new one,
     * which fires the `ON DELETE SET NULL` on `sessions.workspaceId` and silently detaches the
     * workspace's sessions.
     */
    @Upsert
    suspend fun upsert(entity: WorkspaceEntity)

    @Query("UPDATE workspaces SET lastOpenedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: String, timestamp: Long)

    @Query("UPDATE workspaces SET grantState = :state WHERE id = :id")
    suspend fun updateGrantState(id: String, state: String)

    @Query("UPDATE workspaces SET realPath = :realPath WHERE id = :id")
    suspend fun updateRealPath(id: String, realPath: String?)

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ProviderProfileDao {
    @Query("SELECT * FROM provider_profiles ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProviderProfileEntity>>

    @Query("SELECT * FROM provider_profiles WHERE id = :id")
    suspend fun getById(id: String): ProviderProfileEntity?

    @Query("SELECT * FROM provider_profiles WHERE isDefault = 1 LIMIT 1")
    fun observeDefault(): Flow<ProviderProfileEntity?>

    @Query("SELECT * FROM provider_profiles WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): ProviderProfileEntity?

    /**
     * `@Upsert`, not `@Insert(REPLACE)`: REPLACE deletes the existing row and inserts a new one,
     * which fires the `ON DELETE CASCADE` on `credential_records` and silently deletes the
     * profile's stored API key — so picking a model from the models sheet would log the user out.
     */
    @Upsert
    suspend fun upsert(entity: ProviderProfileEntity)

    @Query("UPDATE provider_profiles SET isDefault = 0")
    suspend fun clearDefault()

    @Query("UPDATE provider_profiles SET isDefault = 1 WHERE id = :id")
    suspend fun markDefault(id: String)

    @Query("DELETE FROM provider_profiles WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface CredentialRecordDao {
    @Query("SELECT * FROM credential_records WHERE providerProfileId = :profileId LIMIT 1")
    suspend fun getByProfileId(profileId: String): CredentialRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CredentialRecordEntity)

    @Query("DELETE FROM credential_records WHERE providerProfileId = :profileId")
    suspend fun deleteByProfileId(profileId: String)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY lastEventAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE workspaceId = :workspaceId ORDER BY lastEventAt DESC")
    fun observeByWorkspace(workspaceId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeById(id: String): Flow<SessionEntity?>

    /**
     * `@Upsert`, not `@Insert(REPLACE)`: REPLACE deletes the existing row and inserts a new one,
     * which fires the `ON DELETE CASCADE` on `messages`, `tool_events`, `todos` and
     * `pending_patches` — wiping the session's history on any update.
     */
    @Upsert
    suspend fun upsert(entity: SessionEntity)

    @Query("UPDATE sessions SET status = :status, updatedAt = :updatedAt, lastEventAt = :lastEventAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long, lastEventAt: Long)

    @Query("UPDATE sessions SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM sessions")
    fun observeCount(): Flow<Int>
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY seq ASC")
    fun observeBySession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY seq DESC LIMIT 1")
    suspend fun getLast(sessionId: String): MessageEntity?

    @Query("SELECT MAX(seq) FROM messages WHERE sessionId = :sessionId")
    suspend fun maxSeq(sessionId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageEntity)

    @Query("UPDATE messages SET content = :content, status = :status WHERE id = :id")
    suspend fun updateContentAndStatus(id: String, content: String, status: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND id = :id")
    suspend fun deleteInSession(sessionId: String, id: String)
}

@Dao
interface ToolEventDao {
    @Query("SELECT * FROM tool_events WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeBySession(sessionId: String): Flow<List<ToolEventEntity>>

    /**
     * Closes every row that is still waiting on the agent. Stopping a turn kills the coroutine
     * that would have written the result, so without this the row keeps its spinner forever —
     * including across app restarts, because the status is persisted.
     */
    @Query(
        "UPDATE tool_events SET status = :status, resultSummary = :summary WHERE sessionId = :sessionId " +
            "AND status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL', 'APPROVED')"
    )
    suspend fun closeOpen(sessionId: String, status: String, summary: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ToolEventEntity)

    @Query(
        "UPDATE tool_events SET status = :status, resultSummary = :summary, durationMs = :durationMs, " +
            "linesAdded = :linesAdded, linesRemoved = :linesRemoved, diff = :diff WHERE id = :id"
    )
    suspend fun updateResult(
        id: String,
        status: String,
        summary: String?,
        durationMs: Long?,
        linesAdded: Int = 0,
        linesRemoved: Int = 0,
        diff: String? = null
    )
}

@Dao
interface TodoDao {
    @Query("SELECT * FROM todos WHERE sessionId = :sessionId ORDER BY orderIndex ASC")
    fun observeBySession(sessionId: String): Flow<List<TodoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<TodoEntity>)

    @Query("DELETE FROM todos WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    /** The agent always sends the full list, so replace-all keeps ordering and removal consistent. */
    @Transaction
    suspend fun replaceForSession(sessionId: String, entities: List<TodoEntity>) {
        deleteBySession(sessionId)
        if (entities.isNotEmpty()) upsertAll(entities)
    }
}

@Dao
interface PendingPatchDao {
    @Query("SELECT * FROM pending_patches WHERE id = :id")
    suspend fun getById(id: String): PendingPatchEntity?

    @Query("SELECT * FROM pending_patches WHERE sessionId = :sessionId AND status = 'PROPOSED' ORDER BY createdAt DESC")
    suspend fun getProposedForSession(sessionId: String): List<PendingPatchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingPatchEntity)

    @Query("UPDATE pending_patches SET status = :status, appliedAt = :appliedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, appliedAt: Long?)
}

@Dao
interface LocalPreferenceDao {
    @Query("SELECT * FROM local_preferences")
    suspend fun getAll(): List<LocalPreferenceEntity>

    @Query("SELECT * FROM local_preferences WHERE `key` = :key")
    suspend fun get(key: String): LocalPreferenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalPreferenceEntity)

    @Query("DELETE FROM local_preferences WHERE `key` = :key")
    suspend fun delete(key: String)
}
