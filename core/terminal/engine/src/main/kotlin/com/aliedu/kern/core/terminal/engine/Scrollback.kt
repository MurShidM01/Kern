package com.aliedu.kern.core.terminal.engine

import com.aliedu.kern.core.terminal.engine.model.Cell

/** A bounded FIFO of scrolled-off rows. Oldest rows are evicted once [limit] is exceeded. */
class Scrollback(private val limit: Int) {

    private val rows = ArrayDeque<List<Cell>>()

    val size: Int get() = rows.size

    fun push(row: List<Cell>) {
        rows.addLast(row)
        while (rows.size > limit) rows.removeFirst()
    }

    fun row(index: Int): List<Cell> = rows[index]

    fun clear() = rows.clear()
}
