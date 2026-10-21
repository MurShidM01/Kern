package com.aliedu.kern.agent

import android.app.Application
import com.aliedu.kern.agent.terminal.TerminalSessionNotifier
import com.aliedu.kern.core.common.logging.KernLog
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class KernApplication : Application() {

    @Inject lateinit var terminalSessionNotifier: TerminalSessionNotifier

    override fun onCreate() {
        super.onCreate()
        // Verbose logging is gated to debug builds only; nothing logs secrets regardless (PRD logging policy).
        KernLog.enabled = BuildConfig.DEBUG
        // Watches for terminal sessions so the ongoing session notification appears the moment the
        // first shell starts, wherever it was started from (terminal UI, package manager, agent).
        terminalSessionNotifier.start()
    }
}
