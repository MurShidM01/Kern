package com.aliedu.kern.feature.terminal

import com.aliedu.kern.core.terminal.engine.model.Cell
import com.aliedu.kern.core.terminal.engine.model.Cursor
import com.aliedu.kern.core.terminal.engine.model.ScreenSnapshot
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TerminalSelectionTest {

    /** Builds a snapshot where each argument is one screen row, padded to the full width. */
    private fun snapshot(vararg rows: String): ScreenSnapshot {
        val columns = rows.maxOfOrNull { it.length } ?: 0
        val cells = rows.map { line ->
            val chars = line.map { Cell(it.toString()) }.toMutableList()
            while (chars.size < columns) chars.add(Cell())
            chars
        }
        return ScreenSnapshot(
            columns = columns,
            rows = cells.size,
            cells = cells,
            cursor = Cursor(0, 0, true),
            title = null,
            altScreen = false,
            scrollbackSize = 0,
            viewportOffset = 0,
            applicationCursorKeys = false,
            bracketedPaste = false
        )
    }

    @Test
    fun `select all trims blank leading and trailing rows`() {
        val snap = snapshot("", "", "hello", "", "")
        val selection = TerminalSelection.all(snap)

        assertThat(selection.start).isEqualTo(CellPos(2, 0))
        assertThat(selection.end.row).isEqualTo(2)
        assertThat(selection.text(snap)).isEqualTo("hello")
    }

    @Test
    fun `select all on an all-blank screen collapses to the first cell`() {
        val snap = snapshot("", "", "")
        val selection = TerminalSelection.all(snap)

        assertThat(selection.start).isEqualTo(CellPos(0, 0))
        assertThat(selection.end).isEqualTo(CellPos(0, 0))
        assertThat(selection.text(snap)).isEmpty()
    }

    @Test
    fun `text across a multi-row selection keeps inner blank lines`() {
        val snap = snapshot("first", "", "third")
        val selection = TerminalSelection(CellPos(0, 0), CellPos(2, 4))

        assertThat(selection.text(snap)).isEqualTo("first\n\nthird")
    }

    @Test
    fun `a backwards drag normalizes to start before end`() {
        val selection = TerminalSelection(CellPos(3, 5), CellPos(1, 2))

        assertThat(selection.start).isEqualTo(CellPos(1, 2))
        assertThat(selection.end).isEqualTo(CellPos(3, 5))
    }
}
