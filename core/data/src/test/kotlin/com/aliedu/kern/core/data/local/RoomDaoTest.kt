package com.aliedu.kern.core.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aliedu.kern.core.data.local.entity.CredentialRecordEntity
import com.aliedu.kern.core.data.local.entity.MessageEntity
import com.aliedu.kern.core.data.local.entity.PendingPatchEntity
import com.aliedu.kern.core.data.local.entity.ProviderProfileEntity
import com.aliedu.kern.core.data.local.entity.SessionEntity
import com.aliedu.kern.core.data.local.entity.TodoEntity
import com.aliedu.kern.core.data.local.entity.WorkspaceEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration test over a real (in-memory) Room database: proves the persistence contract for
 * workspaces, providers, sessions and messages that backs Part 1 session persistence (T1-12).
 */
@RunWith(RobolectricTestRunner::class)
class RoomDaoTest {

    private lateinit var db: KernDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KernDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun workspace(id: String, lastOpenedAt: Long = 0) = WorkspaceEntity(
        id = id, displayName = "ws-$id", sourceType = "APP_MANAGED", uriString = null,
        localPath = null, grantState = "NOT_APPLICABLE", lastOpenedAt = lastOpenedAt, createdAt = 0
    )

    @Test
    fun `workspace upsert, ordering, touch and grant update work`() = runBlocking {
        val dao = db.workspaceDao()
        dao.upsert(workspace("a", lastOpenedAt = 1))
        dao.upsert(workspace("b", lastOpenedAt = 2))

        assertThat(dao.observeAll().first().map { it.id }).containsExactly("b", "a").inOrder()

        dao.touch("a", 99)
        assertThat(dao.getById("a")!!.lastOpenedAt).isEqualTo(99)

        dao.updateGrantState("a", "REVOKED")
        assertThat(dao.getById("a")!!.grantState).isEqualTo("REVOKED")

        dao.deleteById("a")
        assertThat(dao.getById("a")).isNull()
    }

    @Test
    fun `only one provider can be the default at a time`() = runBlocking {
        val dao = db.providerProfileDao()
        dao.upsert(provider("p1", isDefault = true))
        dao.upsert(provider("p2", isDefault = false))

        dao.clearDefault()
        dao.markDefault("p2")

        assertThat(dao.getDefault()!!.id).isEqualTo("p2")
        assertThat(dao.observeDefault().first()!!.id).isEqualTo("p2")
    }

    @Test
    fun `re-saving a provider keeps its stored credential`() = runBlocking {
        // Regression: the profile upsert used `INSERT OR REPLACE`, whose implicit delete fired the
        // `ON DELETE CASCADE` on credential_records — so picking a model from the models sheet
        // silently deleted the profile's API key and every later request failed with 401.
        val profiles = db.providerProfileDao()
        val credentials = db.credentialRecordDao()
        profiles.upsert(provider("p1", isDefault = true))
        credentials.upsert(credential("c1", "p1"))

        profiles.upsert(provider("p1", isDefault = true).copy(modelId = "other-model"))

        assertThat(credentials.getByProfileId("p1")).isNotNull()
        assertThat(profiles.getById("p1")!!.modelId).isEqualTo("other-model")
    }

    @Test
    fun `re-saving a session keeps its messages`() = runBlocking {
        // Regression: the same REPLACE-delete would cascade into messages, tool_events, todos and
        // pending_patches, wiping a session's history on any update.
        val sessions = db.sessionDao()
        sessions.upsert(session("s1"))
        db.messageDao().upsert(message("m1", "s1", seq = 1, content = "x"))

        sessions.upsert(session("s1").copy(title = "renamed"))

        assertThat(db.messageDao().observeBySession("s1").first().map { it.id }).containsExactly("m1")
        assertThat(sessions.getById("s1")!!.title).isEqualTo("renamed")
    }

    @Test
    fun `re-saving a workspace keeps its sessions attached`() = runBlocking {
        db.workspaceDao().upsert(workspace("w1"))
        db.sessionDao().upsert(session("s1").copy(workspaceId = "w1"))

        db.workspaceDao().upsert(workspace("w1", lastOpenedAt = 5))

        assertThat(db.sessionDao().getById("s1")!!.workspaceId).isEqualTo("w1")
    }

    @Test
    fun `messages are ordered by sequence and maxSeq is tracked`() = runBlocking {
        val dao = db.messageDao()
        db.sessionDao().upsert(session("s1"))
        dao.upsert(message("m2", "s1", seq = 2, content = "second"))
        dao.upsert(message("m1", "s1", seq = 1, content = "first"))

        assertThat(dao.observeBySession("s1").first().map { it.content })
            .containsExactly("first", "second").inOrder()
        assertThat(dao.maxSeq("s1")).isEqualTo(2)
        assertThat(dao.getLast("s1")!!.content).isEqualTo("second")
    }

    @Test
    fun `deleting a session cascades to its messages`() = runBlocking {
        db.sessionDao().upsert(session("s1"))
        db.messageDao().upsert(message("m1", "s1", seq = 1, content = "x"))

        db.sessionDao().deleteById("s1")

        assertThat(db.messageDao().observeBySession("s1").first()).isEmpty()
    }

    @Test
    fun `only proposed patches are returned for a session`(): Unit = runBlocking {
        db.sessionDao().upsert(session("s1"))
        db.pendingPatchDao().upsert(patch("pp1", "s1", status = "PROPOSED"))
        db.pendingPatchDao().upsert(patch("pp2", "s1", status = "APPLIED"))

        val proposed = db.pendingPatchDao().getProposedForSession("s1")
        assertThat(proposed.map { it.id }).containsExactly("pp1")
    }

    @Test
    fun `todos replace-all keeps order and cascades on session delete`() = runBlocking {
        db.sessionDao().upsert(session("s1"))
        val dao = db.todoDao()
        dao.replaceForSession(
            "s1",
            listOf(
                todo("t2", "s1", order = 1, content = "second"),
                todo("t1", "s1", order = 0, content = "first")
            )
        )
        assertThat(dao.observeBySession("s1").first().map { it.content })
            .containsExactly("first", "second").inOrder()

        // A second call replaces the whole list.
        dao.replaceForSession("s1", listOf(todo("t3", "s1", order = 0, content = "only")))
        assertThat(dao.observeBySession("s1").first().map { it.id }).containsExactly("t3")

        db.sessionDao().deleteById("s1")
        assertThat(dao.observeBySession("s1").first()).isEmpty()
    }

    private fun provider(id: String, isDefault: Boolean) = ProviderProfileEntity(
        id = id, displayName = "p-$id", protocol = "OPENAI_COMPATIBLE", baseUrl = "https://x",
        modelId = "m", credentialRef = null, customHeadersJson = "{}", isDefault = isDefault,
        connectTimeoutMs = 1000, readTimeoutMs = 1000, capabilitiesJson = "{}",
        createdAt = 0, updatedAt = 0
    )

    private fun credential(id: String, profileId: String) = CredentialRecordEntity(
        id = id, providerProfileId = profileId, credentialType = "API_KEY",
        ciphertextBase64 = "ct", ivBase64 = "iv", createdAt = 0, expiresAt = null
    )

    private fun session(id: String) = SessionEntity(
        id = id, workspaceId = null, providerProfileId = null, title = "t",
        status = "IDLE", createdAt = 0, updatedAt = 0, lastEventAt = 0
    )

    private fun message(id: String, sessionId: String, seq: Long, content: String) = MessageEntity(
        id = id, sessionId = sessionId, role = "USER", content = content, status = "COMPLETE",
        providerMessageId = null, createdAt = seq, seq = seq, reasoning = null
    )

    private fun todo(id: String, sessionId: String, order: Int, content: String) = TodoEntity(
        id = id, sessionId = sessionId, content = content, status = "PENDING",
        orderIndex = order, createdAt = 0, updatedAt = 0
    )

    private fun patch(id: String, sessionId: String, status: String) = PendingPatchEntity(
        id = id, sessionId = sessionId, patchHash = "h", filesJson = "[]",
        status = status, createdAt = 0, appliedAt = null
    )
}
