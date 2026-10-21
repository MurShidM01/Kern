package com.aliedu.kern.core.workspace

import com.aliedu.kern.core.model.FileKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A real-path workspace is an ordinary directory outside the app sandbox (`/storage/emulated/0/...`),
 * so the file operations must behave exactly like an app-managed workspace — only the access check
 * differs (all-files access instead of a sandbox path). This proves the shared tree the terminal and
 * the file tools read is edited correctly, with no mirror in between.
 */
@RunWith(RobolectricTestRunner::class)
class RealPathWorkspaceAdapterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var adapter: RealPathWorkspaceAdapter

    @Before
    fun setUp() {
        root = temp.newFolder("sdcard", "Kern")
        adapter = RealPathWorkspaceAdapter(root, workspaceId = "ws-real")
    }

    @Test
    fun `write then read returns the same content`() = runBlocking {
        adapter.writeText("src/main.kt", "fun main() {}")
        assertThat(File(root, "src/main.kt").readText()).isEqualTo("fun main() {}")
        assertThat(adapter.readText("src/main.kt").text).isEqualTo("fun main() {}")
    }

    @Test
    fun `list reports directories and files at the root`() = runBlocking {
        File(root, "src").mkdirs()
        File(root, "README.md").writeText("hi")

        val nodes = adapter.list("")
        assertThat(nodes.map { it.name }).containsExactly("src", "README.md")
        assertThat(nodes.first { it.name == "src" }.kind).isEqualTo(FileKind.DIRECTORY)
        assertThat(nodes.first { it.name == "README.md" }.kind).isEqualTo(FileKind.TEXT)
    }

    @Test
    fun `delete removes a file from the real directory`() = runBlocking {
        File(root, "gone.txt").writeText("x")
        adapter.delete("gone.txt")
        assertThat(File(root, "gone.txt").exists()).isFalse()
    }

    @Test
    fun `search finds text inside the real directory`() = runBlocking {
        adapter.writeText("notes/todo.md", "alpha\nbeta needle\ngamma")
        val matches = adapter.search("needle")
        assertThat(matches.map { it.path }).contains("notes/todo.md")
    }
    // `isAccessValid()` reads Environment.isExternalStorageManager(), a platform call Robolectric's
    // android-all jar does not provide; the all-files-access gate is verified on-device instead.
}
