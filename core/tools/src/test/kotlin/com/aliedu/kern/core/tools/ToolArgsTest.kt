package com.aliedu.kern.core.tools

import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import com.aliedu.kern.core.tools.diff.DiffService
import com.aliedu.kern.core.workspace.AppManagedWorkspaceAdapter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tool calls fail on shape far more often than on intent. These cover the repairs that keep a
 * multi-step task moving: a stringified array, an extra wrapper object, Claude-style argument names,
 * and an absolute path that points inside the workspace.
 */
class ToolArgsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var context: ToolContext
    private val schema = EditFileTool(DiffService()).schema().parametersJsonSchema

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
            realPath = null,
            grantState = GrantState.NOT_APPLICABLE,
            lastOpenedAt = 0,
            createdAt = 0
        )
        context = ToolContext(sessionId = "s1", workspace = workspace, adapter = adapter)
    }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `an edits array sent as a JSON string is parsed back into an array`() {
        val payload = args(
            """
            {"path":"App.kt","edits":"[{\"old_string\":\"a\",\"new_string\":\"b\"}]"}
            """.trimIndent()
        )

        val repaired = ToolArgs.normalize(payload, schema)

        assertThat(repaired["edits"]).isInstanceOf(JsonArray::class.java)
        val edit = (repaired["edits"] as JsonArray).single().jsonObject
        assertThat(edit["old_string"]!!.jsonPrimitive.content).isEqualTo("a")
    }

    @Test
    fun `a payload wrapped in an input object is unwrapped`() {
        val payload = args(
            """
            {"input":{"path":"App.kt","edits":[{"old_string":"a","new_string":"b"}]}}
            """.trimIndent()
        )

        val repaired = ToolArgs.normalize(payload, schema)

        assertThat(repaired["path"]!!.jsonPrimitive.content).isEqualTo("App.kt")
        assertThat(repaired["input"]).isNull()
    }

    @Test
    fun `Claude-style argument names are mapped onto the documented ones`() {
        val payload = args(
            """
            {"file_path":"App.kt","edits":[{"old_str":"a","new_str":"b"}]}
            """.trimIndent()
        )

        val repaired = ToolArgs.normalize(payload, schema)

        assertThat(repaired["path"]!!.jsonPrimitive.content).isEqualTo("App.kt")
        val edit = (repaired["edits"] as JsonArray).single().jsonObject
        assertThat(edit["old_string"]!!.jsonPrimitive.content).isEqualTo("a")
        assertThat(edit["new_string"]!!.jsonPrimitive.content).isEqualTo("b")
    }

    @Test
    fun `an absolute path inside the workspace resolves to a relative one`() {
        val absolute = File(root, "src/App.kt").absolutePath.replace('\\', '/')

        assertThat(context.resolvePath(absolute)).isEqualTo("src/App.kt")
        assertThat(context.resolvePath("/workspace/src/App.kt")).isEqualTo("src/App.kt")
        assertThat(context.resolvePath("./src/App.kt")).isEqualTo("src/App.kt")
    }

    @Test
    fun `an absolute path outside the workspace is still rejected`() {
        assertThat(context.resolvePath("/etc/passwd")).isNull()
        assertThat(context.resolvePath("../../escape.txt")).isNull()
    }

    @Test
    fun `an edit against a CRLF file matches an LF snippet`() = runBlocking {
        File(root, "Win.kt").writeText("fun main() {\r\n    println(\"old\")\r\n}\r\n")
        val tool = EditFileTool(DiffService())

        val result = tool.execute(
            args(
                """
                {"path":"Win.kt","edits":[{"old_string":"    println(\"old\")","new_string":"    println(\"new\")"}]}
                """.trimIndent()
            ),
            context
        )

        assertThat(result.success).isTrue()
        val patch = result.proposal!!.files.single()
        assertThat(patch.newContent).contains("println(\"new\")")
        // The file's own line endings survive the edit.
        assertThat(patch.newContent).contains("\r\n")
    }

    @Test
    fun `an empty file is reported as empty rather than as a failed read`() = runBlocking {
        File(root, "blank.txt").writeText("")
        val result = ReadFileTool().execute(args("""{"path":"blank.txt"}"""), context)

        assertThat(result.success).isTrue()
        assertThat(result.output).contains("is empty")
    }
}
