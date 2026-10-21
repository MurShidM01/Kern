package com.aliedu.kern.core.agent

import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.Message
import com.aliedu.kern.core.model.MessageRole
import com.aliedu.kern.core.model.MessageStatus
import com.aliedu.kern.core.model.Todo
import com.aliedu.kern.core.model.TodoStatus
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Backs P1-07 / PRD 9.3: the provider context keeps a system prompt first and the newest user turn
 * last, dropping the oldest history to stay within the byte budget.
 */
class ContextBuilderTest {

    private val builder = ContextBuilder(ContextCompactor())

    private fun workspace() = Workspace(
        id = "ws",
        displayName = "My Project",
        sourceType = WorkspaceSourceType.APP_MANAGED,
        uriString = null,
        localPath = null,
        grantState = GrantState.NOT_APPLICABLE,
        lastOpenedAt = 0,
        createdAt = 0
    )

    private fun message(role: MessageRole, content: String, seq: Long) = Message(
        id = "m$seq",
        sessionId = "s",
        role = role,
        content = content,
        status = MessageStatus.COMPLETE,
        providerMessageId = null,
        createdAt = seq,
        seq = seq
    )

    @Test
    fun `system prompt names the workspace and lists the tools`() {
        val prompt = builder.systemPrompt(workspace())
        assertThat(prompt).contains("My Project")
        assertThat(prompt).contains("list_files")
        assertThat(prompt).contains("read_file")
        assertThat(prompt).contains("search_files")
        assertThat(prompt).contains("propose_edit")
        assertThat(prompt).contains("untrusted data")
    }

    @Test
    fun `system message is first and the new user input is last`() {
        val history = listOf(
            message(MessageRole.USER, "first question", 1),
            message(MessageRole.ASSISTANT, "first answer", 2)
        )
        val messages = builder.buildMessages(workspace(), history, "new question", maxBytes = 10_000)

        assertThat(messages.first().role).isEqualTo(MessageRole.SYSTEM)
        assertThat(messages.last().role).isEqualTo(MessageRole.USER)
        assertThat(messages.last().content).isEqualTo("new question")
    }

    @Test
    fun `history is replayed in chronological order`() {
        val history = listOf(
            message(MessageRole.USER, "q1", 1),
            message(MessageRole.ASSISTANT, "a1", 2),
            message(MessageRole.USER, "q2", 3),
            message(MessageRole.ASSISTANT, "a2", 4)
        )
        val messages = builder.buildMessages(workspace(), history, "q3", maxBytes = 10_000)
        val contents = messages.filter { it.role != MessageRole.SYSTEM }.map { it.content }
        assertThat(contents).containsExactly("q1", "a1", "q2", "a2", "q3").inOrder()
    }

    @Test
    fun `older turns that exceed the budget are compacted rather than silently dropped`() {
        val history = listOf(
            message(MessageRole.USER, "OLD-" + "x".repeat(500), 1),
            message(MessageRole.ASSISTANT, "RECENT-" + "y".repeat(100), 2)
        )
        // Budget only fits the recent turn plus the new input.
        val messages = builder.buildMessages(workspace(), history, "now", maxBytes = 200)
        val joined = messages.joinToString("|") { it.content }

        // The recent turn survives verbatim; the older one is folded into a lossy digest.
        assertThat(joined).contains("RECENT-")
        assertThat(joined).contains("compacted to save context")
        assertThat(joined).contains("User asked: OLD-")
        assertThat(messages.last().content).isEqualTo("now")
    }

    @Test
    fun `nothing is compacted when the whole history fits`() {
        val history = listOf(
            message(MessageRole.USER, "q1", 1),
            message(MessageRole.ASSISTANT, "a1", 2)
        )
        val messages = builder.buildMessages(workspace(), history, "q2", maxBytes = 10_000)
        assertThat(messages.map { it.content }.any { it.contains("compacted to save context") }).isFalse()
    }

    @Test
    fun `failed and blank messages are never replayed`() {
        val history = listOf(
            message(MessageRole.USER, "kept", 1),
            message(MessageRole.ASSISTANT, "  ", 2),
            Message(
                id = "m3", sessionId = "s", role = MessageRole.ASSISTANT, content = "failed",
                status = MessageStatus.FAILED, providerMessageId = null, createdAt = 3, seq = 3
            )
        )
        val messages = builder.buildMessages(workspace(), history, "next", maxBytes = 10_000)
        val contents = messages.map { it.content }
        assertThat(contents).contains("kept")
        assertThat(contents).doesNotContain("failed")
        assertThat(contents.none { it.isBlank() }).isTrue()
    }

    @Test
    fun `a cancelled half answer is not replayed on retry`() {
        val history = listOf(
            message(MessageRole.USER, "do the thing", 1),
            Message(
                id = "m2", sessionId = "s", role = MessageRole.ASSISTANT, content = "half an answer",
                status = MessageStatus.CANCELLED, providerMessageId = null, createdAt = 2, seq = 2
            )
        )
        val messages = builder.buildMessages(workspace(), history, "do the thing", maxBytes = 10_000)
        assertThat(messages.map { it.content }).doesNotContain("half an answer")
    }

    @Test
    fun `the persisted task list is injected so a resumed turn keeps its plan`() {
        val todos = listOf(
            Todo("t1", "s", "Read the code", TodoStatus.COMPLETED, 0, 0, 0),
            Todo("t2", "s", "Change the parser", TodoStatus.IN_PROGRESS, 1, 0, 0),
            Todo("t2b", "s", "Run the tests", TodoStatus.PENDING, 2, 0, 0)
        )
        val messages = builder.buildMessages(
            workspace(), emptyList(), "continue", maxBytes = 10_000, todos = todos
        )
        val system = messages.first().content

        assertThat(system).contains("Current task list")
        assertThat(system).contains("[x] Read the code")
        assertThat(system).contains("[>] Change the parser")
        assertThat(system).contains("[ ] Run the tests")
    }

    @Test
    fun `no task list block is added when there are no todos`() {
        val messages = builder.buildMessages(workspace(), emptyList(), "hi", maxBytes = 10_000)
        assertThat(messages.first().content).doesNotContain("Current task list")
    }
}
