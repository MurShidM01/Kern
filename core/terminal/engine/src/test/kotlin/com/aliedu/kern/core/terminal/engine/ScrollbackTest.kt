package com.aliedu.kern.core.terminal.engine

import com.aliedu.kern.core.terminal.engine.model.Cell
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrollbackTest {

    private fun row(text: String) = listOf(Cell(text))

    @Test
    fun `rows are kept in order`() {
        val sb = Scrollback(10)
        sb.push(row("a"))
        sb.push(row("b"))
        assertThat(sb.size).isEqualTo(2)
        assertThat(sb.row(0).first().text).isEqualTo("a")
        assertThat(sb.row(1).first().text).isEqualTo("b")
    }

    @Test
    fun `oldest rows are evicted past the limit`() {
        val sb = Scrollback(2)
        sb.push(row("a"))
        sb.push(row("b"))
        sb.push(row("c"))
        assertThat(sb.size).isEqualTo(2)
        assertThat(sb.row(0).first().text).isEqualTo("b")
        assertThat(sb.row(1).first().text).isEqualTo("c")
    }

    @Test
    fun `clear empties the buffer`() {
        val sb = Scrollback(2)
        sb.push(row("a"))
        sb.clear()
        assertThat(sb.size).isEqualTo(0)
    }
}
