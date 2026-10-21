package com.aliedu.kern.core.terminal.implementation.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamingUtf8Test {

    @Test
    fun `ascii decodes directly`() {
        val d = StreamingUtf8()
        val bytes = "hello".toByteArray(Charsets.UTF_8)
        assertThat(d.decode(bytes, bytes.size)).isEqualTo("hello")
    }

    @Test
    fun `a multibyte character split across chunks is decoded once`() {
        val d = StreamingUtf8()
        val bytes = "\u20AC".toByteArray(Charsets.UTF_8) // € = 3 bytes
        val first = d.decode(bytes.copyOfRange(0, 1), 1)
        val second = d.decode(bytes.copyOfRange(1, 3), 2)
        assertThat(first).isEmpty()
        assertThat(second).isEqualTo("\u20AC")
    }

    @Test
    fun `malformed bytes become replacement characters`() {
        val d = StreamingUtf8()
        val out = d.decode(byteArrayOf(0x41, 0xFF.toByte(), 0x42), 3)
        assertThat(out).contains("A")
        assertThat(out).contains("B")
        assertThat(out).contains("\uFFFD")
    }
}
