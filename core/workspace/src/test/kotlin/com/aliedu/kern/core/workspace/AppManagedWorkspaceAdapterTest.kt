package com.aliedu.kern.core.workspace

import com.aliedu.kern.core.model.FileKind
import com.aliedu.kern.core.model.SearchMatchKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Backs P1-04 / T1-02, T1-03, T1-04: browse, read, edit, save, reopen and search against a real
 * local folder with no mock repository. Uses ordinary File I/O over a temp directory.
 */
class AppManagedWorkspaceAdapterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var adapter: AppManagedWorkspaceAdapter

    @Before
    fun setUp() {
        root = temp.newFolder("workspace")
        adapter = AppManagedWorkspaceAdapter(root, workspaceId = "ws-test")
    }

    @Test
    fun `list returns directories first then files, sorted case-insensitively`() = runBlocking {
        File(root, "b.txt").writeText("b")
        File(root, "A.txt").writeText("A")
        File(root, "zeta").mkdirs()
        File(root, "alpha").mkdirs()

        val names = adapter.list("").map { it.name }
        assertThat(names).containsExactly("alpha", "zeta", "A.txt", "b.txt").inOrder()
    }

    @Test
    fun `read then edit then save then reopen round-trips the exact bytes`() = runBlocking {
        File(root, "src/main").mkdirs()
        val file = File(root, "src/main/App.kt")
        file.writeText("fun main() = println(\"hi\")", Charsets.UTF_8)

        val read = adapter.readText("src/main/App.kt")
        assertThat(read.text).isEqualTo("fun main() = println(\"hi\")")
        assertThat(read.encoding).isEqualTo("UTF-8")
        assertThat(read.truncated).isFalse()

        val edited = "fun main() = println(\"hello, world\")"
        val save = adapter.writeText("src/main/App.kt", edited)
        assertThat(save.bytesWritten).isEqualTo(edited.toByteArray(Charsets.UTF_8).size.toLong())

        // Reopen from a fresh adapter instance to prove persistence on disk.
        val reopened = AppManagedWorkspaceAdapter(root, "ws-test").readText("src/main/App.kt")
        assertThat(reopened.text).isEqualTo(edited)
    }

    @Test
    fun `write creates missing parent directories`() = runBlocking {
        adapter.writeText("deep/nested/dir/file.md", "# title")
        assertThat(File(root, "deep/nested/dir/file.md").readText()).isEqualTo("# title")
    }

    @Test
    fun `createFile refuses to clobber an existing file`() = runBlocking {
        File(root, "exists.txt").writeText("original")
        val threw = runCatching { adapter.createFile("exists.txt", "new") }.isFailure
        assertThat(threw).isTrue()
        assertThat(File(root, "exists.txt").readText()).isEqualTo("original")
    }

    @Test
    fun `exists and delete behave as expected`() = runBlocking {
        File(root, "gone.txt").writeText("x")
        assertThat(adapter.exists("gone.txt")).isTrue()
        assertThat(adapter.delete("gone.txt")).isTrue()
        assertThat(adapter.exists("gone.txt")).isFalse()
    }

    @Test
    fun `delete refuses the workspace root`() = runBlocking {
        val threw = runCatching { adapter.delete("") }.isFailure
        assertThat(threw).isTrue()
        assertThat(root.exists()).isTrue()
    }

    @Test
    fun `binary file is rejected with a clear message and not silently corrupted`() = runBlocking {
        File(root, "blob.bin").writeBytes(byteArrayOf(1, 2, 0, 4, 5))
        val error = runCatching { adapter.readText("blob.bin") }.exceptionOrNull()
        assertThat(error).isInstanceOf(WorkspaceAccessException::class.java)
        assertThat(error!!.message).contains("binary")
    }

    @Test
    fun `path escaping the workspace is rejected`() = runBlocking {
        val error = runCatching { adapter.readText("../outside.txt") }.exceptionOrNull()
        assertThat(error).isInstanceOf(WorkspaceAccessException::class.java)
    }

    @Test
    fun `large file read is truncated at the requested budget`() = runBlocking {
        File(root, "big.txt").writeText("A".repeat(5000))
        val content = adapter.readText("big.txt", maxBytes = 1000)
        assertThat(content.truncated).isTrue()
        assertThat(content.sizeBytes).isEqualTo(5000)
        assertThat(content.text.length).isEqualTo(1000)
    }

    @Test
    fun `search matches file names and text content`() = runBlocking {
        File(root, "notes.md").writeText("hello world\nsecond line")
        File(root, "readme.txt").writeText("no match here")
        File(root, "sub").mkdirs()
        File(root, "sub/world.txt").writeText("nothing")

        val byContent = adapter.search("hello", includeContent = true)
        assertThat(byContent.map { it.kind }).contains(SearchMatchKind.CONTENT)
        assertThat(byContent.first().lineNumber).isEqualTo(1)

        val byName = adapter.search("world", includeContent = false)
        assertThat(byName.map { it.path }).contains("sub/world.txt")
    }

    @Test
    fun `list marks known text extensions as TEXT kind`() = runBlocking {
        File(root, "Main.kt").writeText("x")
        File(root, "image.png").writeBytes(byteArrayOf(1, 2, 3))
        val kinds = adapter.list("").associate { it.name to it.kind }
        assertThat(kinds["Main.kt"]).isEqualTo(FileKind.TEXT)
        assertThat(kinds["image.png"]).isEqualTo(FileKind.UNKNOWN)
    }
}
