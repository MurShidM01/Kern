package com.aliedu.kern.core.tools

import com.aliedu.kern.core.model.GrantState
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
import java.io.File

/**
 * The Git tools shell out to the real CLI, so status and diff cannot drift from what the user sees
 * in a terminal. A commit must always be confirmed, and a message with a quote must not break the
 * command.
 */
class GitToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var context: ToolContext

    @Before
    fun setUp() {
        root = temp.newFolder("ws")
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

    private fun args(json: String = "{}"): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun makeRepo() = File(root, ".git").mkdirs()

    @Test
    fun `status proposes the porcelain branch view`() = runBlocking {
        makeRepo()
        val result = GitStatusTool().execute(args(), context)

        assertThat(result.success).isTrue()
        val request = result.commandRequest!!
        assertThat(request.command).isEqualTo("git status --short --branch")
        assertThat(request.impact).isEqualTo(CommandImpact.READ_ONLY)
        assertThat(request.alwaysApprove).isFalse()
    }

    @Test
    fun `diff defaults to unstaged and can be staged or scoped`() = runBlocking {
        makeRepo()
        val tool = GitDiffTool()

        assertThat(tool.execute(args(), context).commandRequest!!.command).isEqualTo("git diff")
        assertThat(tool.execute(args("""{"staged":true}"""), context).commandRequest!!.command)
            .isEqualTo("git diff --staged")
        assertThat(tool.execute(args("""{"path":"src/App.kt"}"""), context).commandRequest!!.command)
            .isEqualTo("git diff -- 'src/App.kt'")
    }

    @Test
    fun `commit is always confirmed even in permissive modes`() = runBlocking {
        makeRepo()
        val result = GitCommitTool().execute(args("""{"message":"fix: thing"}"""), context)

        assertThat(result.success).isTrue()
        val request = result.commandRequest!!
        assertThat(request.command).isEqualTo("git commit -m 'fix: thing'")
        assertThat(request.impact).isEqualTo(CommandImpact.MUTATING)
        assertThat(request.alwaysApprove).isTrue()
    }

    @Test
    fun `commit can stage everything first`() = runBlocking {
        makeRepo()
        val result = GitCommitTool().execute(
            args("""{"message":"feat: all","add_all":true}"""),
            context
        )
        assertThat(result.commandRequest!!.command).isEqualTo("git add -A && git commit -m 'feat: all'")
    }

    @Test
    fun `a message containing a quote is shell escaped`() = runBlocking {
        makeRepo()
        val result = GitCommitTool().execute(args("""{"message":"don't break"}"""), context)

        assertThat(result.commandRequest!!.command).isEqualTo("git commit -m 'don'\\''t break'")
    }

    @Test
    fun `a missing message is rejected`() = runBlocking {
        makeRepo()
        val result = GitCommitTool().execute(args(), context)
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("message")
    }

    @Test
    fun `a non-repository workspace is refused`() = runBlocking {
        val result = GitStatusTool().execute(args(), context)
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("not a Git repository")
    }
}
