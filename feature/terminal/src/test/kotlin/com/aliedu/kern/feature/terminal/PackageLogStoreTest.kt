package com.aliedu.kern.feature.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PackageLogStoreTest {

    @Test
    fun `begin shows the command and clears earlier output`() {
        val store = PackageLogStore()
        store.append("stale\n")

        store.begin("apk add jq")

        assertThat(store.lines.value).containsExactly("$ apk add jq")
    }

    @Test
    fun `a line split across chunks is emitted once, whole`() {
        val store = PackageLogStore()
        store.begin("apk add jq")

        store.append("(1/2) Instal")
        store.append("ling jq\n(2/2) OK\n")

        assertThat(store.lines.value)
            .containsExactly("$ apk add jq", "(1/2) Installing jq", "(2/2) OK")
            .inOrder()
    }

    @Test
    fun `drops the echoed command, the sentinel and everything after it`() {
        val store = PackageLogStore()
        store.begin("apk add jq")

        store.append("apk add jq\r\n")
        store.append("(1/1) Installing jq\r\n")
        store.append("printf '\\n__KERN_DONE_abc % s\\n' \"\$?\"\r\n")
        store.append("trailing junk\r\n")

        assertThat(store.lines.value)
            .containsExactly("$ apk add jq", "(1/1) Installing jq")
            .inOrder()
    }

    @Test
    fun `drops a bare shell prompt line`() {
        val store = PackageLogStore()
        store.begin("apk info")

        store.append("\$ \r\nzlib\r\n")

        assertThat(store.lines.value).containsExactly("$ apk info", "zlib").inOrder()
    }

    @Test
    fun `clear empties the log`() {
        val store = PackageLogStore()
        store.begin("apk info")
        store.append("zlib\n")

        store.clear()

        assertThat(store.lines.value).isEmpty()
    }
}
