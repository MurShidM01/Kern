package com.aliedu.kern.core.terminal.implementation

import com.aliedu.kern.core.terminal.api.EnvironmentInfo
import com.aliedu.kern.core.terminal.api.EnvironmentState
import com.aliedu.kern.core.terminal.api.ExecutionWorkspace
import com.aliedu.kern.core.terminal.api.InstallEvent
import com.aliedu.kern.core.terminal.api.InstallProgress
import com.aliedu.kern.core.terminal.api.TerminalOutput
import com.aliedu.kern.core.terminal.api.TerminalRuntime
import com.aliedu.kern.core.terminal.api.TerminalSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Truthful placeholder used until Part 2 ships a real Linux-backed runtime (PRD 7.7, 5.2).
 * It never simulates a working terminal — it reports that no environment is installed.
 */
@Singleton
class UnavailableTerminalRuntime @Inject constructor() : TerminalRuntime {

    override suspend fun inspectInstallState(): EnvironmentInfo = EnvironmentInfo(
        state = EnvironmentState.NOT_INSTALLED,
        distroId = null,
        version = null,
        installPath = null,
        detail = "No Linux environment is installed. Terminal setup arrives in Part 2."
    )

    override fun installOrRepair(
        distroId: String?,
        onProgress: (InstallProgress) -> Unit
    ): Flow<InstallEvent> = flow {
        emit(
            InstallEvent.Failed(
                "Terminal environment setup is not available in Part 1. " +
                    "Local file, editor, and agent features work without it."
            )
        )
    }

    override suspend fun createSession(workspace: ExecutionWorkspace): TerminalSession =
        throw UnsupportedOperationException("Terminal sessions are not available in Part 1.")

    override fun sendInput(sessionId: String, input: ByteArray) = Unit

    override fun observeOutput(sessionId: String): Flow<TerminalOutput> = flow { }

    override suspend fun requestStop(sessionId: String) = Unit

    override suspend fun closeSession(sessionId: String) = Unit

    override suspend fun uninstallEnvironment(): Result<Unit> =
        Result.failure(UnsupportedOperationException("No environment is installed."))
}
