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

/** `glob` and `grep` give the agent pattern-based discovery, which substring search cannot. */
class SearchToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var context: ToolContext
    private val glob = GlobTool()
    private val grep = GrepTool()

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

        File(root, "App.kt").writeText("fun main() {\n    println(\"hello\")\n}\n")
        File(root, "src").mkdirs()
        File(root, "src/Nested.kt").writeText("class Nested\n")
        File(root, "src/notes.md").writeText("hello world\n")
        File(root, "build").mkdirs()
        File(root, "build/Generated.kt").writeText("// generated\n")
    }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `glob matches across directories and skips build output`() = runBlocking {
        val result = glob.execute(args("""{"pattern":"**/*.kt"}"""), context)

        assertThat(result.success).isTrue()
        val paths = result.output.lines()
        assertThat(paths).contains("App.kt")
        assertThat(paths).contains("src/Nested.kt")
        assertThat(paths).doesNotContain("build/Generated.kt")
    }

    @Test
    fun `glob without a slash matches the file name at any depth`() = runBlocking {
        val result = glob.execute(args("""{"pattern":"*.kt"}"""), context)

        val paths = result.output.lines()
        assertThat(paths).contains("App.kt")
        assertThat(paths).contains("src/Nested.kt")
    }

    @Test
    fun `glob honours a subfolder scope`(): Unit = runBlocking {
        val result = glob.execute(args("""{"pattern":"**/*.kt","path":"src"}"""), context)

        assertThat(result.output.lines()).containsExactly("src/Nested.kt")
    }

    @Test
    fun `grep reports path line and text for a regex`() = runBlocking {
        val result = grep.execute(args("""{"pattern":"println\\(.*\\)"}"""), context)

        assertThat(result.success).isTrue()
        assertThat(result.output).contains("App.kt:2:")
        assertThat(result.output).contains("println")
    }

    @Test
    fun `grep is case sensitive unless asked otherwise`() = runBlocking {
        val sensitive = grep.execute(args("""{"pattern":"HELLO"}"""), context)
        assertThat(sensitive.output).contains("No matches")

        val insensitive = grep.execute(
            args("""{"pattern":"HELLO","ignore_case":true}"""),
            context
        )
        assertThat(insensitive.output).contains("src/notes.md:1:")
    }

    @Test
    fun `grep rejects an invalid regular expression`() = runBlocking {
        val result = grep.execute(args("""{"pattern":"("}"""), context)
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("Invalid regular expression")
    }

    @Test
    fun `a pattern that escapes the workspace is rejected`() = runBlocking {
        val result = glob.execute(args("""{"pattern":"**/*.kt","path":"../outside"}"""), context)
        assertThat(result.success).isFalse()
    }

    @Test
    fun `a gitignored file is hidden from glob`() = runBlocking {
        File(root, ".gitignore").writeText("*.log\n")
        File(root, "debug.log").writeText("noise\n")

        val result = glob.execute(args("""{"pattern":"**/*.log"}"""), context)

        assertThat(result.output).contains("No files match")
    }

    @Test
    fun `a gitignore negation re-includes a file`() = runBlocking {
        File(root, ".gitignore").writeText("*.log\n!keep.log\n")
        File(root, "debug.log").writeText("noise\n")
        File(root, "keep.log").writeText("wanted\n")

        val result = glob.execute(args("""{"pattern":"**/*.log"}"""), context)

        assertThat(result.output.lines()).contains("keep.log")
        assertThat(result.output).doesNotContain("debug.log")
    }

    @Test
    fun `a gitignored directory is pruned from grep`() = runBlocking {
        File(root, ".gitignore").writeText("vendor/\n")
        File(root, "vendor").mkdirs()
        File(root, "vendor/Lib.kt").writeText("val marker = 1\n")
        File(root, "App2.kt").writeText("val marker = 2\n")

        val result = grep.execute(args("""{"pattern":"marker"}"""), context)

        assertThat(result.output).contains("App2.kt")
        assertThat(result.output).doesNotContain("vendor/Lib.kt")
    }

    @Test
    fun `ignore rules honour anchoring and directory-only patterns`() {
        val rules = IgnoreRules.parse(listOf("/root.txt", "logs/", "*.tmp"))

        assertThat(rules.ignores("root.txt", isDirectory = false)).isTrue()
        assertThat(rules.ignores("nested/root.txt", isDirectory = false)).isFalse()
        assertThat(rules.ignores("logs", isDirectory = true)).isTrue()
        assertThat(rules.ignores("logs/today.txt", isDirectory = false)).isTrue()
        assertThat(rules.ignores("scratch.tmp", isDirectory = false)).isTrue()
        assertThat(rules.ignores("keep.md", isDirectory = false)).isFalse()
    }
}
