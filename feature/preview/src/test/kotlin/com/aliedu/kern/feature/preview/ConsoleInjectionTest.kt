package com.aliedu.kern.feature.preview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The injected shim is the only place the preview adds JavaScript to a page, so its contract is
 * worth pinning: it must be idempotent, it must talk to the bridge by the name the host registered,
 * and it must not re-wrap `console.*` (which the native callback already reports).
 */
class ConsoleInjectionTest {

    @Test
    fun `the shim installs itself only once`() {
        assertThat(ConsoleInjection.SCRIPT).contains("window.__kernHooked")
        assertThat(ConsoleInjection.SCRIPT).contains("if (window.__kernHooked) { return; }")
        assertThat(ConsoleInjection.SCRIPT).contains("window.__kernHooked = true")
    }

    @Test
    fun `the shim reports through the registered bridge`() {
        assertThat(ConsoleInjection.BRIDGE_NAME).isEqualTo("KernPreviewBridge")
        assertThat(ConsoleInjection.SCRIPT).contains("window.${ConsoleInjection.BRIDGE_NAME}.report")
    }

    @Test
    fun `uncaught errors and rejected promises are captured`() {
        assertThat(ConsoleInjection.SCRIPT).contains("addEventListener('error'")
        assertThat(ConsoleInjection.SCRIPT).contains("addEventListener('unhandledrejection'")
    }

    @Test
    fun `console itself is left to the native callback`() {
        // Patching console.log here would double every line, because WebChromeClient reports them.
        assertThat(ConsoleInjection.SCRIPT).doesNotContain("console.log =")
        assertThat(ConsoleInjection.SCRIPT).doesNotContain("console.error =")
    }
}
