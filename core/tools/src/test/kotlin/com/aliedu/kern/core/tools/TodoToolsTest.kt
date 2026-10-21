package com.aliedu.kern.core.tools

import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.TodoStatus
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import com.aliedu.kern.core.workspace.AppManagedWorkspaceAdapter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** FR-018: update_todos parses the model's task list and hands it back for the UI to persist. */
class TodoToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: ToolContext
    private val tool = UpdateTodosTool()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Before
    fun setUp() {
        val root = temp.newFolder("ws")
        val adapter = AppManagedWorkspaceAdapter(root, "ws")
        val workspace = Workspace(
            id = "ws",
            displayName = "Fixture",
            sourceType = WorkspaceSourceType.APP_MANAGED,
            uriString = null,
            localPath = root.absolutePath,
            grantState = GrantState.NOT_APPLICABLE,
            lastOpenedAt = 0,
            createdAt = 0
        )
        context = ToolContext(sessionId = "s1", workspace = workspace, adapter = adapter)
    }

    private fun args(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    @Test
    fun `parses the full list preserving order and status`() = runBlocking {
        val result = tool.execute(
            args(
                """{"todos":[{"content":"First","status":"in_progress"},""" +
                    """{"content":"Second","status":"completed"}]}"""
            ),
            context
        )
        assertThat(result.success).isTrue()
        val todos = result.todos!!
        assertThat(todos).hasSize(2)
        assertThat(todos[0].content).isEqualTo("First")
        assertThat(todos[0].status).isEqualTo(TodoStatus.IN_PROGRESS)
        assertThat(todos[0].order).isEqualTo(0)
        assertThat(todos[1].status).isEqualTo(TodoStatus.COMPLETED)
        assertThat(todos[1].id).isEqualTo("s1#1")
    }

    @Test
    fun `unknown status falls back to pending and blank items are skipped`() = runBlocking {
        val result = tool.execute(
            args("""{"todos":[{"content":"  ","status":"pending"},{"content":"Keep me","status":"whatever"}]}"""),
            context
        )
        val todos = result.todos!!
        assertThat(todos).hasSize(1)
        assertThat(todos[0].content).isEqualTo("Keep me")
        assertThat(todos[0].status).isEqualTo(TodoStatus.PENDING)
    }

    @Test
    fun `missing array fails cleanly`() = runBlocking {
        val result = tool.execute(args("{}"), context)
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("todos")
    }

    @Test
    fun `an empty list clears the task list`() = runBlocking {
        val result = tool.execute(args("""{"todos":[]}"""), context)
        assertThat(result.success).isTrue()
        assertThat(result.todos).isEmpty()
    }
}
