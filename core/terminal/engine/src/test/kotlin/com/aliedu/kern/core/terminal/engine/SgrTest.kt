package com.aliedu.kern.core.terminal.engine

import com.aliedu.kern.core.terminal.engine.model.CellAttributes
import com.aliedu.kern.core.terminal.engine.model.TerminalColor
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SgrTest {

    @Test
    fun `empty params reset attributes`() {
        val styled = Sgr.apply(CellAttributes(bold = true), emptyList(), emptyList())
        assertThat(styled).isEqualTo(CellAttributes.DEFAULT)
    }

    @Test
    fun `zero resets`() {
        val styled = Sgr.apply(CellAttributes(bold = true, underline = true), listOf(0), emptyList())
        assertThat(styled).isEqualTo(CellAttributes.DEFAULT)
    }

    @Test
    fun `bold and basic colours`() {
        val styled = Sgr.apply(CellAttributes.DEFAULT, listOf(1, 31, 44), emptyList())
        assertThat(styled.bold).isTrue()
        assertThat(styled.fg).isEqualTo(TerminalColor.Indexed(1))
        assertThat(styled.bg).isEqualTo(TerminalColor.Indexed(4))
    }

    @Test
    fun `bright colours map to 8-15`() {
        val styled = Sgr.apply(CellAttributes.DEFAULT, listOf(91, 102), emptyList())
        assertThat(styled.fg).isEqualTo(TerminalColor.Indexed(9))
        assertThat(styled.bg).isEqualTo(TerminalColor.Indexed(10))
    }

    @Test
    fun `256 colour foreground`() {
        val styled = Sgr.apply(CellAttributes.DEFAULT, listOf(38, 5, 200), emptyList())
        assertThat(styled.fg).isEqualTo(TerminalColor.Indexed(200))
    }

    @Test
    fun `truecolour background`() {
        val styled = Sgr.apply(CellAttributes.DEFAULT, listOf(48, 2, 1, 2, 3), emptyList())
        assertThat(styled.bg).isEqualTo(TerminalColor.Rgb(1, 2, 3))
    }

    @Test
    fun `truecolour via subparams`() {
        val styled = Sgr.apply(
            CellAttributes.DEFAULT,
            listOf(38),
            listOf(listOf(38, 2, 9, 8, 7))
        )
        assertThat(styled.fg).isEqualTo(TerminalColor.Rgb(9, 8, 7))
    }

    @Test
    fun `resets clear individual attributes`() {
        val bold = Sgr.apply(CellAttributes.DEFAULT, listOf(1), emptyList())
        val notBold = Sgr.apply(bold, listOf(22), emptyList())
        assertThat(notBold.bold).isFalse()
    }
}
