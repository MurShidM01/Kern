package com.aliedu.kern.core.terminal.engine

import com.aliedu.kern.core.terminal.engine.model.Cell

/** A mutable grid of [Cell]s. Rows are arrays so row copies are cheap during scrolling. */
class TerminalBuffer(var columns: Int, var rows: Int) {

    private var grid: Array<Array<Cell>> = Array(rows) { Array(columns) { Cell.EMPTY } }

    fun cell(y: Int, x: Int): Cell = grid[y][x]

    fun set(y: Int, x: Int, cell: Cell) {
        grid[y][x] = cell
    }

    fun row(y: Int): List<Cell> = grid[y].toList()

    fun clearRow(y: Int, from: Int = 0, to: Int = columns) {
        val end = to.coerceAtMost(columns)
        for (x in from.coerceAtLeast(0) until end) grid[y][x] = Cell.EMPTY
    }

    fun copyRow(fromY: Int, toY: Int) {
        System.arraycopy(grid[fromY], 0, grid[toY], 0, columns)
    }

    fun fillRow(y: Int, from: Int, to: Int, cell: Cell) {
        val end = to.coerceAtMost(columns)
        for (x in from.coerceAtLeast(0) until end) grid[y][x] = cell
    }

    fun clearAll() {
        for (y in 0 until rows) clearRow(y)
    }

    /** Resize preserving the top-left origin (simple clip/pad — no reflow). */
    fun resize(newColumns: Int, newRows: Int) {
        val next = Array(newRows) { y ->
            Array(newColumns) { x ->
                if (y < rows && x < columns) grid[y][x] else Cell.EMPTY
            }
        }
        grid = next
        columns = newColumns
        rows = newRows
    }
}
