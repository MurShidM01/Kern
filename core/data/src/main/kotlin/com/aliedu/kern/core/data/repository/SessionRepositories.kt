package com.aliedu.kern.core.data.repository

import androidx.room.withTransaction
import com.aliedu.kern.core.data.local.KernDatabase
import com.aliedu.kern.core.data.local.dao.MessageDao
import com.aliedu.kern.core.data.local.dao.PendingPatchDao
import com.aliedu.kern.core.data.local.dao.SessionDao
import com.aliedu.kern.core.data.local.dao.TodoDao
import com.aliedu.kern.core.data.local.dao.ToolEventDao
import com.aliedu.kern.core.data.mapper.toDomain
import com.aliedu.kern.core.data.mapper.toEntity
import com.aliedu.kern.core.model.Message
import com.aliedu.kern.core.model.MessageStatus
import com.aliedu.kern.core.model.PatchStatus
import com.aliedu.kern.core.model.PendingPatch
import com.aliedu.kern.core.model.Session
import com.aliedu.kern.core.model.SessionStatus
import com.aliedu.kern.core.model.Todo
import com.aliedu.kern.core.model.ToolEvent
import com.aliedu.kern.core.model.ToolEventStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface SessionRepository {
    fun observeSessions(): Flow<List<Session>>
    fun observeSessionsForWorkspace(workspaceId: String): Flow<List<Session>>
    fun observeSession(id: String): Flow<Session?>
    fun observeSessionCount(): Flow<Int>
    suspend fun getSession(id: String): Session?
    suspend fun upsert(session: Session)
    suspend fun updateStatus(id: String, status: SessionStatus, timestamp: Long)
    suspend fun updateTitle(id: String, title: String, timestamp: Long)
    suspend fun delete(id: String)

    /** Removes every session (and, by cascade, its messages, tool events and pending patches). */
    suspend fun deleteAllSessions()
}

@Singleton
class DefaultSessionRepository @Inject constructor(
    private val dao: SessionDao
) : SessionRepository {
    override fun observeSessions(): Flow<List<Session>> =
        dao.observeAll().map { it.map { e -> e.toDomain() } }

    override fun observeSessionsForWorkspace(workspaceId: String): Flow<List<Session>> =
        dao.observeByWorkspace(workspaceId).map { it.map { e -> e.toDomain() } }

    override fun observeSession(id: String): Flow<Session?> =
        dao.observeById(id).map { it?.toDomain() }

    override fun observeSessionCount(): Flow<Int> = dao.observeCount()

    override suspend fun getSession(id: String): Session? = dao.getById(id)?.toDomain()

    override suspend fun upsert(session: Session) = dao.upsert(session.toEntity())

    override suspend fun updateStatus(id: String, status: SessionStatus, timestamp: Long) =
        dao.updateStatus(id, status.name, timestamp, timestamp)

    override suspend fun updateTitle(id: String, title: String, timestamp: Long) =
        dao.updateTitle(id, title, timestamp)

    override suspend fun delete(id: String) = dao.deleteById(id)

    override suspend fun deleteAllSessions() = dao.deleteAll()
}

interface MessageRepository {
    fun observeMessages(sessionId: String): Flow<List<Message>>
    suspend fun append(message: Message)
    suspend fun updateContentAndStatus(id: String, content: String, status: MessageStatus)
    suspend fun delete(id: String, sessionId: String)
    suspend fun lastMessage(sessionId: String): Message?
    suspend fun nextSeq(sessionId: String): Long
}

@Singleton
class DefaultMessageRepository @Inject constructor(
    private val dao: MessageDao
) : MessageRepository {
    override fun observeMessages(sessionId: String): Flow<List<Message>> =
        dao.observeBySession(sessionId).map { it.map { e -> e.toDomain() } }

    override suspend fun append(message: Message) = dao.upsert(message.toEntity())

    override suspend fun updateContentAndStatus(id: String, content: String, status: MessageStatus) =
        dao.updateContentAndStatus(id, content, status.name)

    override suspend fun delete(id: String, sessionId: String) = dao.deleteInSession(sessionId, id)

    override suspend fun lastMessage(sessionId: String): Message? = dao.getLast(sessionId)?.toDomain()

    override suspend fun nextSeq(sessionId: String): Long = (dao.maxSeq(sessionId) ?: -1L) + 1L
}

interface ToolEventRepository {
    fun observeToolEvents(sessionId: String): Flow<List<ToolEvent>>
    suspend fun upsert(event: ToolEvent)
    suspend fun updateResult(
        id: String,
        status: ToolEventStatus,
        summary: String?,
        durationMs: Long?,
        linesAdded: Int = 0,
        linesRemoved: Int = 0,
        diff: String? = null
    )
}

@Singleton
class DefaultToolEventRepository @Inject constructor(
    private val dao: ToolEventDao
) : ToolEventRepository {
    override fun observeToolEvents(sessionId: String): Flow<List<ToolEvent>> =
        dao.observeBySession(sessionId).map { it.map { e -> e.toDomain() } }

    override suspend fun upsert(event: ToolEvent) = dao.upsert(event.toEntity())

    override suspend fun updateResult(
        id: String,
        status: ToolEventStatus,
        summary: String?,
        durationMs: Long?,
        linesAdded: Int,
        linesRemoved: Int,
        diff: String?
    ) = dao.updateResult(id, status.name, summary, durationMs, linesAdded, linesRemoved, diff)
}

interface TodoRepository {
    fun observeTodos(sessionId: String): Flow<List<Todo>>
    suspend fun replaceForSession(sessionId: String, todos: List<Todo>)
}

@Singleton
class DefaultTodoRepository @Inject constructor(
    private val dao: TodoDao
) : TodoRepository {
    override fun observeTodos(sessionId: String): Flow<List<Todo>> =
        dao.observeBySession(sessionId).map { it.map { e -> e.toDomain() } }

    override suspend fun replaceForSession(sessionId: String, todos: List<Todo>) =
        dao.replaceForSession(sessionId, todos.map { it.toEntity() })
}

interface PatchRepository {
    suspend fun getById(id: String): PendingPatch?
    suspend fun proposedForSession(sessionId: String): List<PendingPatch>
    suspend fun upsert(patch: PendingPatch)
    suspend fun updateStatus(id: String, status: PatchStatus, appliedAt: Long?)
}

@Singleton
class DefaultPatchRepository @Inject constructor(
    private val dao: PendingPatchDao
) : PatchRepository {
    override suspend fun getById(id: String): PendingPatch? = dao.getById(id)?.toDomain()

    override suspend fun proposedForSession(sessionId: String): List<PendingPatch> =
        dao.getProposedForSession(sessionId).map { it.toDomain() }

    override suspend fun upsert(patch: PendingPatch) = dao.upsert(patch.toEntity())

    override suspend fun updateStatus(id: String, status: PatchStatus, appliedAt: Long?) =
        dao.updateStatus(id, status.name, appliedAt)
}

/** Groups a message + its tool event write so a partial stream can't corrupt history (PRD 14.2). */
@Singleton
class SessionTransactionRunner @Inject constructor(
    private val database: KernDatabase
) {
    suspend fun <R> inTransaction(block: suspend () -> R): R = database.withTransaction { block() }
}
