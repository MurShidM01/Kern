package com.aliedu.kern.core.agent

import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import com.aliedu.kern.core.tools.MemoryNote
import com.aliedu.kern.core.tools.PlanProposal
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Plan mode and memory are only useful if they survive the turn: these tests pin the on-disk shape
 * (~/.kern/PLANS/<workspace>/{plan.md,memory.md}) and the merge behaviour of repeated notes.
 */
class WorkspaceNotesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun notes(): WorkspaceNotes = WorkspaceNotes(temp.newFolder())

    private fun workspace() = Workspace(
        id = "ws-1",
        displayName = "git-socio",
        sourceType = WorkspaceSourceType.APP_MANAGED,
        uriString = null,
        localPath = temp.root.absolutePath,
        grantState = GrantState.NOT_APPLICABLE,
        lastOpenedAt = 0,
        createdAt = 0
    )

    @Test
    fun `plan and memory live in a directory named after the workspace`() {
        val notes = notes()
        val dir = notes.directory(workspace())

        assertThat(dir.name).isEqualTo("git-socio")
        assertThat(dir.parentFile?.name).isEqualTo("PLANS")
        assertThat(notes.location(workspace())).contains("PLANS")
    }

    @Test
    fun `an approved plan is saved as markdown and read back`() {
        val notes = notes()
        val ws = workspace()

        notes.savePlan(ws, PlanProposal(id = "p1", title = "Add dark mode", markdown = "1. Read theme\n2. Add toggle"))

        val saved = notes.plan(ws).orEmpty()
        assertThat(saved).contains("# Add dark mode")
        assertThat(saved).contains("2. Add toggle")
        assertThat(File(notes.directory(ws), "plan.md").isFile).isTrue()
    }

    @Test
    fun `replacing a plan archives the previous one`() {
        val notes = notes()
        val ws = workspace()

        notes.savePlan(ws, PlanProposal(id = "p1", title = "First", markdown = "step one"))
        notes.savePlan(ws, PlanProposal(id = "p2", title = "Second", markdown = "step two"))

        assertThat(notes.plan(ws).orEmpty()).contains("# Second")
        val archive = File(notes.directory(ws), "archive")
        assertThat(archive.listFiles()?.size).isEqualTo(1)
        assertThat(archive.listFiles()?.first()?.readText()).contains("step one")
    }

    @Test
    fun `notes are grouped under their section and never duplicated`() {
        val notes = notes()
        val ws = workspace()

        notes.remember(ws, MemoryNote("conventions", "Tests use JUnit4."))
        notes.remember(ws, MemoryNote("conventions", "Tests use JUnit4."))
        notes.remember(ws, MemoryNote("project", "Kotlin + Compose, 22 Gradle modules."))

        val memory = notes.memory(ws).orEmpty()
        assertThat(memory).contains("## Conventions")
        assertThat(memory).contains("## Project")
        assertThat(Regex("- Tests use JUnit4\\.").findAll(memory).count()).isEqualTo(1)
    }

    @Test
    fun `a finished task is appended to the task log`() {
        val notes = notes()
        val ws = workspace()

        notes.appendTaskLog(ws, "Fix the login screen", "done · read_file, edit_file")
        notes.appendTaskLog(ws, "Add tests", "done · run_tests")

        val memory = notes.memory(ws).orEmpty()
        assertThat(memory).contains("## Task log")
        assertThat(memory).contains("Fix the login screen")
        assertThat(memory).contains("done · run_tests")
    }
}
