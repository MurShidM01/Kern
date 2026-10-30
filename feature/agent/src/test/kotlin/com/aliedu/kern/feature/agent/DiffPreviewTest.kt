package com.aliedu.kern.feature.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The approval sheet paints added lines green and removed lines red, so every line has to be typed
 * correctly. The file header is the trap: `--- a.txt` starts with `-` but is not a removed line.
 */
class DiffPreviewTest {

    private val diff = listOf(
        "--- notes.txt",
        "+++ notes.txt",
        "@@ -1,3 +1,4 @@",
        " first",
        "-second",
        "+second (edited)",
        "+third",
        " fourth"
    ).joinToString("\n")

    @Test
    fun `file headers are not painted as changes`() {
        val rows = parseUnifiedDiff(diff)

        assertThat(rows.take(2).map { it.kind })
            .containsExactly(DiffLineKind.HEADER, DiffLineKind.HEADER).inOrder()
    }

    @Test
    fun `added and removed lines are typed, context is not`() {
        val rows = parseUnifiedDiff(diff)

        assertThat(rows[2].kind).isEqualTo(DiffLineKind.HUNK)
        assertThat(rows[3].kind).isEqualTo(DiffLineKind.CONTEXT)
        assertThat(rows[4].kind).isEqualTo(DiffLineKind.REMOVED)
        assertThat(rows[5].kind).isEqualTo(DiffLineKind.ADDED)
        assertThat(rows[6].kind).isEqualTo(DiffLineKind.ADDED)
        assertThat(rows[7].kind).isEqualTo(DiffLineKind.CONTEXT)
    }

    @Test
    fun `the marker is kept in the text so a copy still reads as a diff`() {
        val rows = parseUnifiedDiff(diff)

        assertThat(rows[4].text).isEqualTo("-second")
        assertThat(rows[5].text).isEqualTo("+second (edited)")
    }

    @Test
    fun `a new file is all additions`() {
        val newFile = listOf("--- app.kt", "+++ app.kt", "@@ -0,0 +1,2 @@", "+one", "+two")
            .joinToString("\n")

        val rows = parseUnifiedDiff(newFile)

        assertThat(rows.drop(3).map { it.kind })
            .containsExactly(DiffLineKind.ADDED, DiffLineKind.ADDED).inOrder()
    }
}
