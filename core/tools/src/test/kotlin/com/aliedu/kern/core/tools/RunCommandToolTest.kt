package com.aliedu.kern.core.tools

import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
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
 * run_command must only propose a command (classified by impact) and never execute it — execution is
 * the orchestrator's job, after approval (PRD FR-017).
 */
class RunCommandToolTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var context: ToolContext
    private val tool = RunCommandTool()

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

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun `proposing a command produces a request and does not execute it`() = runBlocking {
        val result = tool.execute(args("command" to "ls -la"), context)
        assertThat(result.success).isTrue()
        assertThat(result.requiresApproval).isTrue()
        assertThat(result.commandRequest).isNotNull()
        assertThat(result.commandRequest!!.command).isEqualTo("ls -la")
        assertThat(result.commandRequest!!.workspaceId).isEqualTo("ws")
    }

    @Test
    fun `missing command fails cleanly`() = runBlocking {
        val result = tool.execute(JsonObject(emptyMap()), context)
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("command")
    }

    @Test
    fun `the output policy can be set to stream`() = runBlocking {
        val result = tool.execute(args("command" to "ls", "output_policy" to "stream"), context)
        assertThat(result.commandRequest!!.outputPolicy).isEqualTo(OutputPolicy.STREAM)
    }

    // ---- classification ---------------------------------------------------

    @Test
    fun `read-only commands are classified read-only`() {
        listOf("ls -la", "cat README.md", "grep -rn TODO .", "git status", "pwd").forEach {
            assertThat(CommandClassifier.classify(it)).isEqualTo(CommandImpact.READ_ONLY)
        }
    }

    @Test
    fun `recursive deletes and disk-level commands are destructive`() {
        listOf("rm -rf build", "rm -r node_modules", "sudo rm /etc/hosts", "mkfs.ext4 /dev/sda1").forEach {
            assertThat(CommandClassifier.classify(it)).isEqualTo(CommandImpact.DESTRUCTIVE)
        }
    }

    @Test
    fun `network commands are classified network`() {
        listOf("curl https://example.com", "git push origin main", "npm install", "apt-get update").forEach {
            assertThat(CommandClassifier.classify(it)).isEqualTo(CommandImpact.NETWORK)
        }
    }

    @Test
    fun `file writes and git mutations are classified mutating`() {
        listOf("mkdir build", "mv a.txt b.txt", "rm old.txt", "echo hi > out.txt", "git commit -m x").forEach {
            assertThat(CommandClassifier.classify(it)).isEqualTo(CommandImpact.MUTATING)
        }
    }

    @Test
    fun `destructive variants are caught whatever the flag case or spelling`() {
        // classify() lower-cases the command first, so a pattern written with an upper-case -R
        // could never match: `chmod -R 777 /` was classified MUTATING and, with the permissive
        // modes auto-running any non-destructive command, ran without the approval it always needs.
        listOf(
            "rm -rf build",
            "rm --recursive build",
            "chmod -R 777 /",
            "chown -R root /tmp/demo"
        ).forEach { command ->
            assertThat(CommandClassifier.classify(command)).isEqualTo(CommandImpact.DESTRUCTIVE)
        }
    }

    @Test
    fun `a plain rm of a single file is mutating not destructive`() {
        assertThat(CommandClassifier.classify("rm old.txt")).isEqualTo(CommandImpact.MUTATING)
    }
}
