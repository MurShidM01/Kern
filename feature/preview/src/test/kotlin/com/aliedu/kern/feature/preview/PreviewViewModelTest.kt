package com.aliedu.kern.feature.preview

import com.aliedu.kern.core.tools.devserver.DevServer
import com.aliedu.kern.core.tools.devserver.DevServerRegistry
import com.aliedu.kern.core.tools.devserver.DevServerState
import com.aliedu.kern.core.tools.preview.ConsoleLevel
import com.aliedu.kern.core.tools.preview.PreviewController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The preview screen's own decisions: which server to show, and what the console drawer filters. */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private class FakeServers : DevServerRegistry {
        private val _servers = MutableStateFlow<List<DevServer>>(emptyList())
        override val servers: StateFlow<List<DevServer>> = _servers
        val stopped = mutableListOf<String>()

        fun set(list: List<DevServer>) {
            _servers.value = list
        }

        override suspend fun start(
            command: String,
            port: Int?,
            workspaceId: String,
            cwd: String,
            onOutput: ((String) -> Unit)?
        ): Result<DevServer> = Result.failure(UnsupportedOperationException())

        override suspend fun stop(id: String): Boolean {
            stopped.add(id)
            return true
        }

        override fun isOwned(sessionId: String): Boolean = false
    }

    private fun server(id: String, port: Int, state: DevServerState) = DevServer(
        id = id,
        command = "serve --port $port",
        port = port,
        url = if (state == DevServerState.FAILED) null else "http://127.0.0.1:$port",
        state = state,
        startedAt = 0,
        sessionId = "s-$id"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `opening the screen points the preview at the newest running server`() = runTest(dispatcher) {
        val servers = FakeServers().apply {
            set(
                listOf(
                    server("a", 3000, DevServerState.RUNNING),
                    server("b", 4000, DevServerState.RUNNING)
                )
            )
        }
        val controller = PreviewController()
        val viewModel = PreviewViewModel(controller, servers)
        backgroundScope.launch { viewModel.state.collect {} }

        viewModel.onScreenOpened()
        advanceUntilIdle()

        assertThat(controller.state.value.url).isEqualTo("http://127.0.0.1:4000")
        assertThat(viewModel.state.value.activeServerId).isEqualTo("b")
    }

    @Test
    fun `a stopped or failed server is never auto-opened`() = runTest(dispatcher) {
        val servers = FakeServers().apply {
            set(
                listOf(
                    server("a", 3000, DevServerState.STOPPED),
                    server("b", 4000, DevServerState.FAILED)
                )
            )
        }
        val controller = PreviewController()
        val viewModel = PreviewViewModel(controller, servers)
        backgroundScope.launch { viewModel.state.collect {} }

        viewModel.onScreenOpened()
        advanceUntilIdle()

        assertThat(controller.state.value.url).isNull()
        assertThat(viewModel.state.value.hasSomethingToShow).isFalse()
    }

    @Test
    fun `opening the screen does not steal a URL the agent already chose`() = runTest(dispatcher) {
        val servers = FakeServers().apply { set(listOf(server("a", 3000, DevServerState.RUNNING))) }
        val controller = PreviewController()
        val viewModel = PreviewViewModel(controller, servers)
        backgroundScope.launch { viewModel.state.collect {} }
        controller.open("http://127.0.0.1:9999")

        viewModel.onScreenOpened()
        advanceUntilIdle()

        assertThat(controller.state.value.url).isEqualTo("http://127.0.0.1:9999")
    }

    @Test
    fun `selecting a server switches the preview to its url`() = runTest(dispatcher) {
        val servers = FakeServers().apply {
            set(listOf(server("a", 3000, DevServerState.RUNNING), server("b", 4000, DevServerState.RUNNING)))
        }
        val controller = PreviewController()
        val viewModel = PreviewViewModel(controller, servers)
        backgroundScope.launch { viewModel.state.collect {} }

        viewModel.selectServer(servers.servers.value.first())
        advanceUntilIdle()

        assertThat(controller.state.value.url).isEqualTo("http://127.0.0.1:3000")
        assertThat(viewModel.state.value.activeServerId).isEqualTo("a")
    }

    @Test
    fun `stopping a server goes through the registry`() = runTest(dispatcher) {
        val servers = FakeServers().apply { set(listOf(server("a", 3000, DevServerState.RUNNING))) }
        val viewModel = PreviewViewModel(PreviewController(), servers)
        backgroundScope.launch { viewModel.state.collect {} }

        viewModel.stopServer("a")
        advanceUntilIdle()

        assertThat(servers.stopped).containsExactly("a")
    }

    @Test
    fun `every level is shown until one is switched off`() = runTest(dispatcher) {
        val controller = PreviewController()
        val viewModel = PreviewViewModel(controller, FakeServers())
        backgroundScope.launch { viewModel.state.collect {} }
        controller.onConsole(ConsoleLevel.LOG, "hello", null, 0)
        controller.onConsole(ConsoleLevel.ERROR, "boom", "app.js", 12)
        advanceUntilIdle()

        assertThat(viewModel.state.value.levels).containsExactlyElementsIn(ConsoleLevel.entries)
        assertThat(viewModel.state.value.visibleConsole).hasSize(2)
        assertThat(viewModel.state.value.errorCount).isEqualTo(1)

        viewModel.toggleLevel(ConsoleLevel.ERROR)
        advanceUntilIdle()

        assertThat(viewModel.state.value.visibleConsole.map { it.message }).containsExactly("hello")

        viewModel.toggleLevel(ConsoleLevel.ERROR)
        advanceUntilIdle()

        assertThat(viewModel.state.value.visibleConsole).hasSize(2)
    }

    @Test
    fun `the console drawer opens, closes and clears`() = runTest(dispatcher) {
        val controller = PreviewController()
        val viewModel = PreviewViewModel(controller, FakeServers())
        backgroundScope.launch { viewModel.state.collect {} }
        controller.onConsole(ConsoleLevel.WARN, "careful", null, 0)
        advanceUntilIdle()

        viewModel.toggleConsole()
        advanceUntilIdle()
        assertThat(viewModel.state.value.consoleOpen).isTrue()

        viewModel.clearConsole()
        advanceUntilIdle()
        assertThat(viewModel.state.value.console).isEmpty()

        viewModel.toggleConsole()
        advanceUntilIdle()
        assertThat(viewModel.state.value.consoleOpen).isFalse()
    }
}
