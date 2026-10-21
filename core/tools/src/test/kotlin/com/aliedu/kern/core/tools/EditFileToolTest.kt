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
 * `edit_file` sends only the changed lines instead of the whole file body, so it must be exact:
 * it either produces one coherent proposal or names the edit that did not match. It never writes.
 */
class EditFileToolTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var context: ToolContext
    private val tool = EditFileTool(DiffService())

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
    fun `a unique snippet is replaced and the file is left untouched`() = runBlocking {
        File(root, "App.kt").writeText("fun main() {\n    println(\"old\")\n}\n")

        val result = tool.execute(
            args(
                """
                {"path":"App.kt","edits":[{"old_string":"println(\"old\")","new_string":"println(\"new\")"}]}
                """.trimIndent()
            ),
            context
        )

        assertThat(result.success).isTrue()
        assertThat(result.requiresApproval).isTrue()
        val patch = result.proposal!!.files.single()
        assertThat(patch.path).isEqualTo("App.kt")
        assertThat(patch.newContent).contains("println(\"new\")")
        assertThat(patch.isNewFile).isFalse()
        // Critical: nothing is written until the user approves.
        assertThat(File(root, "App.kt").readText()).contains("println(\"old\")")
    }

    @Test
    fun `an ambiguous snippet is rejected unless replace_all is set`() = runBlocking {
        File(root, "App.kt").writeText("val a = 1\nval a = 2\n")

        val ambiguous = tool.execute(
            args("""{"path":"App.kt","edits":[{"old_string":"val a","new_string":"val b"}]}"""),
            context
        )
        assertThat(ambiguous.success).isFalse()
        assertThat(ambiguous.errorMessage).contains("2 times")
        assertThat(ambiguous.proposal).isNull()

        val all = tool.execute(
            args(
                """
                {"path":"App.kt","replace_all":true,"edits":[{"old_string":"val a","new_string":"val b"}]}
                """.trimIndent()
            ),
            context
        )
        assertThat(all.success).isTrue()
        assertThat(all.proposal!!.files.single().newContent).isEqualTo("val b = 1\nval b = 2\n")
    }

    @Test
    fun `a snippet that does not match names the failing edit`() = runBlocking {
        File(root, "App.kt").writeText("val a = 1\n")

        val result = tool.execute(
            args(
                """
                {"path":"App.kt","edits":[{"old_string":"val a","new_string":"val b"},{"old_string":"nope","new_string":"x"}]}
                """.trimIndent()
            ),
            context
        )

        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("Edit 2")
        assertThat(result.proposal).isNull()
    }

    @Test
    fun `edits are applied in order`() = runBlocking {
        File(root, "App.kt").writeText("one\n")

        val result = tool.execute(
            args(
                """
                {"path":"App.kt","edits":[{"old_string":"one","new_string":"two"},{"old_string":"two","new_string":"three"}]}
                """.trimIndent()
            ),
            context
        )

        assertThat(result.proposal!!.files.single().newContent).isEqualTo("three\n")
    }

    @Test
    fun `editing a file that does not exist points at propose_edit`() = runBlocking {
        val result = tool.execute(
            args("""{"path":"Missing.kt","edits":[{"old_string":"a","new_string":"b"}]}"""),
            context
        )
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("propose_edit")
    }

    @Test
    fun `an edit that changes nothing produces no proposal`() = runBlocking {
        File(root, "App.kt").writeText("same\n")
        val result = tool.execute(
            args("""{"path":"App.kt","edits":[{"old_string":"same","new_string":"same"}]}"""),
            context
        )
        assertThat(result.success).isTrue()
        assertThat(result.proposal).isNull()
        assertThat(result.output).contains("No change")
    }

    @Test
    fun `an edit reports the lines it added and removed`() = runBlocking {
        File(root, "App.kt").writeText("one\ntwo\nthree\n")
        val result = tool.execute(
            args(
                """
                {"path":"App.kt","edits":[{"old_string":"two","new_string":"TWO\nand a new line"}]}
                """.trimIndent()
            ),
            context
        )
        assertThat(result.success).isTrue()
        // "two" is replaced by two lines: one added line on balance, one removed.
        assertThat(result.linesAdded).isEqualTo(2)
        assertThat(result.linesRemoved).isEqualTo(1)
    }

    @Test
    fun `an empty edits array is rejected`() = runBlocking {
        File(root, "App.kt").writeText("x")
        val result = tool.execute(args("""{"path":"App.kt","edits":[]}"""), context)
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("at least one edit")
    }
}
