package com.aliedu.kern.feature.terminal

import com.aliedu.kern.core.data.preferences.SettingsRepository
import com.aliedu.kern.core.data.repository.WorkspaceRepository
import com.aliedu.kern.core.terminal.api.EnvironmentInfo
import com.aliedu.kern.core.terminal.api.EnvironmentState
import com.aliedu.kern.core.terminal.api.ExecutionWorkspace
import com.aliedu.kern.core.terminal.api.InstallEvent
import com.aliedu.kern.core.terminal.api.InstallProgress
import com.aliedu.kern.core.terminal.api.TerminalOutput
import com.aliedu.kern.core.terminal.api.TerminalRuntime
import com.aliedu.kern.core.terminal.api.TerminalSession
import com.aliedu.kern.core.workspace.ExecutionWorkspaceResolver
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {

    private class FakeRuntime : TerminalRuntime {
        val sessions = mutableListOf<TerminalSession>()
        val flows = mutableMapOf<String, MutableSharedFlow<TerminalOutput>>()
        val sentInput = mutableListOf<Pair<String, ByteArray>>()
        val replays = mutableMapOf<String, String>()
        val closed = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()
        val stopped = mutableListOf<String>()
        var created = 0

        fun preload(id: String, name: String, cwd: String) {
            sessions.add(TerminalSession(id, name, cwd))
            flows[id] = MutableSharedFlow(extraBufferCapacity = 64)
        }

        override suspend fun inspectInstallState() = EnvironmentInfo(EnvironmentState.READY)
        override fun installOrRepair(
        distroId: String?,
        onProgress: (InstallProgress) -> Unit
    ): Flow<InstallEvent> = emptyFlow()
        override suspend fun createSession(workspace: ExecutionWorkspace): TerminalSession {
            val session = TerminalSession("s${++created}", "Shell $created", workspace.workingDirectory)
            sessions.add(session)
            flows[session.id] = MutableSharedFlow(extraBufferCapacity = 64)
            return session
        }

        override fun sendInput(sessionId: String, input: ByteArray) {
            sentInput.add(sessionId to input)
        }

        val resized = mutableListOf<Triple<String, Int, Int>>()

        override fun observeOutput(sessionId: String): Flow<TerminalOutput> = flows[sessionId] ?: emptyFlow()
        override suspend fun requestStop(sessionId: String) { stopped.add(sessionId) }

        override fun resizeSession(sessionId: String, columns: Int, rows: Int) {
            resized.add(Triple(sessionId, columns, rows))
        }
        override suspend fun closeSession(sessionId: String) { closed.add(sessionId) }
        override suspend fun uninstallEnvironment() = Result.success(Unit)
        override suspend fun listSessions(): List<TerminalSession> = sessions.toList()
        override suspend fun renameSession(sessionId: String, displayName: String) {
            renamed.add(sessionId to displayName)
        }

        override suspend fun replay(sessionId: String): String = replays[sessionId] ?: ""

        suspend fun emit(sessionId: String, output: TerminalOutput) {
            flows[sessionId]?.emit(output)
        }
    }

    private class FakeResolver : ExecutionWorkspaceResolver {
        override suspend fun prepare(workspaceId: String) = ExecutionWorkspace(workspaceId, "/tmp/$workspaceId")
        override fun scratchWorkspace() = ExecutionWorkspace("__scratch__", "/tmp/scratch")
    }

    private lateinit var runtime: FakeRuntime
    private lateinit var settings: SettingsRepository
    private lateinit var workspaces: WorkspaceRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runtime = FakeRuntime()
        settings = mockk(relaxed = true)
        workspaces = mockk(relaxed = true)
        every { settings.lastWorkspaceId } returns flowOf(null)
        every { settings.terminalFontSizeSp } returns flowOf(12f)
        every { settings.terminalScrollback } returns flowOf(1000)
        coEvery { workspaces.getWorkspace(any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = TerminalViewModel(runtime, FakeResolver(), settings, workspaces)

    private fun screenText(state: TerminalUiState): String =
        state.active?.snapshot?.cells?.joinToString("\n") { row ->
            row.joinToString("") { it.text }
        }.orEmpty()

    @Test
    fun `bootstrap creates a session and marks it active`() = runTest {
        val viewModel = newViewModel()
        val state = viewModel.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.sessions).hasSize(1)
        assertThat(state.activeSessionId).isEqualTo(state.sessions.first().id)
    }

    @Test
    fun `stdout is fed into the emulator and appears on screen`() = runTest {
        val viewModel = newViewModel()
        val id = viewModel.state.value.activeSessionId!!
        runtime.emit(id, TerminalOutput.Stdout("hello world"))
        assertThat(screenText(viewModel.state.value)).contains("hello world")
    }

    @Test
    fun `an existing session is re-attached and its replay seeds the screen`() = runTest {
        runtime.preload("s1", "Shell 1", "/tmp/ws")
        runtime.replays["s1"] = "previous output"
        val viewModel = newViewModel()
        assertThat(viewModel.state.value.sessions).hasSize(1)
        assertThat(viewModel.state.value.activeSessionId).isEqualTo("s1")
        assertThat(screenText(viewModel.state.value)).contains("previous output")
    }

    @Test
    fun `sendInput forwards the typed text and converts newline to carriage return`() = runTest {
        val viewModel = newViewModel()
        viewModel.sendInput("ls -la\n")
        assertThat(runtime.sentInput.last().second).isEqualTo("ls -la\r".toByteArray())
    }

    @Test
    fun `sendSequence forwards a raw escape sequence untouched`() = runTest {
        val viewModel = newViewModel()
        viewModel.sendSequence("\u001b[A")
        assertThat(runtime.sentInput.last().second).isEqualTo("\u001b[A".toByteArray())
    }

    @Test
    fun `onResize tells the runtime the new window size`() = runTest {
        val viewModel = newViewModel()
        viewModel.onResize(100, 30)
        assertThat(runtime.resized).containsExactly(Triple("s1", 100, 30))
    }

    @Test
    fun `the sidebar toggles open and closed`() = runTest {
        val viewModel = newViewModel()
        assertThat(viewModel.state.value.sidebarOpen).isFalse()
        viewModel.toggleSidebar()
        assertThat(viewModel.state.value.sidebarOpen).isTrue()
        viewModel.closeSidebar()
        assertThat(viewModel.state.value.sidebarOpen).isFalse()
    }

    @Test
    fun `selection begins, extends and clears`() = runTest {
        val viewModel = newViewModel()
        viewModel.beginSelection(CellPos(0, 0))
        viewModel.extendSelection(CellPos(0, 4))
        assertThat(viewModel.state.value.selection).isEqualTo(
            TerminalSelection(CellPos(0, 0), CellPos(0, 4))
        )
        viewModel.clearSelection()
        assertThat(viewModel.state.value.selection).isNull()
    }

    @Test
    fun `selectedText returns the text under the selection`() = runTest {
        val viewModel = newViewModel()
        val id = viewModel.state.value.activeSessionId!!
        runtime.emit(id, TerminalOutput.Stdout("hello"))
        viewModel.beginSelection(CellPos(0, 0))
        viewModel.extendSelection(CellPos(0, 4))
        assertThat(viewModel.selectedText()).isEqualTo("hello")
    }

    @Test
    fun `createSession adds a second session and selects it`() = runTest {
        val viewModel = newViewModel()
        viewModel.createSession()
        assertThat(viewModel.state.value.sessions).hasSize(2)
        assertThat(viewModel.state.value.activeSessionId).isEqualTo("s2")
    }

    @Test
    fun `closeSession removes it and selects the remaining one`() = runTest {
        val viewModel = newViewModel()
        viewModel.createSession()
        viewModel.closeSession("s2")
        assertThat(viewModel.state.value.sessions.map { it.id }).containsExactly("s1")
        assertThat(viewModel.state.value.activeSessionId).isEqualTo("s1")
        assertThat(runtime.closed).contains("s2")
    }

    @Test
    fun `sendKey ESC forwards the escape byte`() = runTest {
        val viewModel = newViewModel()
        viewModel.sendKey(TerminalKey.ESC)
        assertThat(runtime.sentInput.last().second).isEqualTo(byteArrayOf(0x1B))
    }

    @Test
    fun `the sticky ctrl modifier converts the next character to a control code`() = runTest {
        val viewModel = newViewModel()
        viewModel.toggleCtrl()
        assertThat(viewModel.state.value.ctrlArmed).isTrue()
        viewModel.sendInput("c")
        assertThat(runtime.sentInput.last().second).isEqualTo(byteArrayOf(0x03))
        assertThat(viewModel.state.value.ctrlArmed).isFalse()
    }

    @Test
    fun `the sticky alt modifier prefixes the next character with escape`() = runTest {
        val viewModel = newViewModel()
        viewModel.toggleAlt()
        viewModel.sendInput("x")
        assertThat(runtime.sentInput.last().second).isEqualTo(byteArrayOf(0x1B, 'x'.code.toByte()))
    }

    @Test
    fun `renameSession updates the display name`() = runTest {
        val viewModel = newViewModel()
        viewModel.renameSession("s1", "build")
        assertThat(runtime.renamed).contains("s1" to "build")
        assertThat(viewModel.state.value.sessions.first().displayName).isEqualTo("build")
    }

    @Test
    fun `an exit event marks the session as no longer alive`() = runTest {
        val viewModel = newViewModel()
        runtime.emit("s1", TerminalOutput.Exited(0))
        assertThat(viewModel.state.value.active!!.alive).isFalse()
        assertThat(viewModel.state.value.active!!.exitCode).isEqualTo(0)
    }

    @Test
    fun `stopActive requests a stop on the active session`() = runTest {
        val viewModel = newViewModel()
        viewModel.stopActive()
        assertThat(runtime.stopped).contains("s1")
    }
}
