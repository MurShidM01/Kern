package com.aliedu.kern.core.workspace

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Backs P1-04 / T1-02: workspace-relative paths must be normalized and any attempt to escape the
 * workspace root rejected (PRD 9.3 path containment).
 */
class WorkspacePathsTest {

    @Test
    fun `plain relative path is preserved`() {
        assertThat(WorkspacePaths.normalize("src/main/App.kt")).isEqualTo("src/main/App.kt")
    }

    @Test
    fun `redundant separators and dot segments are collapsed`() {
        assertThat(WorkspacePaths.normalize("./src//main/./App.kt")).isEqualTo("src/main/App.kt")
    }

    @Test
    fun `backslashes are treated as separators`() {
        assertThat(WorkspacePaths.normalize("src\\main\\App.kt")).isEqualTo("src/main/App.kt")
    }

    @Test
    fun `absolute paths are rejected`() {
        assertThat(WorkspacePaths.normalize("/etc/passwd")).isNull()
        assertThat(WorkspacePaths.isWithinWorkspace("/etc/passwd")).isFalse()
    }

    @Test
    fun `parent traversal that escapes the root is rejected`() {
        assertThat(WorkspacePaths.normalize("../secrets.txt")).isNull()
        assertThat(WorkspacePaths.normalize("src/../../secrets.txt")).isNull()
    }

    @Test
    fun `parent traversal that stays inside the root is resolved`() {
        assertThat(WorkspacePaths.normalize("src/../build/output.apk")).isEqualTo("build/output.apk")
        assertThat(WorkspacePaths.normalize("a/b/../c")).isEqualTo("a/c")
    }

    @Test
    fun `root is represented as the empty string`() {
        assertThat(WorkspacePaths.normalize("")).isEqualTo("")
        assertThat(WorkspacePaths.normalize(".")).isEqualTo("")
    }

    @Test
    fun `parentOf and nameOf follow normalized semantics`() {
        assertThat(WorkspacePaths.parentOf("src/main/App.kt")).isEqualTo("src/main")
        assertThat(WorkspacePaths.parentOf("App.kt")).isEqualTo("")
        assertThat(WorkspacePaths.nameOf("src/main/App.kt")).isEqualTo("App.kt")
        assertThat(WorkspacePaths.nameOf("App.kt")).isEqualTo("App.kt")
    }
}
