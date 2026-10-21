package com.aliedu.kern.core.tools.diff

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Backs P1-07 / T1-08: the agent must produce a reviewable unified diff, and the SHA-256 hash
 * binds an approval to the exact reviewed content (PRD 9.5).
 */
class DiffServiceTest {

    private val service = DiffService()

    @Test
    fun `unified diff contains context, insertions and deletions`() {
        val old = "line1\nline2\nline3\n"
        val new = "line1\nline2 changed\nline3\nline4\n"
        val diff = service.unifiedDiff("src/App.kt", old, new)

        assertThat(diff.path).isEqualTo("src/App.kt")
        assertThat(diff.unifiedDiff).contains("--- src/App.kt")
        assertThat(diff.unifiedDiff).contains("+++ src/App.kt")
        assertThat(diff.unifiedDiff).contains("+line4")
        assertThat(diff.unifiedDiff).contains("+line2 changed")
        assertThat(diff.unifiedDiff).contains("-line2")
        assertThat(diff.insertions).isEqualTo(2)
        assertThat(diff.deletions).isEqualTo(1)
    }

    @Test
    fun `identical content yields an empty diff with zero counts`() {
        val diff = service.unifiedDiff("a.txt", "same\n", "same\n")
        assertThat(diff.insertions).isEqualTo(0)
        assertThat(diff.deletions).isEqualTo(0)
        assertThat(diff.unifiedDiff.trim()).isEmpty()
    }

    @Test
    fun `hash is stable and matches a known SHA-256 vector`() {
        // SHA-256 of "abc"
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertThat(service.hash("abc")).isEqualTo(expected)
        assertThat(service.hash("abc")).isEqualTo(service.hash("abc"))
    }

    @Test
    fun `hash differs when a single character changes`() {
        assertThat(service.hash("value=1")).isNotEqualTo(service.hash("value=2"))
    }

    @Test
    fun `proposal hash is order-independent and content-sensitive`() {
        val a = "a.txt" to "content a"
        val b = "b.txt" to "content b"
        assertThat(service.proposalHash(listOf(a, b)))
            .isEqualTo(service.proposalHash(listOf(b, a)))
        assertThat(service.proposalHash(listOf(a, b)))
            .isNotEqualTo(service.proposalHash(listOf(a, "b.txt" to "content changed")))
    }
}
