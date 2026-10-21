package com.aliedu.kern.core.terminal.implementation.runtime

import com.aliedu.kern.core.terminal.api.DistroOption
import com.aliedu.kern.core.terminal.api.EnvironmentInfo
import com.aliedu.kern.core.terminal.api.EnvironmentState
import com.aliedu.kern.core.terminal.api.ExecutionWorkspace
import com.aliedu.kern.core.terminal.api.InstallEvent
import com.aliedu.kern.core.terminal.api.InstallProgress
import com.aliedu.kern.core.terminal.api.SessionKind
import com.aliedu.kern.core.terminal.api.TerminalOutput
import com.aliedu.kern.core.terminal.api.TerminalRuntime
import com.aliedu.kern.core.terminal.api.TerminalSession
import com.aliedu.kern.core.terminal.implementation.install.AbiSupport
import com.aliedu.kern.core.terminal.implementation.install.EnvironmentInstaller
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Part 2 runtime. Sessions run on the device shell immediately; once the Alpine environment is
 * READY (and the device ABI can execute the proot runtime) sessions run inside Alpine instead.
 * Install-state concerns are delegated to [EnvironmentInstaller] (PRD 12.2).
 */
@Singleton
class LayeredTerminalRuntime @Inject constructor(
    private val installer: EnvironmentInstaller,
    private val sessions: TerminalSessionManager,
    private val abi: AbiSupport
) : TerminalRuntime {

    override suspend fun inspectInstallState(): EnvironmentInfo = installer.inspect()

    override fun installOrRepair(
        distroId: String?,
        onProgress: (InstallProgress) -> Unit
    ): Flow<InstallEvent> = flow {
        var installed = false
        installer.install(distroId, onProgress).collect { event ->
            if (event is InstallEvent.Completed) installed = true
            emit(event)
        }
        // A successful install may have replaced the rootfs under a running guest, so those sessions
        // are closed and rebuilt on the new one; a failed install leaves them alone.
        if (installed) sessions.closeByKind(SessionKind.LINUX_PROOT)
        retireStaleSessions()
    }

    override suspend fun availableDistros(): List<DistroOption> = installer.availableDistros()

    override suspend fun uninstallEnvironment(): Result<Unit> {
        val result = installer.uninstall()
        retireStaleSessions()
        return result
    }

    override suspend fun cleanIncompleteInstall(): Result<Unit> = installer.cleanIncomplete()

    override suspend fun preferredSessionKind(): SessionKind = preferredKind()

    override suspend fun createSession(workspace: ExecutionWorkspace): TerminalSession =
        sessions.create(workspace, preferredKind())

    override fun sendInput(sessionId: String, input: ByteArray) = sessions.send(sessionId, input)

    override fun observeOutput(sessionId: String): Flow<TerminalOutput> = sessions.observe(sessionId)

    override suspend fun requestStop(sessionId: String) = sessions.requestStop(sessionId)

    override fun resizeSession(sessionId: String, columns: Int, rows: Int) =
        sessions.resize(sessionId, columns, rows)

    override suspend fun closeSession(sessionId: String) = sessions.close(sessionId)

    override suspend fun listSessions(): List<TerminalSession> = sessions.list()

    override suspend fun findSessionByCwd(cwd: String, kind: SessionKind?): TerminalSession? =
        sessions.findByCwd(cwd, kind ?: preferredKind())

    override suspend fun renameSession(sessionId: String, displayName: String) =
        sessions.rename(sessionId, displayName)

    override suspend fun replay(sessionId: String): String = sessions.replay(sessionId)

    override suspend fun installLog(): String = installer.logText()

    /**
     * The backend a *new* session should use. Alpine wins as soon as it is installed, the device can
     * execute the bundled proot, and the sandbox actually launches; otherwise sessions run on the
     * device shell — a terminal that cannot start a guest is worse than the built-in shell.
     *
     * [installer.inspect] does real I/O (it parses the bundle manifest and stats the rootfs), and this
     * is consulted on every command, so the answer is memoised for a short window.
     */
    private suspend fun preferredKind(): SessionKind {
        val now = System.currentTimeMillis()
        cachedKind?.let { if (now - cachedAtMs < KIND_CACHE_MS) return it }
        val info = installer.inspect()
        val ready = info.state == EnvironmentState.READY && info.runtimeUsable && abi.runtimeSupported()
        val kind = if (ready) SessionKind.LINUX_PROOT else SessionKind.DEVICE_SHELL
        cachedKind = kind
        cachedAtMs = now
        return kind
    }

    /**
     * After an install/uninstall the environment a session runs in may have changed. Sessions on the
     * now-stale backend are closed so the terminal (and the command executor) rebuild them on the new
     * one — this is what makes "install Alpine, return to the terminal" pick up the guest instead of
     * silently keeping the old device shell.
     */
    private suspend fun retireStaleSessions() {
        cachedKind = null
        val stale = if (preferredKind() == SessionKind.LINUX_PROOT) {
            SessionKind.DEVICE_SHELL
        } else {
            SessionKind.LINUX_PROOT
        }
        sessions.closeByKind(stale)
    }

    @Volatile
    private var cachedKind: SessionKind? = null

    @Volatile
    private var cachedAtMs: Long = 0

    private companion object {
        /** How long a preferred-backend answer is trusted before [installer] is consulted again. */
        const val KIND_CACHE_MS = 2_000L
    }
}
