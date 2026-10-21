package com.aliedu.kern.core.workspace

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the SAF copy-in / diff / sync-back path (plan risk R2) using two [LocalExecutionTree]s so no
 * Android or document provider is required.
 */
class ExecutionMirrorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tree(name: String): Pair<File, LocalExecutionTree> {
        val dir = tmp.newFolder(name)
        return dir to LocalExecutionTree(dir)
    }

    private fun write(root: File, rel: String, text: String) {
        val f = File(root, rel).apply { parentFile?.mkdirs() }
        f.writeText(text)
    }

    private fun read(root: File, rel: String): String = File(root, rel).readText()

    @Test
    fun `copyAll mirrors every source file into the destination`(): Unit = runBlocking {
        val (srcRoot, src) = tree("src")
        val (_, mirror) = tree("mirror")
        write(srcRoot, "README.md", "hello")
        write(srcRoot, "src/Main.kt", "fun main() {}")

        ExecutionMirror(src, mirror).copyAll()

        assertThat(mirror.listFiles().sorted()).containsExactly("README.md", "src/Main.kt")
    }

    @Test
    fun `listChanges reports added modified and deleted files`(): Unit = runBlocking {
        val (srcRoot, src) = tree("src")
        val (mirrorRoot, mirror) = tree("mirror")
        write(srcRoot, "kept.txt", "same")
        write(srcRoot, "edited.txt", "before")
        write(srcRoot, "removed.txt", "gone")
        ExecutionMirror(src, mirror).copyAll()

        // Simulate edits in the mirror.
        write(mirrorRoot, "edited.txt", "after\nsecond line")
        write(mirrorRoot, "new.txt", "brand new")
        File(mirrorRoot, "removed.txt").delete()

        val changes = ExecutionMirror(src, mirror).listChanges().associateBy { it.path }

        assertThat(changes.keys).containsExactly("edited.txt", "new.txt", "removed.txt")
        assertThat(changes["new.txt"]!!.kind).isEqualTo(ChangeKind.ADDED)
        assertThat(changes["removed.txt"]!!.kind).isEqualTo(ChangeKind.DELETED)
        assertThat(changes["edited.txt"]!!.kind).isEqualTo(ChangeKind.MODIFIED)
        assertThat(changes["edited.txt"]!!.additions).isEqualTo(2)
        assertThat(changes["edited.txt"]!!.deletions).isEqualTo(1)
    }

    @Test
    fun `listChanges is empty when the mirror matches the source`(): Unit = runBlocking {
        val (srcRoot, src) = tree("src")
        val (_, mirror) = tree("mirror")
        write(srcRoot, "a.txt", "a")
        ExecutionMirror(src, mirror).copyAll()
        assertThat(ExecutionMirror(src, mirror).listChanges()).isEmpty()
    }

    @Test
    fun `fileDiff marks added and removed lines`(): Unit = runBlocking {
        val (srcRoot, src) = tree("src")
        val (mirrorRoot, mirror) = tree("mirror")
        write(srcRoot, "f.txt", "one\ntwo\nthree")
        ExecutionMirror(src, mirror).copyAll()
        write(mirrorRoot, "f.txt", "one\nTWO\nthree")

        val diff = ExecutionMirror(src, mirror).fileDiff("f.txt")
        assertThat(diff.filter { it.kind == DiffLineKind.REMOVE }.map { it.text }).contains("two")
        assertThat(diff.filter { it.kind == DiffLineKind.ADD }.map { it.text }).contains("TWO")
        assertThat(diff.filter { it.kind == DiffLineKind.CONTEXT }.map { it.text })
            .containsExactly("one", "three").inOrder()
    }

    @Test
    fun `syncBack writes chosen files to the source and leaves others untouched`(): Unit = runBlocking {
        val (srcRoot, src) = tree("src")
        val (mirrorRoot, mirror) = tree("mirror")
        write(srcRoot, "a.txt", "old-a")
        write(srcRoot, "b.txt", "old-b")
        ExecutionMirror(src, mirror).copyAll()
        write(mirrorRoot, "a.txt", "new-a")
        write(mirrorRoot, "b.txt", "new-b")

        val report = ExecutionMirror(src, mirror).syncBack(listOf("a.txt"))

        assertThat(report.synced).containsExactly("a.txt")
        assertThat(report.failed).isEmpty()
        assertThat(read(srcRoot, "a.txt")).isEqualTo("new-a")
        assertThat(read(srcRoot, "b.txt")).isEqualTo("old-b") // never auto-overwritten
    }

    @Test
    fun `syncBack propagates a deletion made in the mirror`(): Unit = runBlocking {
        val (srcRoot, src) = tree("src")
        val (mirrorRoot, mirror) = tree("mirror")
        write(srcRoot, "gone.txt", "bye")
        ExecutionMirror(src, mirror).copyAll()
        File(mirrorRoot, "gone.txt").delete()

        val report = ExecutionMirror(src, mirror).syncBack(listOf("gone.txt"))

        assertThat(report.synced).containsExactly("gone.txt")
        assertThat(File(srcRoot, "gone.txt").exists()).isFalse()
    }

    @Test
    fun `restore discards mirror edits from the source`(): Unit = runBlocking {
        val (srcRoot, src) = tree("src")
        val (mirrorRoot, mirror) = tree("mirror")
        write(srcRoot, "f.txt", "original")
        ExecutionMirror(src, mirror).copyAll()
        write(mirrorRoot, "f.txt", "edited")

        ExecutionMirror(src, mirror).restore(listOf("f.txt"))

        assertThat(read(mirrorRoot, "f.txt")).isEqualTo("original")
    }

    @Test
    fun `binary files are diffed without crashing and report no line counts`(): Unit = runBlocking {
        val (srcRoot, src) = tree("src")
        val (mirrorRoot, mirror) = tree("mirror")
        File(srcRoot, "bin.dat").writeBytes(byteArrayOf(1, 0, 2, 3))
        ExecutionMirror(src, mirror).copyAll()
        File(mirrorRoot, "bin.dat").writeBytes(byteArrayOf(1, 0, 2, 4))

        val change = ExecutionMirror(src, mirror).listChanges().single()
        assertThat(change.path).isEqualTo("bin.dat")
        assertThat(change.kind).isEqualTo(ChangeKind.MODIFIED)
        assertThat(change.additions).isEqualTo(0)
        assertThat(change.deletions).isEqualTo(0)
    }
}
