package com.aliedu.kern.core.tools.preview

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** The shared preview state the agent's tools and the preview screen both read. */
class PreviewControllerTest {

    private val controller = PreviewController()

    private class FakeHost : PreviewHost {
        val loaded = mutableListOf<String>()
        var reloads = 0
        var backs = 0
        var forwards = 0
        var evalResult: String? = "ok"
        var shot: ByteArray? = byteArrayOf(1, 2, 3)

        override suspend fun load(url: String) {
            loaded.add(url)
        }

        override suspend fun reload() {
            reloads++
        }

        override suspend fun goBack() {
            backs++
        }

        override suspend fun goForward() {
            forwards++
        }

        override suspend fun evaluate(js: String): String? = evalResult

        override suspend fun screenshot(): ByteArray? = shot
    }

    @Test
    fun `attaching and detaching flips the attached flag`() {
        assertThat(controller.state.value.attached).isFalse()
        controller.attach(FakeHost())
        assertThat(controller.state.value.attached).isTrue()
        assertThat(controller.isAttached).isTrue()
        controller.attach(null)
        assertThat(controller.state.value.attached).isFalse()
    }

    @Test
    fun `opening a url loads it when a screen is attached`(): Unit = runBlocking {
        val host = FakeHost()
        controller.attach(host)

        controller.open("http://127.0.0.1:8000")

        assertThat(host.loaded).containsExactly("http://127.0.0.1:8000")
        assertThat(controller.state.value.url).isEqualTo("http://127.0.0.1:8000")
    }

    @Test
    fun `opening a url with no screen attached still records it for later`(): Unit = runBlocking {
        controller.open("http://127.0.0.1:5173")
        assertThat(controller.state.value.url).isEqualTo("http://127.0.0.1:5173")

        // The screen picks the remembered URL up when it attaches.
        val host = FakeHost()
        controller.attach(host)
        controller.open(controller.state.value.url!!)
        assertThat(host.loaded).containsExactly("http://127.0.0.1:5173")
    }

    @Test
    fun `navigation commands reach the host`(): Unit = runBlocking {
        val host = FakeHost()
        controller.attach(host)

        controller.reload()
        controller.goBack()
        controller.goForward()

        assertThat(host.reloads).isEqualTo(1)
        assertThat(host.backs).isEqualTo(1)
        assertThat(host.forwards).isEqualTo(1)
    }

    @Test
    fun `evaluate and screenshot return null without a host`(): Unit = runBlocking {
        assertThat(controller.evaluate("document.title")).isNull()
        assertThat(controller.screenshot()).isNull()
    }

    @Test
    fun `evaluate and screenshot delegate to the host`(): Unit = runBlocking {
        val host = FakeHost()
        host.evalResult = "\"My App\""
        controller.attach(host)

        assertThat(controller.evaluate("document.title")).isEqualTo("\"My App\"")
        assertThat(controller.screenshot()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `console entries are captured newest-last and can be cleared`() {
        controller.onConsole(ConsoleLevel.LOG, "hello", "app.js", 10)
        controller.onConsole(ConsoleLevel.ERROR, "boom", "app.js", 20)

        val entries = controller.console.value
        assertThat(entries).hasSize(2)
        assertThat(entries[0].message).isEqualTo("hello")
        assertThat(entries[0].source).isEqualTo("app.js")
        assertThat(entries[1].level).isEqualTo(ConsoleLevel.ERROR)

        controller.clearConsole()
        assertThat(controller.console.value).isEmpty()
    }

    @Test
    fun `the console buffer is a bounded ring`() {
        repeat(PreviewController.MAX_ENTRIES + 25) { index ->
            controller.onConsole(ConsoleLevel.LOG, "line $index", null, 0)
        }

        val entries = controller.console.value
        assertThat(entries).hasSize(PreviewController.MAX_ENTRIES)
        // The oldest lines were dropped, so the first retained line is not "line 0".
        assertThat(entries.first().message).isEqualTo("line 25")
        assertThat(entries.last().message).isEqualTo("line ${PreviewController.MAX_ENTRIES + 24}")
    }

    @Test
    fun `a page error is also recorded as a console error`() {
        controller.onError("net::ERR_CONNECTION_REFUSED")

        assertThat(controller.state.value.error).isEqualTo("net::ERR_CONNECTION_REFUSED")
        assertThat(controller.state.value.loading).isFalse()
        assertThat(controller.console.value.single().level).isEqualTo(ConsoleLevel.ERROR)
    }

    @Test
    fun `page lifecycle callbacks update the state`() {
        controller.onPageStarted("http://127.0.0.1:8000/")
        assertThat(controller.state.value.loading).isTrue()
        assertThat(controller.state.value.progress).isEqualTo(0)

        controller.onProgress(60)
        assertThat(controller.state.value.progress).isEqualTo(60)

        controller.onPageFinished("http://127.0.0.1:8000/", "My App")
        assertThat(controller.state.value.loading).isFalse()
        assertThat(controller.state.value.title).isEqualTo("My App")

        controller.onHistoryChanged(canGoBack = true, canGoForward = false)
        assertThat(controller.state.value.canGoBack).isTrue()
        assertThat(controller.state.value.canGoForward).isFalse()
    }

    @Test
    fun `requesting the preview bumps the open signal`() {
        val before = controller.openSignal.value
        controller.requestOpen()
        assertThat(controller.openSignal.value).isEqualTo(before + 1)
    }
}
