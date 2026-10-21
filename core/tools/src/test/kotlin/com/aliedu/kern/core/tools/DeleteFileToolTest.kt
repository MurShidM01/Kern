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
 * delete_file must only produce a reviewable DELETE proposal and must never remove anything from
 * disk on its own (approval happens in the orchestrator via PatchApplier).
 */
class DeleteFileToolTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var adapter: AppManagedWorkspaceAdapter
    private lateinit var context: ToolContext
    private val tool = DeleteFileTool(DiffService())

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
    fun `proposing a delete produces a DELETE proposal and does not remove the file`() = runBlocking {
        File(root, "old.txt").writeText("hello")

        val result = tool.execute(args("path" to "old.txt"), context)

        assertThat(result.success).isTrue()
        assertThat(result.requiresApproval).isTrue()
        assertThat(result.proposal).isNotNull()
        val patch = result.proposal!!.files.single()
        assertThat(patch.path).isEqualTo("old.txt")
        assertThat(patch.operation).isEqualTo(PatchOperation.DELETE)
        // Critical: the file on disk is untouched until the proposal is approved and applied.
        assertThat(File(root, "old.txt").exists()).isTrue()
    }

    @Test
    fun `deleting a missing file fails cleanly`() = runBlocking {
        val result = tool.execute(args("path" to "nope.txt"), context)
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("No such file")
    }

    @Test
    fun `deleting a folder is refused`() = runBlocking {
        File(root, "dir").mkdirs()
        val result = tool.execute(args("path" to "dir"), context)
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("folder")
    }

    @Test
    fun `refuses to delete the workspace root`() = runBlocking {
        val result = tool.execute(args("path" to ""), context)
        assertThat(result.success).isFalse()
    }

    @Test
    fun `missing path argument fails cleanly`() = runBlocking {
        val result = tool.execute(JsonObject(emptyMap()), context)
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("path")
    }
}
