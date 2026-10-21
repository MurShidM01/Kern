package com.aliedu.kern.agent.terminal

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.aliedu.kern.core.terminal.implementation.runtime.TerminalSessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings [TerminalSessionService] up the moment the first shell appears. The service takes over from
 * there (it watches the same flow and stops itself when the last session closes), so this only has to
 * decide *when to start*.
 *
 * Started from [com.aliedu.kern.agent.KernApplication] so it works no matter which entry point opened
 * the session — the terminal UI, the package manager, or the agent's `run_command`.
 */
@Singleton
class TerminalSessionNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: TerminalSessionManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var serviceRequested = false

    fun start() {
        scope.launch {
            sessionManager.liveSessions.collect { sessions ->
                if (sessions.isEmpty()) {
                    serviceRequested = false
                } else if (!serviceRequested) {
                    serviceRequested = requestService()
                }
            }
        }
    }

    private fun requestService(): Boolean = runCatching {
        ContextCompat.startForegroundService(
            context,
            Intent(context, TerminalSessionService::class.java)
        )
    }.isSuccess
}
