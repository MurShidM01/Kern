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
 * A multi-file change must be all-or-nothing: if one entry is invalid the whole call fails so the
 * user is never asked to review a patch that cannot be applied coherently.
 */
class ProposePatchToolTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var context: ToolContext
    private val tool = ProposePatchTool(DiffService())

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
    fun `a create edit and delete can be proposed together`() = runBlocking {
        File(root, "Edit.kt").writeText("val old = 1\n")
        File(root, "Gone.kt").writeText("bye\n")

        val result = tool.execute(
            args(
                """
                {"files":[
                  {"path":"New.kt","new_content":"class New\n"},
                  {"path":"Edit.kt","edits":[{"old_string":"val old","new_string":"val fresh"}]},
                  {"path":"Gone.kt","delete":true}
                ],"reason":"tidy up"}
                """.trimIndent()
            ),
            context
        )

        assertThat(result.success).isTrue()
        assertThat(result.requiresApproval).isTrue()
        val proposal = result.proposal!!
        assertThat(proposal.files.map { it.path })
            .containsExactly("New.kt", "Edit.kt", "Gone.kt").inOrder()
        assertThat(proposal.files[0].isNewFile).isTrue()
        assertThat(proposal.files[1].newContent).contains("val fresh")
        assertThat(proposal.files[2].operation).isEqualTo(PatchOperation.DELETE)
        // Nothing is written.
        assertThat(File(root, "New.kt").exists()).isFalse()
        assertThat(File(root, "Edit.kt").readText()).isEqualTo("val old = 1\n")
        assertThat(File(root, "Gone.kt").exists()).isTrue()
    }

    @Test
    fun `a failing entry fails the whole call`() = runBlocking {
        File(root, "Edit.kt").writeText("val old = 1\n")

        val result = tool.execute(
            args(
                """
                {"files":[
                  {"path":"New.kt","new_content":"class New\n"},
                  {"path":"Edit.kt","edits":[{"old_string":"does not exist","new_string":"x"}]}
                ]}
                """.trimIndent()
            ),
            context
        )

        assertThat(result.success).isFalse()
        assertThat(result.proposal).isNull()
        assertThat(result.errorMessage).contains("File entry 2")
    }

    @Test
    fun `the same path twice is rejected`() = runBlocking {
        val result = tool.execute(
            args(
                """
                {"files":[
                  {"path":"A.kt","new_content":"a"},
                  {"path":"A.kt","new_content":"b"}
                ]}
                """.trimIndent()
            ),
            context
        )
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("more than once")
    }

    @Test
    fun `an entry must choose exactly one mode`() = runBlocking {
        val result = tool.execute(
            args("""{"files":[{"path":"A.kt","new_content":"a","delete":true}]}"""),
            context
        )
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("exactly one")
    }

    @Test
    fun `replacing an existing file with new_content needs overwrite`() = runBlocking {
        File(root, "A.kt").writeText("existing\n")

        val refused = tool.execute(args("""{"files":[{"path":"A.kt","new_content":"other"}]}"""), context)
        assertThat(refused.success).isFalse()
        assertThat(refused.errorMessage).contains("overwrite")

        val allowed = tool.execute(
            args("""{"files":[{"path":"A.kt","new_content":"other","overwrite":true}]}"""),
            context
        )
        assertThat(allowed.success).isTrue()
    }

    @Test
    fun `an empty file list is rejected`() = runBlocking {
        val result = tool.execute(args("""{"files":[]}"""), context)
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("at least one")
    }
}
