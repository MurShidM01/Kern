package com.aliedu.kern.core.terminal.engine.vt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VtParserTest {

    private fun actions(input: String): List<Action> {
        val out = ArrayList<Action>()
        VtParser().parse(input) { out.add(it) }
        return out
    }

    @Test
    fun `plain text emits prints`() {
        val a = actions("ab")
        assertThat(a).containsExactly(
            Action.Print('a'.code, 1),
            Action.Print('b'.code, 1)
        )
    }

    @Test
    fun `csi sequence is decoded with params and final`() {
        val a = actions("\u001B[1;2H")
        assertThat(a).hasSize(1)
        val csi = a[0] as Action.CsiDispatch
        assertThat(csi.final).isEqualTo('H')
        assertThat(csi.params).containsExactly(1, 2).inOrder()
        assertThat(csi.privateMarker).isNull()
    }

    @Test
    fun `private marker is captured`() {
        val csi = actions("\u001B[?1049h")[0] as Action.CsiDispatch
        assertThat(csi.privateMarker).isEqualTo('?')
        assertThat(csi.params).containsExactly(1049)
        assertThat(csi.final).isEqualTo('h')
    }

    @Test
    fun `a sequence split across chunks is still parsed`() {
        val parser = VtParser()
        val out = ArrayList<Action>()
        parser.parse("\u001B[3", out::add)
        assertThat(out).isEmpty()
        parser.parse("1m", out::add)
        val csi = out.single() as Action.CsiDispatch
        assertThat(csi.final).isEqualTo('m')
        assertThat(csi.params).containsExactly(31)
    }

    @Test
    fun `osc title is dispatched on BEL`() {
        val osc = actions("\u001B]0;hello\u0007").single() as Action.OscDispatch
        assertThat(osc.command).isEqualTo(0)
        assertThat(osc.payload).isEqualTo("hello")
    }

    @Test
    fun `osc title is dispatched on ST`() {
        val osc = actions("\u001B]2;world\u001B\\").single() as Action.OscDispatch
        assertThat(osc.command).isEqualTo(2)
        assertThat(osc.payload).isEqualTo("world")
    }

    @Test
    fun `esc dispatch is decoded`() {
        val esc = actions("\u001B7").single() as Action.EscDispatch
        assertThat(esc.final).isEqualTo('7')
    }

    @Test
    fun `control characters become execute actions`() {
        val a = actions("\r\n")
        assertThat(a).containsExactly(
            Action.Execute('\r'),
            Action.Execute('\n')
        )
    }

    @Test
    fun `supplementary code point is emitted as one print`() {
        val a = actions("\uD83D\uDE00") // U+1F600
        assertThat(a).hasSize(1)
        val print = a[0] as Action.Print
        assertThat(print.cp).isEqualTo(0x1F600)
    }

    @Test
    fun `colon subparams are parsed`() {
        val csi = actions("\u001B[38:2:10:20:30m")[0] as Action.CsiDispatch
        assertThat(csi.params).containsExactly(38)
        assertThat(csi.subparams).containsExactly(listOf(38, 2, 10, 20, 30))
    }
}
