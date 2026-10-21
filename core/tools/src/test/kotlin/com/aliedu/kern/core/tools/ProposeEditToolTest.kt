package com.aliedu.kern.core.tools

import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import com.aliedu.kern.core.tools.diff.DiffService
import com.aliedu.kern.core.workspace.AppManagedWorkspaceAdapter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Backs P1-07 / T1-08: propose_edit only produces a reviewable proposal with requiresApproval and
 * must never write to disk on its own.
 */
class ProposeEditToolTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var adapter: AppManagedWorkspaceAdapter
    private lateinit var context: ToolContext
    private val tool = ProposeEditTool(DiffService())

    @Before
    fun setUp() {
        root = temp.newFolder("ws")
        adapter = AppManagedWorkspaceAdapter(root, "ws")
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

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun `proposing an edit produces a proposal and does not write`() = runBlocking {
        File(root, "App.kt").writeText("fun main() {}")

        val result = tool.execute(
            args("path" to "App.kt", "new_content" to "fun main() { println(\"hi\") }"),
            context
        )

        assertThat(result.success).isTrue()
        assertThat(result.requiresApproval).isTrue()
        assertThat(result.proposal).isNotNull()
        assertThat(result.proposal!!.files.single().path).isEqualTo("App.kt")
        // Critical: the file on disk is untouched until approval is applied elsewhere.
        assertThat(File(root, "App.kt").readText()).isEqualTo("fun main() {}")
    }

    @Test
    fun `proposing a new file marks it as new and does not create it`() = runBlocking {
        val result = tool.execute(
            args("path" to "src/New.kt", "new_content" to "class New"),
            context
        )
        assertThat(result.proposal!!.files.single().isNewFile).isTrue()
        assertThat(File(root, "src/New.kt").exists()).isFalse()
    }

    @Test
    fun `missing required arguments fail cleanly`() = runBlocking {
        val noPath = tool.execute(args("new_content" to "x"), context)
        assertThat(noPath.success).isFalse()
        assertThat(noPath.errorMessage).contains("path")

        val noContent = tool.execute(args("path" to "App.kt"), context)
        assertThat(noContent.success).isFalse()
        assertThat(noContent.errorMessage).contains("new_content")
    }

    @Test
    fun `path escaping the workspace is rejected`() = runBlocking {
        val result = tool.execute(
            args("path" to "../outside.txt", "new_content" to "x"),
            context
        )
        assertThat(result.success).isFalse()
        assertThat(result.proposal).isNull()
    }

    @Test
    fun `no-op edit is reported without a proposal`() = runBlocking {
        File(root, "App.kt").writeText("same")
        val result = tool.execute(args("path" to "App.kt", "new_content" to "same"), context)
        assertThat(result.success).isTrue()
        assertThat(result.proposal).isNull()
        assertThat(result.output).contains("No change")
    }
}
