package com.aliedu.kern.core.terminal.engine

import com.aliedu.kern.core.terminal.engine.model.Cell
import com.aliedu.kern.core.terminal.engine.model.CellAttributes
import com.aliedu.kern.core.terminal.engine.model.Cursor
import com.aliedu.kern.core.terminal.engine.model.CursorShape
import com.aliedu.kern.core.terminal.engine.model.ScreenSnapshot
import com.aliedu.kern.core.terminal.engine.vt.Action
import com.aliedu.kern.core.terminal.engine.vt.VtParser

/**
 * A VT/ANSI screen emulator covering the subset a shell and the agent's tools actually emit:
 * C0 controls, auto-wrap, cursor movement, erase/insert/delete, SGR (incl. 256/truecolour),
 * scroll regions, the alternate screen, cursor save/restore, DECSCUSR, DSR/DA and OSC titles.
 *
 * `feed` takes decoded text and returns any bytes the application should write back (e.g. cursor
 * position reports). It is deliberately framework-free so it can be unit tested on the JVM.
 */
class TerminalEmulator(
    columns: Int = 80,
    rows: Int = 24,
    private val scrollbackLimit: Int = 1000
) {

    var columns: Int = columns.coerceAtLeast(1)
        private set
    var rows: Int = rows.coerceAtLeast(1)
        private set

    private var primary = TerminalBuffer(this.columns, this.rows)
    private var alt = TerminalBuffer(this.columns, this.rows)
    private val scrollback = Scrollback(scrollbackLimit)
    private val parser = VtParser()
    private val responses = ArrayList<String>()

    private var altActive = false
    private val buffer: TerminalBuffer get() = if (altActive) alt else primary

    private var cursorRow = 0
    private var cursorCol = 0
    private var cursorVisible = true
    private var cursorShape = CursorShape.BLOCK
    private var attrs = CellAttributes.DEFAULT

    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    private var autoWrap = true
    private var wrapPending = false
    private var applicationCursorKeys = false
    private var bracketedPaste = false

    private var savedCursor: SavedCursor? = null
    private var lastChar: Int? = null
    private var title: String? = null
    private var viewportOffset = 0

    private data class SavedCursor(val row: Int, val col: Int, val attrs: CellAttributes)

    /** Feed decoded terminal text; returns any response bytes to send back to the application. */
    fun feed(input: String): List<String> {
        responses.clear()
        parser.parse(input) { handle(it) }
        return responses.toList()
    }

    fun resize(newColumns: Int, newRows: Int) {
        val c = newColumns.coerceAtLeast(1)
        val r = newRows.coerceAtLeast(1)
        if (c == columns && r == rows) return
        primary.resize(c, r)
        alt.resize(c, r)
        columns = c
        rows = r
        scrollTop = 0
        scrollBottom = r - 1
        cursorRow = cursorRow.coerceIn(0, r - 1)
        cursorCol = cursorCol.coerceIn(0, c - 1)
        wrapPending = false
    }

    /** Index of the last screen row the shell has written to (the cursor counts as written). */
    private fun lastUsedRow(): Int {
        var used = cursorRow
        for (row in rows - 1 downTo 0) {
            if (buffer.row(row).any { !it.isBlank }) {
                used = maxOf(used, row)
                break
            }
        }
        return used
    }

    /**
     * How far the view is lifted out of history when it is pinned to the newest line.
     *
     * A shell that printed fewer lines than the grid has rows leaves the prompt stranded part-way
     * down with empty rows under it. The view is composed as the last [rows] lines of
     * (scrollback + everything the shell has written), so it always ends at the prompt: history
     * fills the space above it when there is any, and the prompt sits on the last row instead of
     * floating above a blank band.
     */
    /** Number of lines the shell has written, scrollback included. */
    private fun writtenLines(): Int = scrollback.size + lastUsedRow() + 1

    /** First line of the pinned window: the last [rows] written lines, or 0 when they fit. */
    private fun pinnedTop(): Int = (writtenLines() - rows).coerceAtLeast(0)

    fun scrollViewportBy(delta: Int) {
        // 0 means "pinned to the newest line". The user scrolls relative to what they can actually
        // see, and scrolling back down past the bottom re-pins instead of leaving a dead zone.
        val target = (viewportOffset + delta).coerceIn(0, pinnedTop())
        viewportOffset = target
    }

    fun resetViewport() {
        viewportOffset = 0
    }

    /** "Clear view" — drop scrollback and return to the live screen. */
    fun clearView() {
        scrollback.clear()
        viewportOffset = 0
    }

    private fun blankRow(): List<Cell> = List(columns) { Cell.EMPTY }

    fun snapshot(): ScreenSnapshot {
        val sb = scrollback.size
        val written = writtenLines()
        val top = (pinnedTop() - viewportOffset).coerceAtLeast(0)
        val visible: List<List<Cell>> = (0 until rows).map { i ->
            val idx = top + i
            when {
                idx >= written -> blankRow()
                idx < sb -> scrollback.row(idx)
                else -> buffer.row((idx - sb).coerceIn(0, rows - 1))
            }
        }
        return ScreenSnapshot(
            columns = columns,
            rows = rows,
            cells = visible,
            cursor = Cursor(
                // The cursor moves with the window; when the shell is short it lands on the last row.
                row = (sb + cursorRow - top).coerceIn(0, rows - 1),
                col = cursorCol,
                visible = cursorVisible && viewportOffset == 0,
                shape = cursorShape
            ),
            title = title,
            altScreen = altActive,
            scrollbackSize = scrollback.size,
            viewportOffset = viewportOffset,
            applicationCursorKeys = applicationCursorKeys,
            bracketedPaste = bracketedPaste
        )
    }

    // ---- action handling ---------------------------------------------------

    private fun handle(action: Action) {
        when (action) {
            is Action.Print -> putChar(action.cp, action.width)
            is Action.Execute -> execute(action.control)
            is Action.CsiDispatch -> csi(action)
            is Action.EscDispatch -> esc(action)
            is Action.OscDispatch -> osc(action)
        }
    }

    private fun execute(control: Char) {
        when (control) {
            '\u0007' -> Unit // BEL
            '\b' -> backspace()
            '\t' -> tab()
            '\n', '\u000B', '\u000C' -> lineFeed()
            '\r' -> carriageReturn()
            else -> Unit
        }
    }

    private fun putChar(cp: Int, width: Int) {
        if (width == 0) {
            combineInto(cp)
            return
        }
        if (wrapPending) {
            carriageReturn()
            lineFeed()
            wrapPending = false
        }
        if (cursorCol + width > columns) {
            if (autoWrap) {
                carriageReturn()
                lineFeed()
            } else {
                cursorCol = (columns - width).coerceAtLeast(0)
            }
        }
        lastChar = cp
        val text = String(Character.toChars(cp))
        buffer.set(cursorRow, cursorCol, Cell(text, attrs, width, continuation = false))
        for (i in 1 until width) {
            val x = cursorCol + i
            if (x < columns) buffer.set(cursorRow, x, Cell("", attrs, 1, continuation = true))
        }
        cursorCol += width
        if (cursorCol >= columns) {
            cursorCol = columns - 1
            if (autoWrap) wrapPending = true
        }
    }

    private fun combineInto(cp: Int) {
        val x = cursorCol - 1
        if (x < 0) return
        val existing = buffer.cell(cursorRow, x)
        if (existing.continuation) return
        val combined = existing.text + String(Character.toChars(cp))
        buffer.set(cursorRow, x, existing.copy(text = combined))
    }

    private fun backspace() {
        if (cursorCol > 0) cursorCol--
        wrapPending = false
    }

    private fun tab() {
        val next = ((cursorCol / 8) + 1) * 8
        cursorCol = next.coerceAtMost(columns - 1)
        wrapPending = false
    }

    private fun carriageReturn() {
        cursorCol = 0
        wrapPending = false
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun scrollUp(n: Int) {
        repeat(n) {
            if (!altActive && scrollTop == 0) scrollback.push(buffer.row(scrollTop))
            for (y in scrollTop until scrollBottom) buffer.copyRow(y + 1, y)
            buffer.clearRow(scrollBottom)
        }
    }

    private fun scrollDown(n: Int) {
        repeat(n) {
            for (y in scrollBottom downTo scrollTop + 1) buffer.copyRow(y - 1, y)
            buffer.clearRow(scrollTop)
        }
    }

    private fun csi(a: Action.CsiDispatch) {
        val params = a.params
        fun p(i: Int, def: Int): Int {
            val v = params.getOrNull(i) ?: return def
            return if (v == 0) def else v
        }
        when (a.final) {
            'A' -> cursorRow = (cursorRow - p(0, 1)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + p(0, 1)).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + p(0, 1)).coerceAtMost(columns - 1)
            'D' -> cursorCol = (cursorCol - p(0, 1)).coerceAtLeast(0)
            'E' -> { cursorRow = (cursorRow + p(0, 1)).coerceAtMost(rows - 1); carriageReturn() }
            'F' -> { cursorRow = (cursorRow - p(0, 1)).coerceAtLeast(0); carriageReturn() }
            'G', '`' -> { cursorCol = (p(0, 1) - 1).coerceIn(0, columns - 1); wrapPending = false }
            'd' -> { cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1); wrapPending = false }
            'H', 'f' -> {
                cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (p(1, 1) - 1).coerceIn(0, columns - 1)
                wrapPending = false
            }
            'J' -> eraseInDisplay(params.getOrNull(0) ?: 0)
            'K' -> eraseInLine(params.getOrNull(0) ?: 0)
            'L' -> insertLines(p(0, 1))
            'M' -> deleteLines(p(0, 1))
            '@' -> insertChars(p(0, 1))
            'P' -> deleteChars(p(0, 1))
            'X' -> eraseChars(p(0, 1))
            'S' -> scrollUp(p(0, 1))
            'T' -> scrollDown(p(0, 1))
            'r' -> setScrollRegion(params)
            'm' -> attrs = Sgr.apply(attrs, params, a.subparams)
            'h' -> setModes(params, a.privateMarker, true)
            'l' -> setModes(params, a.privateMarker, false)
            's' -> saveCursor()
            'u' -> restoreCursor()
            'n' -> deviceStatusReport(params)
            'c' -> if (a.privateMarker == null) responses.add("\u001B[?6c")
            'g' -> Unit // tab clear (fixed tab stops)
            'b' -> lastChar?.let { repeat(p(0, 1)) { putChar(it, 1) } }
            'q' -> cursorShape = shapeFor(p(0, 1))
            else -> Unit
        }
    }

    private fun esc(a: Action.EscDispatch) {
        when (a.final) {
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'c' -> reset()
            else -> Unit // keypad/charset selections are recorded but not rendered
        }
    }

    private fun osc(a: Action.OscDispatch) {
        when (a.command) {
            0, 2 -> title = a.payload
            10 -> responses.add("\u001B]10;rgb:ffff/ffff/ffff\u0007")
            11 -> responses.add("\u001B]11;rgb:0000/0000/0000\u0007")
            else -> Unit // 8 hyperlink / 133 shell integration: accepted, rendered as plain text
        }
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (y in cursorRow + 1 until rows) buffer.clearRow(y)
            }
            1 -> {
                eraseInLine(1)
                for (y in 0 until cursorRow) buffer.clearRow(y)
            }
            2 -> buffer.clearAll()
            3 -> { buffer.clearAll(); scrollback.clear() }
        }
    }

    private fun eraseInLine(mode: Int) {
        when (mode) {
            0 -> buffer.clearRow(cursorRow, cursorCol, columns)
            1 -> buffer.clearRow(cursorRow, 0, cursorCol + 1)
            2 -> buffer.clearRow(cursorRow, 0, columns)
        }
    }

    private fun insertLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        repeat(n) {
            for (y in scrollBottom downTo cursorRow + 1) buffer.copyRow(y - 1, y)
            buffer.clearRow(cursorRow)
        }
    }

    private fun deleteLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        repeat(n) {
            for (y in cursorRow until scrollBottom) buffer.copyRow(y + 1, y)
            buffer.clearRow(scrollBottom)
        }
    }

    private fun insertChars(n: Int) {
        val count = n.coerceAtMost(columns - cursorCol)
        for (x in columns - 1 downTo cursorCol + count) {
            buffer.set(cursorRow, x, buffer.cell(cursorRow, x - count))
        }
        buffer.clearRow(cursorRow, cursorCol, cursorCol + count)
    }

    private fun deleteChars(n: Int) {
        val count = n.coerceAtMost(columns - cursorCol)
        for (x in cursorCol until columns - count) {
            buffer.set(cursorRow, x, buffer.cell(cursorRow, x + count))
        }
        buffer.clearRow(cursorRow, columns - count, columns)
    }

    private fun eraseChars(n: Int) {
        val count = n.coerceAtMost(columns - cursorCol)
        buffer.clearRow(cursorRow, cursorCol, cursorCol + count)
    }

    private fun setScrollRegion(params: List<Int>) {
        val top = ((params.getOrNull(0) ?: 1).takeIf { it != 0 } ?: 1) - 1
        val bottom = ((params.getOrNull(1) ?: rows).takeIf { it != 0 } ?: rows) - 1
        if (top in 0 until rows && bottom in 0 until rows && top < bottom) {
            scrollTop = top
            scrollBottom = bottom
            cursorRow = scrollTop
            cursorCol = 0
            wrapPending = false
        }
    }

    private fun setModes(params: List<Int>, marker: Char?, enable: Boolean) {
        for (m in params) {
            if (marker == '?') {
                when (m) {
                    1 -> applicationCursorKeys = enable
                    7 -> autoWrap = enable
                    25 -> cursorVisible = enable
                    47, 1047 -> if (enable) enterAlt(clear = false) else exitAlt()
                    1049 -> if (enable) enterAlt(clear = true) else exitAlt()
                    2004 -> bracketedPaste = enable
                    else -> Unit // mouse tracking modes are accepted but not acted on
                }
            }
        }
    }

    private fun enterAlt(clear: Boolean) {
        if (altActive) return
        savedCursor = SavedCursor(cursorRow, cursorCol, attrs)
        altActive = true
        if (clear) alt.clearAll()
        cursorRow = 0
        cursorCol = 0
        wrapPending = false
        viewportOffset = 0
    }

    private fun exitAlt() {
        if (!altActive) return
        altActive = false
        savedCursor?.let {
            cursorRow = it.row.coerceIn(0, rows - 1)
            cursorCol = it.col.coerceIn(0, columns - 1)
            attrs = it.attrs
        }
        savedCursor = null
        wrapPending = false
    }

    private fun saveCursor() {
        savedCursor = SavedCursor(cursorRow, cursorCol, attrs)
    }

    private fun restoreCursor() {
        savedCursor?.let {
            cursorRow = it.row.coerceIn(0, rows - 1)
            cursorCol = it.col.coerceIn(0, columns - 1)
            attrs = it.attrs
            wrapPending = false
        }
    }

    private fun deviceStatusReport(params: List<Int>) {
        when (params.getOrNull(0)) {
            5 -> responses.add("\u001B[0n")
            6 -> responses.add("\u001B[${cursorRow + 1};${cursorCol + 1}R")
        }
    }

    private fun shapeFor(n: Int): CursorShape = when (n) {
        3, 4 -> CursorShape.UNDERLINE
        5, 6 -> CursorShape.BAR
        else -> CursorShape.BLOCK
    }

    private fun reset() {
        primary = TerminalBuffer(columns, rows)
        alt = TerminalBuffer(columns, rows)
        altActive = false
        scrollback.clear()
        attrs = CellAttributes.DEFAULT
        cursorRow = 0; cursorCol = 0
        cursorVisible = true; cursorShape = CursorShape.BLOCK
        scrollTop = 0; scrollBottom = rows - 1
        autoWrap = true; wrapPending = false
        applicationCursorKeys = false; bracketedPaste = false
        savedCursor = null; lastChar = null; title = null; viewportOffset = 0
        parser.reset()
    }
}
