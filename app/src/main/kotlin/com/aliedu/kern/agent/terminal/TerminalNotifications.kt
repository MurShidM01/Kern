package com.aliedu.kern.agent.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.aliedu.kern.agent.MainActivity
import com.aliedu.kern.agent.R

/**
 * Builds the terminal's ongoing notification — the Kern analogue of Termux's session notification:
 * a low-priority, silent, ongoing entry that shows how many shells are alive and offers *Exit* and
 * *Acquire wakelock* actions when expanded.
 */
object TerminalNotifications {

    const val CHANNEL_ID = "kern_terminal_sessions"
    const val NOTIFICATION_ID = 0x4B45524E // "KERN"

    const val ACTION_EXIT = "com.aliedu.kern.agent.terminal.action.EXIT"
    const val ACTION_TOGGLE_WAKELOCK = "com.aliedu.kern.agent.terminal.action.TOGGLE_WAKELOCK"

    /** Extra on the activity intent so a notification tap lands on the terminal. */
    const val EXTRA_OPEN_TERMINAL = "com.aliedu.kern.agent.terminal.extra.OPEN_TERMINAL"

    private const val REQUEST_CONTENT = 10
    private const val REQUEST_EXIT = 11
    private const val REQUEST_WAKELOCK = 12

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.terminal_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.terminal_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, sessionNames: List<String>, wakelockHeld: Boolean): Notification {
        val count = sessionNames.size
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kern_terminal)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(
                context.resources.getQuantityString(
                    R.plurals.terminal_session_count,
                    count,
                    count
                )
            )
            .setContentIntent(contentPendingIntent(context))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_kern_terminal,
                context.getString(R.string.terminal_action_exit),
                actionPendingIntent(context, ACTION_EXIT, REQUEST_EXIT)
            )
            .addAction(
                R.drawable.ic_kern_terminal,
                context.getString(
                    if (wakelockHeld) R.string.terminal_action_release_wakelock
                    else R.string.terminal_action_wakelock
                ),
                actionPendingIntent(context, ACTION_TOGGLE_WAKELOCK, REQUEST_WAKELOCK)
            )
            .build()
    }

    private fun contentPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_TERMINAL, true)
        }
        return PendingIntent.getActivity(context, REQUEST_CONTENT, intent, FLAGS)
    }

    private fun actionPendingIntent(context: Context, action: String, request: Int): PendingIntent {
        val intent = Intent(context, TerminalSessionService::class.java).setAction(action)
        return PendingIntent.getService(context, request, intent, FLAGS)
    }
}
