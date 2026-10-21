package com.aliedu.kern.agent.terminal

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.aliedu.kern.core.terminal.api.TerminalSession
import com.aliedu.kern.core.terminal.implementation.runtime.TerminalSessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps live terminal sessions alive in the background and mirrors their count in an ongoing
 * notification, the way Termux does. Started when the first session appears (see
 * [TerminalSessionNotifier]) and stops itself as soon as the last one closes.
 *
 * The notification's actions are *Exit* (close every session) and *Acquire/Release wakelock* (hold a
 * partial wakelock so long-running work is not suspended when the screen turns off).
 */
@AndroidEntryPoint
class TerminalSessionService : Service() {

    @Inject lateinit var sessionManager: TerminalSessionManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TerminalNotifications.ensureChannel(this)
        scope.launch {
            sessionManager.liveSessions.collect { sessions -> publish(sessions) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TerminalNotifications.ACTION_EXIT -> {
                sessionManager.shutdownAll()
                stopSessions()
                return START_NOT_STICKY
            }

            TerminalNotifications.ACTION_TOGGLE_WAKELOCK -> toggleWakeLock()
        }
        // Promote/refresh immediately: a `startForegroundService` start must reach `startForeground`
        // promptly, and the collector may not have emitted yet.
        publish(sessionManager.liveSessions.value)
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    /** Show/refresh the notification, or shut the service down when there is nothing left to hold. */
    private fun publish(sessions: List<TerminalSession>) {
        if (sessions.isEmpty()) {
            stopSessions()
            return
        }
        startForeground(
            TerminalNotifications.NOTIFICATION_ID,
            TerminalNotifications.build(
                this,
                sessions.map { it.displayName },
                isWakelockHeld()
            )
        )
    }

    private fun stopSessions() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun isWakelockHeld(): Boolean = wakeLock?.isHeld == true

    private fun toggleWakeLock() {
        if (isWakelockHeld()) {
            releaseWakeLock()
        } else {
            val power = getSystemService(PowerManager::class.java) ?: return
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        // Re-render so the action label flips between Acquire/Release.
        publish(sessionManager.liveSessions.value)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wakeLock = null
    }

    private companion object {
        const val WAKELOCK_TAG = "Kern::TerminalSession"
    }
}
