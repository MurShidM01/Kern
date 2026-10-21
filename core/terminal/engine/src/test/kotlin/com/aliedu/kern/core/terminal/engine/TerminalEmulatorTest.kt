package com.aliedu.kern.core.terminal.engine

import com.aliedu.kern.core.terminal.engine.model.TerminalColor
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TerminalEmulatorTest {

    private fun TerminalEmulator.rowText(y: Int): String =
        snapshot().cells[y].joinToString("") { if (it.continuation) "" else it.text }

    private fun emulator(columns: Int = 10, rows: Int = 4, scrollback: Int = 100) =
        TerminalEmulator(columns, rows, scrollback)

    @Test
    fun `printing advances the cursor`() {
        val t = emulator()
        t.feed("abc")
        assertThat(t.rowText(0).trimEnd()).isEqualTo("abc")
        assertThat(t.snapshot().cursor.col).isEqualTo(3)
    }

    @Test
    fun `carriage return and line feed move the cursor`() {
        val t = emulator()
        t.feed("ab\r\ncd")
        assertThat(t.rowText(0).trimEnd()).isEqualTo("ab")
        assertThat(t.rowText(1).trimEnd()).isEqualTo("cd")
    }

    @Test
    fun `cursor position is set by CUP`() {
        val t = emulator()
        t.feed("\u001B[2;3HX")
        assertThat(t.rowText(1)[2]).isEqualTo('X')
    }

    @Test
    fun `erase in display clears the screen`() {
        val t = emulator()
        t.feed("hello")
        t.feed("\u001B[2J")
        assertThat(t.rowText(0).trim()).isEmpty()
    }

    @Test
    fun `erase in line clears to the end`() {
        val t = emulator()
        t.feed("abcdef\u001B[3G\u001B[K")
        assertThat(t.rowText(0).trimEnd()).isEqualTo("ab")
    }

    @Test
    fun `sgr sets foreground colour`() {
        val t = emulator()
        t.feed("\u001B[31mX")
        assertThat(t.snapshot().cells[0][0].attrs.fg).isEqualTo(TerminalColor.Indexed(1))
    }

    @Test
    fun `sgr 256 colour`() {
        val t = emulator()
        t.feed("\u001B[38;5;196mX")
        assertThat(t.snapshot().cells[0][0].attrs.fg).isEqualTo(TerminalColor.Indexed(196))
    }

    @Test
    fun `sgr truecolour`() {
        val t = emulator()
        t.feed("\u001B[38;2;10;20;30mX")
        assertThat(t.snapshot().cells[0][0].attrs.fg).isEqualTo(TerminalColor.Rgb(10, 20, 30))
    }

    @Test
    fun `sgr reset clears attributes`() {
        val t = emulator()
        t.feed("\u001B[1;31mX\u001B[0mY")
        assertThat(t.snapshot().cells[0][1].attrs.bold).isFalse()
        assertThat(t.snapshot().cells[0][1].attrs.fg).isEqualTo(TerminalColor.Default)
    }

    @Test
    fun `auto wrap moves to the next line`() {
        val t = emulator(columns = 5, rows = 4)
        t.feed("abcde")
        assertThat(t.snapshot().cursor.col).isEqualTo(4)
        t.feed("f")
        assertThat(t.rowText(1).trimEnd()).isEqualTo("f")
    }

    @Test
    fun `wide characters occupy two cells`() {
        val t = emulator()
        t.feed("\u4E2DX")
        assertThat(t.snapshot().cells[0][0].width).isEqualTo(2)
        assertThat(t.snapshot().cells[0][1].continuation).isTrue()
        assertThat(t.snapshot().cells[0][2].text).isEqualTo("X")
    }

    @Test
    fun `alternate screen switches and restores`() {
        val t = emulator()
        t.feed("main")
        t.feed("\u001B[?1049h")
        assertThat(t.snapshot().altScreen).isTrue()
        t.feed("alt")
        assertThat(t.rowText(0).trimEnd()).isEqualTo("alt")
        t.feed("\u001B[?1049l")
        assertThat(t.snapshot().altScreen).isFalse()
        assertThat(t.rowText(0).trimEnd()).isEqualTo("main")
    }

    @Test
    fun `scrolled lines enter scrollback`() {
        val t = emulator(columns = 5, rows = 2, scrollback = 10)
        t.feed("a\r\nb\r\nc")
        assertThat(t.snapshot().scrollbackSize).isEqualTo(1)
    }

    @Test
    fun `scroll region restricts scrolling`() {
        val t = emulator(columns = 5, rows = 5)
        t.feed("1\r\n2\r\n3\r\n4\r\n5")
        t.feed("\u001B[2;4r") // region rows 2..4
        t.feed("\u001B[4;1H\n") // LF on the bottom of the region scrolls it
        assertThat(t.rowText(0).trimEnd()).isEqualTo("1")
        assertThat(t.snapshot().scrollbackSize).isEqualTo(0)
    }

    @Test
    fun `device status report returns cursor position`() {
        val t = emulator()
        t.feed("\u001B[3;4H")
        val responses = t.feed("\u001B[6n")
        assertThat(responses).containsExactly("\u001B[3;4R")
    }

    @Test
    fun `osc title is stored`() {
        val t = emulator()
        t.feed("\u001B]0;my title\u0007")
        assertThat(t.snapshot().title).isEqualTo("my title")
    }

    @Test
    fun `resize changes reported dimensions`() {
        val t = emulator()
        t.resize(20, 6)
        assertThat(t.snapshot().columns).isEqualTo(20)
        assertThat(t.snapshot().rows).isEqualTo(6)
    }

    @Test
    fun `short screen is filled from scrollback so the prompt ends on the last row`() {
        val t = emulator(columns = 5, rows = 6, scrollback = 50)
        t.feed("1\r\n2\r\n3\r\n4\r\n5\r\n6\r\n7\r\n8\r\n9")
        // The view grows (keyboard closed): the shell keeps its cursor where it was, which would
        // leave blank rows under the prompt. The history above is pulled in to close that band.
        t.resize(5, 9)
        val snap = t.snapshot()
        assertThat(snap.cells[0].joinToString("") { it.text }.trimEnd()).isEqualTo("1")
        assertThat(snap.cells[snap.rows - 1].joinToString("") { it.text }.trimEnd()).isEqualTo("9")
        assertThat(snap.cursor.row).isEqualTo(snap.rows - 1)
    }

    @Test
    fun `without history the prompt keeps its own row`() {
        val t = emulator(columns = 5, rows = 6, scrollback = 50)
        t.feed("hi")
        val snap = t.snapshot()
        assertThat(snap.cursor.row).isEqualTo(0)
        assertThat(snap.cells[0].joinToString("") { it.text }.trimEnd()).isEqualTo("hi")
    }

    @Test
    fun `scrolling back down re-pins to the filled view instead of a dead zone`() {
        val t = emulator(columns = 5, rows = 6, scrollback = 50)
        t.feed("1\r\n2\r\n3\r\n4\r\n5\r\n6\r\n7\r\n8\r\n9\r\n10\r\n11\r\n12")
        t.resize(5, 9)
        // Auto-filled view starts at history row 4.
        assertThat(t.snapshot().cells[0].joinToString("") { it.text }.trimEnd()).isEqualTo("4")
        t.scrollViewportBy(1)
        assertThat(t.snapshot().cells[0].joinToString("") { it.text }.trimEnd()).isEqualTo("3")
        t.scrollViewportBy(-1)
        assertThat(t.snapshot().cells[0].joinToString("") { it.text }.trimEnd()).isEqualTo("4")
    }

    @Test
    fun `clear view drops scrollback`() {
        val t = emulator(columns = 5, rows = 2, scrollback = 10)
        t.feed("a\r\nb\r\nc")
        t.clearView()
        assertThat(t.snapshot().scrollbackSize).isEqualTo(0)
    }
}
