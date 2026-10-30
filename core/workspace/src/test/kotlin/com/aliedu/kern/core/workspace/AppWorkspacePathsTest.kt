package com.aliedu.kern.core.workspace

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The directory a project gets is the name the user typed, because that name is also the path they
 * see in the terminal (`/workspace/first_app`). A UUID would be untypeable there and unreadable in
 * a listing.
 */
class AppWorkspacePathsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `spaces become underscores`() {
        assertThat(AppWorkspacePaths.slug("first app")).isEqualTo("first_app")
        assertThat(AppWorkspacePaths.slug("  java  apps  ")).isEqualTo("java_apps")
    }

    @Test
    fun `names that are not shell safe are cleaned up`() {
        assertThat(AppWorkspacePaths.slug("my/app?")).isEqualTo("myapp")
        assertThat(AppWorkspacePaths.slug("!!!")).isEqualTo("project")
        assertThat(AppWorkspacePaths.slug("")).isEqualTo("project")
    }

    @Test
    fun `a taken name gets a numeric suffix instead of reusing the directory`() {
        val root = temp.newFolder("workspaces")
        File(root, "first_app").mkdirs()

        val dir = AppWorkspacePaths.directoryFor(root, "first app")

        assertThat(dir.name).isEqualTo("first_app_2")
        assertThat(dir.parentFile).isEqualTo(root)
    }

    @Test
    fun `the root is created on demand`() {
        val files = temp.newFolder("files")
        val root = AppWorkspacePaths.root(files)
        assertThat(root.isDirectory).isTrue()
        assertThat(root.name).isEqualTo(AppWorkspacePaths.DIR_NAME)
    }
}
