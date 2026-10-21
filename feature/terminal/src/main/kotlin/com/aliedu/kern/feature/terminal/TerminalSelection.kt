package com.aliedu.kern.feature.terminal

import com.aliedu.kern.core.terminal.engine.model.ScreenSnapshot

/** A cell coordinate on the visible screen. */
data class CellPos(val row: Int, val col: Int)

/**
 * A cell-anchored text selection over the visible terminal screen. [anchor] is where the gesture
 * started; [focus] is the current extent. Rows are half-open in the visual sense: a selection that
 * spans multiple rows includes the full middle rows.
 */
data class TerminalSelection(val anchor: CellPos, val focus: CellPos) {

    private val ordered: Pair<CellPos, CellPos>
        get() = if (anchor.row < focus.row || (anchor.row == focus.row && anchor.col <= focus.col)) {
            anchor to focus
        } else {
            focus to anchor
        }

    val start: CellPos get() = ordered.first
    val end: CellPos get() = ordered.second

    /** The selected column range on [row] (inclusive), or null when the row is outside the selection. */
    fun columnsFor(row: Int, columns: Int): IntRange? {
        val (s, e) = ordered
        if (row < s.row || row > e.row) return null
        val from = if (row == s.row) s.col else 0
        val to = if (row == e.row) e.col else columns - 1
        if (to < from) return null
        return from..to
    }

    /** The selected text, with trailing blanks trimmed per row. */
    fun text(snapshot: ScreenSnapshot): String {
        val (s, e) = ordered
        val out = StringBuilder()
        for (row in s.row..e.row) {
            val cells = snapshot.cells.getOrNull(row) ?: continue
            val range = columnsFor(row, cells.size) ?: continue
            val line = StringBuilder()
            for (col in range) {
                val cell = cells.getOrNull(col) ?: continue
                if (cell.continuation) continue
                line.append(cell.text)
            }
            out.append(line.toString().trimEnd())
            if (row < e.row) out.append('\n')
        }
        return out.toString()
    }

    companion object {
        /**
         * A selection covering the screen's content block — blank leading and trailing rows are
         * excluded so "select all" copies just the visible output rather than the empty viewport.
         */
        fun all(snapshot: ScreenSnapshot): TerminalSelection {
            val firstContent = snapshot.cells.indexOfFirst { row -> row.any { !it.isBlank } }
            if (firstContent < 0) return TerminalSelection(CellPos(0, 0), CellPos(0, 0))
            val lastContent = snapshot.cells.indexOfLast { row -> row.any { !it.isBlank } }
            val lastCol = (snapshot.columns - 1).coerceAtLeast(0)
            return TerminalSelection(CellPos(firstContent, 0), CellPos(lastContent, lastCol))
        }
    }
}
