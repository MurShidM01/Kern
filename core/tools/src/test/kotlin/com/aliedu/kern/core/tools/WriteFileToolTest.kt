package com.aliedu.kern.core.tools

import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import com.aliedu.kern.core.tools.diff.DiffService
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
 * `write_file` creates a file, but replacing an existing one is the dangerous case: an agent that
 * meant to touch one line must not clobber the file by accident, so an overwrite needs an explicit
 * flag. It never writes — it only proposes.
 */
class WriteFileToolTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var context: ToolContext
    private val tool = WriteFileTool(DiffService())

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

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `creating a new file proposes a patch and writes nothing`() = runBlocking {
        val result = tool.execute(
            args("""{"path":"src/New.kt","content":"class New\n"}"""),
            context
        )

        assertThat(result.success).isTrue()
        assertThat(result.requiresApproval).isTrue()
        val patch = result.proposal!!.files.single()
        assertThat(patch.path).isEqualTo("src/New.kt")
        assertThat(patch.isNewFile).isTrue()
        assertThat(patch.newContent).isEqualTo("class New\n")
        assertThat(File(root, "src/New.kt").exists()).isFalse()
    }

    @Test
    fun `overwriting an existing file without the flag is refused`() = runBlocking {
        File(root, "App.kt").writeText("original\n")

        val result = tool.execute(args("""{"path":"App.kt","content":"clobbered\n"}"""), context)

        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("already exists")
        assertThat(result.proposal).isNull()
        assertThat(File(root, "App.kt").readText()).isEqualTo("original\n")
    }

    @Test
    fun `overwriting with the flag set produces a replacement patch`() = runBlocking {
        File(root, "App.kt").writeText("original\n")

        val result = tool.execute(
            args("""{"path":"App.kt","content":"replaced\n","overwrite":true}"""),
            context
        )

        assertThat(result.success).isTrue()
        val patch = result.proposal!!.files.single()
        assertThat(patch.isNewFile).isFalse()
        assertThat(patch.newContent).isEqualTo("replaced\n")
        // Still nothing written until approval.
        assertThat(File(root, "App.kt").readText()).isEqualTo("original\n")
    }

    @Test
    fun `identical content is reported as no change`() = runBlocking {
        File(root, "App.kt").writeText("same\n")
        val result = tool.execute(
            args("""{"path":"App.kt","content":"same\n","overwrite":true}"""),
            context
        )
        assertThat(result.success).isTrue()
        assertThat(result.proposal).isNull()
        assertThat(result.output).contains("No change")
    }

    @Test
    fun `an escaping path is rejected`() = runBlocking {
        val result = tool.execute(args("""{"path":"../outside.txt","content":"x"}"""), context)
        assertThat(result.success).isFalse()
    }

    @Test
    fun `writing to the workspace root is rejected`() = runBlocking {
        val result = tool.execute(args("""{"path":"","content":"x"}"""), context)
        assertThat(result.success).isFalse()
    }
}
