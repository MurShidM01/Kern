package com.aliedu.kern.core.terminal.implementation.runtime

import com.aliedu.kern.core.terminal.api.EnvironmentInfo
import com.aliedu.kern.core.terminal.api.EnvironmentState
import com.aliedu.kern.core.terminal.api.ExecutionWorkspace
import com.aliedu.kern.core.terminal.api.InstallEvent
import com.aliedu.kern.core.terminal.api.InstallProgress
import com.aliedu.kern.core.terminal.api.SessionKind
import com.aliedu.kern.core.terminal.implementation.install.AbiSupport
import com.aliedu.kern.core.terminal.implementation.install.EnvironmentInstaller
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Test

private class FakeInstaller(
    var state: EnvironmentState,
    var runtimeUsable: Boolean = true
) : EnvironmentInstaller {
    override suspend fun inspect(): EnvironmentInfo =
        EnvironmentInfo(
            state = state,
            distroId = "alpine",
            version = "3.20",
            abi = "arm64-v8a",
            runtimeUsable = runtimeUsable
        )

    override fun install(distroId: String?, onProgress: (InstallProgress) -> Unit): Flow<InstallEvent> = flow {
        state = EnvironmentState.READY
        emit(InstallEvent.Completed(EnvironmentInfo(EnvironmentState.READY)))
    }

    override suspend fun uninstall(): Result<Unit> = Result.success(Unit)
    override suspend fun cleanIncomplete(): Result<Unit> = Result.success(Unit)
}

class LayeredTerminalRuntimeTest {

    private fun runtime(installer: EnvironmentInstaller, backend: FakeBackend, abi: List<String> = listOf("arm64-v8a")) =
        LayeredTerminalRuntime(installer, TerminalSessionManager(setOf(backend)), AbiSupport(abi))

    @Test
    fun `inspect delegates to the installer`(): Unit = runBlocking {
        val rt = runtime(FakeInstaller(EnvironmentState.NOT_INSTALLED), FakeBackend())
        assertThat(rt.inspectInstallState().state).isEqualTo(EnvironmentState.NOT_INSTALLED)
        assertThat(rt.inspectInstallState().distroId).isEqualTo("alpine")
    }

    @Test
    fun `session uses the device shell when not ready`(): Unit = runBlocking {
        val rt = runtime(FakeInstaller(EnvironmentState.NOT_INSTALLED), FakeBackend(SessionKind.DEVICE_SHELL))
        assertThat(rt.createSession(ExecutionWorkspace("w", "/w")).kind).isEqualTo(SessionKind.DEVICE_SHELL)
    }

    @Test
    fun `session uses alpine when ready and supported`(): Unit = runBlocking {
        val device = FakeBackend(SessionKind.DEVICE_SHELL)
        val alpine = FakeBackend(SessionKind.LINUX_PROOT)
        val rt = LayeredTerminalRuntime(
            FakeInstaller(EnvironmentState.READY),
            TerminalSessionManager(setOf(device, alpine)),
            AbiSupport(listOf("arm64-v8a"))
        )
        assertThat(rt.createSession(ExecutionWorkspace("w", "/w")).kind).isEqualTo(SessionKind.LINUX_PROOT)
    }

    @Test
    fun `session uses alpine on an x86_64 emulator`(): Unit = runBlocking {
        val device = FakeBackend(SessionKind.DEVICE_SHELL)
        val alpine = FakeBackend(SessionKind.LINUX_PROOT)
        val rt = LayeredTerminalRuntime(
            FakeInstaller(EnvironmentState.READY),
            TerminalSessionManager(setOf(device, alpine)),
            AbiSupport(listOf("x86_64"))
        )
        assertThat(rt.createSession(ExecutionWorkspace("w", "/w")).kind).isEqualTo(SessionKind.LINUX_PROOT)
    }

    @Test
    fun `session stays on the device shell when the runtime abi is unsupported`(): Unit = runBlocking {
        val device = FakeBackend(SessionKind.DEVICE_SHELL)
        val alpine = FakeBackend(SessionKind.LINUX_PROOT)
        val rt = LayeredTerminalRuntime(
            FakeInstaller(EnvironmentState.READY),
            TerminalSessionManager(setOf(device, alpine)),
            AbiSupport(listOf("armeabi-v7a"))
        )
        assertThat(rt.createSession(ExecutionWorkspace("w", "/w")).kind).isEqualTo(SessionKind.DEVICE_SHELL)
    }

    @Test
    fun `session stays on the device shell when the installed sandbox cannot start`(): Unit = runBlocking {
        val device = FakeBackend(SessionKind.DEVICE_SHELL)
        val alpine = FakeBackend(SessionKind.LINUX_PROOT)
        val rt = LayeredTerminalRuntime(
            FakeInstaller(EnvironmentState.READY, runtimeUsable = false),
            TerminalSessionManager(setOf(device, alpine)),
            AbiSupport(listOf("x86_64"))
        )
        // Installed, ABI supported, but the kernel denies the proot sandbox: a terminal that cannot
        // start a guest is worse than the built-in shell.
        assertThat(rt.createSession(ExecutionWorkspace("w", "/w")).kind).isEqualTo(SessionKind.DEVICE_SHELL)
    }

    @Test
    fun `list and rename delegate to the session manager`(): Unit = runBlocking {
        val rt = runtime(FakeInstaller(EnvironmentState.NOT_INSTALLED), FakeBackend())
        val s = rt.createSession(ExecutionWorkspace("w", "/w"))
        rt.renameSession(s.id, "Renamed")
        assertThat(rt.listSessions().first().displayName).isEqualTo("Renamed")
    }

    @Test
    fun `a device-shell session is not reused once alpine is ready`(): Unit = runBlocking {
        val device = FakeBackend(SessionKind.DEVICE_SHELL)
        val alpine = FakeBackend(SessionKind.LINUX_PROOT)
        val installer = FakeInstaller(EnvironmentState.NOT_INSTALLED)
        val rt = LayeredTerminalRuntime(
            installer,
            TerminalSessionManager(setOf(device, alpine)),
            AbiSupport(listOf("arm64-v8a"))
        )

        val shell = rt.createSession(ExecutionWorkspace("w", "/w"))
        assertThat(shell.kind).isEqualTo(SessionKind.DEVICE_SHELL)
        assertThat(rt.findSessionByCwd("/w")?.id).isEqualTo(shell.id)

        // Installing Alpine retires the device shell: the same cwd must no longer resolve to it, so
        // the executor builds an Alpine session instead of running `apk` on /system/bin/sh.
        rt.installOrRepair(null) { }.collect { }

        assertThat(rt.preferredSessionKind()).isEqualTo(SessionKind.LINUX_PROOT)
        assertThat(rt.findSessionByCwd("/w")).isNull()
    }

    @Test
    fun `installing alpine retires the device-shell sessions`(): Unit = runBlocking {
        val device = FakeBackend(SessionKind.DEVICE_SHELL)
        val alpine = FakeBackend(SessionKind.LINUX_PROOT)
        val rt = LayeredTerminalRuntime(
            FakeInstaller(EnvironmentState.NOT_INSTALLED),
            TerminalSessionManager(setOf(device, alpine)),
            AbiSupport(listOf("arm64-v8a"))
        )

        rt.createSession(ExecutionWorkspace("w", "/w"))
        assertThat(rt.listSessions()).hasSize(1)

        rt.installOrRepair(null) { }.collect { }

        assertThat(rt.listSessions()).isEmpty()
    }
}
