package com.aliedu.kern.feature.terminal

import com.aliedu.kern.core.terminal.api.EnvironmentInfo
import com.aliedu.kern.core.terminal.api.EnvironmentState
import com.aliedu.kern.core.terminal.api.ExecutionWorkspace
import com.aliedu.kern.core.terminal.api.InstallEvent
import com.aliedu.kern.core.terminal.api.InstallProgress
import com.aliedu.kern.core.terminal.api.TerminalOutput
import com.aliedu.kern.core.terminal.api.TerminalRuntime
import com.aliedu.kern.core.terminal.api.TerminalSession
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnvironmentSetupViewModelTest {

    private class FakeRuntime : TerminalRuntime {
        var info = EnvironmentInfo(
            state = EnvironmentState.NOT_INSTALLED,
            abi = "arm64-v8a",
            freeBytes = 2_000_000_000,
            requiredBytes = 400_000_000
        )
        val scripts = mutableListOf<List<InstallEvent>>()
        var installCalls = 0
        var log = "install start"
        var uninstalled = false

        override suspend fun inspectInstallState(): EnvironmentInfo = info
        override fun installOrRepair(
        distroId: String?,
        onProgress: (InstallProgress) -> Unit
    ): Flow<InstallEvent> = flow {
            val script = scripts.getOrElse(installCalls) { emptyList() }
            installCalls++
            script.forEach { event ->
                if (event is InstallEvent.Progress) onProgress(event.progress)
                if (event is InstallEvent.Completed) info = event.info
                emit(event)
            }
        }

        override suspend fun createSession(workspace: ExecutionWorkspace) = TerminalSession("s1", "Shell", workspace.workingDirectory)
        override fun sendInput(sessionId: String, input: ByteArray) {}
        override fun observeOutput(sessionId: String): Flow<TerminalOutput> = emptyFlow()
        override suspend fun requestStop(sessionId: String) {}
        override suspend fun closeSession(sessionId: String) {}
        override suspend fun uninstallEnvironment(): Result<Unit> {
            uninstalled = true
            info = EnvironmentInfo(EnvironmentState.NOT_INSTALLED, abi = "arm64-v8a")
            return Result.success(Unit)
        }

        override suspend fun installLog(): String = log
    }

    private lateinit var runtime: FakeRuntime

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runtime = FakeRuntime()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `refresh loads the environment info and log`() = runTest {
        val viewModel = EnvironmentSetupViewModel(runtime)
        val state = viewModel.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.info?.state).isEqualTo(EnvironmentState.NOT_INSTALLED)
        assertThat(state.info?.freeBytes).isEqualTo(2_000_000_000)
        assertThat(state.log).isEqualTo("install start")
    }

    @Test
    fun `install streams progress and ends READY`() = runTest {
        runtime.scripts.add(
            listOf(
                InstallEvent.Progress(InstallProgress("Downloading minirootfs", fraction = 0.5f)),
                InstallEvent.Progress(InstallProgress("Extracting minirootfs", fraction = 1f)),
                InstallEvent.Completed(EnvironmentInfo(EnvironmentState.READY, version = "3.20.9", abi = "arm64-v8a"))
            )
        )
        val viewModel = EnvironmentSetupViewModel(runtime)
        viewModel.install()
        val state = viewModel.state.value
        assertThat(state.installing).isFalse()
        assertThat(state.completed).isTrue()
        assertThat(state.info?.state).isEqualTo(EnvironmentState.READY)
        assertThat(state.error).isNull()
    }

    @Test
    fun `a failure surfaces the reason and a retry succeeds`() = runTest {
        runtime.scripts.add(listOf(InstallEvent.Failed("checksum mismatch")))
        runtime.scripts.add(
            listOf(InstallEvent.Completed(EnvironmentInfo(EnvironmentState.READY, version = "3.20.9")))
        )
        val viewModel = EnvironmentSetupViewModel(runtime)

        viewModel.install()
        assertThat(viewModel.state.value.error).isEqualTo("checksum mismatch")
        assertThat(viewModel.state.value.installing).isFalse()

        viewModel.install()
        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.completed).isTrue()
    }

    @Test
    fun `unsupported abi is reported as a state`() = runTest {
        runtime.info = EnvironmentInfo(EnvironmentState.UNSUPPORTED_ABI, abi = "x86")
        val viewModel = EnvironmentSetupViewModel(runtime)
        assertThat(viewModel.state.value.info?.state).isEqualTo(EnvironmentState.UNSUPPORTED_ABI)
    }

    @Test
    fun `uninstall clears the environment`() = runTest {
        runtime.info = EnvironmentInfo(EnvironmentState.READY, version = "3.20.9")
        val viewModel = EnvironmentSetupViewModel(runtime)
        viewModel.uninstall()
        assertThat(runtime.uninstalled).isTrue()
        assertThat(viewModel.state.value.info?.state).isEqualTo(EnvironmentState.NOT_INSTALLED)
    }

    @Test
    fun `toggleLog flips the expanded flag`() = runTest {
        val viewModel = EnvironmentSetupViewModel(runtime)
        assertThat(viewModel.state.value.logExpanded).isFalse()
        viewModel.toggleLog()
        assertThat(viewModel.state.value.logExpanded).isTrue()
    }
}
