package com.aliedu.kern.agent.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aliedu.kern.feature.agent.AgentSessionStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Holds the process in the foreground for as long as an agent turn is running.
 *
 * Without it Android freezes and eventually reclaims a backgrounded process, which is why a task
 * used to stop when the app was minimised or another screen was opened. The turn itself now runs in
 * [AgentSessionStore]'s application scope; this service is what keeps that scope alive.
 */
@AndroidEntryPoint
class AgentWorkService : Service() {

    @Inject lateinit var sessionStore: AgentSessionStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AgentWorkNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            sessionStore.turnJob?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        // A startForegroundService() start must reach startForeground() promptly or the system
        // kills the app, so this runs before anything else.
        startForeground(
            AgentWorkNotifications.NOTIFICATION_ID,
            AgentWorkNotifications.build(this, intent?.getStringExtra(AgentWorkNotifications.EXTRA_WORKSPACE))
        )
        // The turn cannot outlive the process, so there is nothing to resume after a restart.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // A destroyed foreground service usually takes its notification with it, but not always
        // when it was stopped from the outside: clear it explicitly so a finished turn never leaves
        // a permanent "Agent working" entry in the shade.
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching {
            NotificationManagerCompat.from(this)
                .cancel(AgentWorkNotifications.NOTIFICATION_ID)
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = AgentWorkNotifications.ACTION_STOP

        fun start(context: Context, workspaceName: String?) {
            val intent = Intent(context, AgentWorkService::class.java)
                .putExtra(AgentWorkNotifications.EXTRA_WORKSPACE, workspaceName)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentWorkService::class.java)) }
        }
    }
}
