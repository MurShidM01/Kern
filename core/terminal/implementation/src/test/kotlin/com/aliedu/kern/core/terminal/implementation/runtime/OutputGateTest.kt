package com.aliedu.kern.core.terminal.implementation.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The gate is what keeps the terminal's echo out of everything the model and the job card read.
 * The echo used to be removed by matching the typed line character by character, which failed on
 * every long command — the shell re-wraps the line — and left the script, prompts and escape
 * sequences in the captured output.
 */
class OutputGateTest {

    private val marker = "__KERN_abc123__"

    @Test
    fun `the echoed script before the marker is dropped`() {
        val gate = OutputGate(marker)

        val before = gate.accept("root@localhost:/workspace# KERN_S=abc123\r\nKERN_M=\"__KERN_\${KERN_S}__\"\r\n")
        val after = gate.accept("\r\n__KERN_abc123__\r\nhello\r\n")

        assertThat(before).isEmpty()
        assertThat(after).isEqualTo("\r\nhello\r\n")
    }

    @Test
    fun `a marker split across two reads still opens the gate`() {
        val gate = OutputGate(marker)

        // The echo names the seed, never the marker, so a split can only happen in real output.
        assertThat(gate.accept("KERN_S=abc123\r\nKERN_M=\"__KERN_\${KERN_S}__\"\r\n")).isEmpty()
        assertThat(gate.accept("\r\n__KERN_abc")).isEmpty()
        val rest = gate.accept("123__\r\nbuild ok\r\n")

        // Everything up to and including the marker is echo; the output starts right after it.
        assertThat(rest).isEqualTo("\r\nbuild ok\r\n")
    }

    @Test
    fun `output that arrives before the marker is kept, not thrown away`() {
        // A shell with echo off prints the marker first, but a misbehaving one might not. Holding
        // the text is fine; dropping it would silently lose the command's output.
        val gate = OutputGate(marker)

        val first = gate.accept("x".repeat(5_000))

        assertThat(first).hasLength(5_000)
    }

    @Test
    fun `flush releases what is still held`() {
        val gate = OutputGate(marker)

        assertThat(gate.accept("partial")).isEmpty()
        assertThat(gate.flush()).isEqualTo("partial")
        // Nothing is released twice.
        assertThat(gate.flush()).isEmpty()
    }
}
