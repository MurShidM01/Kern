package com.aliedu.kern.core.tools

import com.aliedu.kern.core.tools.diff.DiffService
import com.aliedu.kern.core.workspace.AppManagedWorkspaceAdapter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Backs P1-07 / T1-08, T1-09: no write happens before approval, and a file that changed after the
 * diff preview is detected by hash mismatch and never overwritten without a new review (PRD 9.5).
 */
class PatchApplierTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var adapter: AppManagedWorkspaceAdapter
    private val diffService = DiffService()
    private val applier = PatchApplier(diffService)

    @Before
    fun setUp() {
        root = temp.newFolder("ws")
        adapter = AppManagedWorkspaceAdapter(root, "ws")
    }

    private fun proposalFor(path: String, original: String, newContent: String): PatchProposal {
        val filePatch = FilePatch(
            path = path,
            originalHash = diffService.hash(original),
            newContent = newContent,
            unifiedDiff = diffService.unifiedDiff(path, original, newContent).unifiedDiff,
            insertions = 0,
            deletions = 0,
            isNewFile = original.isEmpty()
        )
        return PatchProposal("p1", listOf(filePatch), diffService.proposalHash(listOf(path to newContent)))
    }

    @Test
    fun `apply writes the new content when the file is unchanged`() = runBlocking {
        File(root, "App.kt").writeText("old content")
        val proposal = proposalFor("App.kt", "old content", "new content")

        val result = applier.apply(proposal, adapter)

        assertThat(result.allApplied).isTrue()
        assertThat(File(root, "App.kt").readText()).isEqualTo("new content")
    }

    @Test
    fun `apply creates a brand-new file`() = runBlocking {
        val proposal = proposalFor("src/New.kt", "", "fresh")
        val result = applier.apply(proposal, adapter)
        assertThat(result.allApplied).isTrue()
        assertThat(File(root, "src/New.kt").readText()).isEqualTo("fresh")
    }

    @Test
    fun `apply is rejected when the file changed after the preview`() = runBlocking {
        File(root, "App.kt").writeText("old content")
        val proposal = proposalFor("App.kt", "old content", "new content")

        // External change happens between preview and apply.
        File(root, "App.kt").writeText("externally modified")

        val result = applier.apply(proposal, adapter)

        assertThat(result.allApplied).isFalse()
        assertThat(result.statuses.single().message).contains("changed since preview")
        // The external change must survive; the stale patch must not overwrite it.
        assertThat(File(root, "App.kt").readText()).isEqualTo("externally modified")
    }

    @Test
    fun `apply deletes a file for a DELETE patch`() = runBlocking {
        File(root, "old.txt").writeText("goodbye")
        val filePatch = FilePatch(
            path = "old.txt",
            originalHash = diffService.hash("goodbye"),
            newContent = "",
            unifiedDiff = "",
            insertions = 0,
            deletions = 1,
            operation = PatchOperation.DELETE
        )
        val proposal = PatchProposal("p-del", listOf(filePatch), diffService.proposalHash(listOf("old.txt" to "")))

        val result = applier.apply(proposal, adapter)

        assertThat(result.allApplied).isTrue()
        assertThat(File(root, "old.txt").exists()).isFalse()
    }

    @Test
    fun `apply refuses a DELETE when the file changed after the preview`() = runBlocking {
        File(root, "old.txt").writeText("v1")
        val filePatch = FilePatch(
            path = "old.txt",
            originalHash = diffService.hash("v1"),
            newContent = "",
            unifiedDiff = "",
            insertions = 0,
            deletions = 1,
            operation = PatchOperation.DELETE
        )
        File(root, "old.txt").writeText("changed")

        val result = applier.apply(PatchProposal("p-del2", listOf(filePatch), "h"), adapter)

        assertThat(result.allApplied).isFalse()
        assertThat(File(root, "old.txt").exists()).isTrue()
    }

    @Test
    fun `one stale file stops the whole batch so nothing is half-applied`() = runBlocking {
        File(root, "ok.txt").writeText("v1")
        File(root, "stale.txt").writeText("v1")

        val ok = FilePatch(
            path = "ok.txt",
            originalHash = diffService.hash("v1"),
            newContent = "v2",
            unifiedDiff = "",
            insertions = 1,
            deletions = 1,
            previousContent = "v1"
        )
        val stale = FilePatch(
            path = "stale.txt",
            originalHash = diffService.hash("v1"),
            newContent = "v2",
            unifiedDiff = "",
            insertions = 1,
            deletions = 1,
            previousContent = "v1"
        )
        File(root, "stale.txt").writeText("changed")

        val proposal = PatchProposal("p2", listOf(ok, stale), "h")
        val result = applier.apply(proposal, adapter)

        assertThat(result.allApplied).isFalse()
        // The healthy file is left exactly as it was: validation happens before any write.
        assertThat(File(root, "ok.txt").readText()).isEqualTo("v1")
        assertThat(File(root, "stale.txt").readText()).isEqualTo("changed")
        assertThat(result.statuses).hasSize(2)
        assertThat(result.statuses.all { !it.applied }).isTrue()
    }

    @Test
    fun `applying only the accepted files leaves the others untouched`() = runBlocking {
        File(root, "a.txt").writeText("a1")
        File(root, "b.txt").writeText("b1")
        val a = FilePatch(
            path = "a.txt",
            originalHash = diffService.hash("a1"),
            newContent = "a2",
            unifiedDiff = "",
            insertions = 1,
            deletions = 1,
            previousContent = "a1"
        )
        val b = FilePatch(
            path = "b.txt",
            originalHash = diffService.hash("b1"),
            newContent = "b2",
            unifiedDiff = "",
            insertions = 1,
            deletions = 1,
            previousContent = "b1"
        )
        val proposal = PatchProposal("p3", listOf(a, b), "h")

        val result = applier.apply(proposal.only(setOf("a.txt")), adapter)

        assertThat(result.allApplied).isTrue()
        assertThat(File(root, "a.txt").readText()).isEqualTo("a2")
        assertThat(File(root, "b.txt").readText()).isEqualTo("b1")
    }
}
