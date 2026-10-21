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
 * The execution tools must pick the command a developer would have typed for this project, and
 * refuse to guess when the project type is unclear.
 */
class BuildTaskToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var context: ToolContext
    private val detector = ProjectDetector()
    private val build = RunBuildTool(detector)
    private val tests = RunTestsTool(detector)
    private val lint = RunLintTool(detector)

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

    @Test
    fun `a gradle project with a wrapper builds through gradlew`() = runBlocking {
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"x\"\n")
        File(root, "gradlew").writeText("#!/bin/sh\n")

        val result = build.execute(args(), context)

        assertThat(result.success).isTrue()
        assertThat(result.requiresApproval).isTrue()
        val request = result.commandRequest!!
        assertThat(request.command).isEqualTo("./gradlew assembleDebug")
        assertThat(request.summarize).isTrue()
        assertThat(request.cwd).isEqualTo(root.absolutePath)
    }

    @Test
    fun `a gradle project without a wrapper uses the system gradle`() = runBlocking {
        File(root, "build.gradle.kts").writeText("plugins {}\n")

        val result = build.execute(args(), context)

        assertThat(result.commandRequest!!.command).isEqualTo("gradle assembleDebug")
    }

    @Test
    fun `a go project tests with go test`() = runBlocking {
        File(root, "go.mod").writeText("module example.com/x\n")

        val result = tests.execute(args(), context)

        assertThat(result.commandRequest!!.command).isEqualTo("go test ./...")
    }

    @Test
    fun `an unknown project type is refused with a pointer to run_command`() = runBlocking {
        File(root, "README.md").writeText("nothing here\n")

        val result = build.execute(args(), context)

        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("run_command")
        assertThat(result.commandRequest).isNull()
    }

    @Test
    fun `a project type with no conventional lint command is refused`() = runBlocking {
        File(root, "requirements.txt").writeText("pytest\n")

        val result = lint.execute(args(), context)

        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("run_command")
    }

    @Test
    fun `a python project tests with pytest`() = runBlocking {
        File(root, "pyproject.toml").writeText("[project]\nname='x'\n")

        val result = tests.execute(args(), context)

        assertThat(result.commandRequest!!.command).isEqualTo("python -m pytest")
    }

    @Test
    fun `a custom reason is carried into the request`() = runBlocking {
        File(root, "Cargo.toml").writeText("[package]\nname='x'\n")

        val result = build.execute(args("""{"reason":"check the new module"}"""), context)

        assertThat(result.commandRequest!!.reason).isEqualTo("check the new module")
    }
}
