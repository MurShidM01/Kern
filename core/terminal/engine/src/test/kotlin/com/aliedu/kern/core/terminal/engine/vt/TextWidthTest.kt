package com.aliedu.kern.core.terminal.engine.vt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextWidthTest {

    @Test
    fun `ascii is narrow`() {
        assertThat(TextWidth.charWidth('A'.code)).isEqualTo(1)
    }

    @Test
    fun `cjk is wide`() {
        assertThat(TextWidth.charWidth('\u4E2D'.code)).isEqualTo(2)
    }

    @Test
    fun `combining mark is zero width`() {
        assertThat(TextWidth.charWidth(0x0301)).isEqualTo(0)
    }

    @Test
    fun `control is zero width`() {
        assertThat(TextWidth.charWidth('\n'.code)).isEqualTo(0)
    }
}
