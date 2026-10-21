package com.aliedu.kern.agent.notifications

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
 * The "task finished" notification. Unlike the terminal's ongoing session notification this one is
 * a normal (dismissible) entry: it exists to tell the user the agent is done when they are not
 * looking at the screen.
 */
object AgentTurnNotifications {

    const val CHANNEL_ID = "kern_agent_turns"
    const val NOTIFICATION_ID = 0x4B455241 // "KERA"

    /** Extra on the activity intent so a notification tap lands on the agent screen. */
    const val EXTRA_OPEN_AGENT = "com.aliedu.kern.agent.extra.OPEN_AGENT"

    private const val REQUEST_CONTENT = 20
    private const val BODY_LIMIT = 180

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.agent_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.agent_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, workspaceName: String?, reply: String): Notification {
        val title = if (workspaceName.isNullOrBlank()) {
            context.getString(R.string.agent_turn_done_title_generic)
        } else {
            context.getString(R.string.agent_turn_done_title, workspaceName)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kern_terminal)
            .setContentTitle(title)
            .setContentText(body(reply))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body(reply)))
            .setContentIntent(contentPendingIntent(context))
            .setAutoCancel(true)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    /** One line of the reply: newlines and markdown noise make a poor notification body. */
    private fun body(reply: String): String {
        val collapsed = reply
            .lineSequence()
            .map { it.trim().trimStart('#', '*', '-', '>', ' ') }
            .filter { it.isNotEmpty() && !it.startsWith("```") }
            .joinToString(" ")
            .trim()
        return when {
            collapsed.isEmpty() -> "Tap to review the result."
            collapsed.length <= BODY_LIMIT -> collapsed
            else -> collapsed.take(BODY_LIMIT).trimEnd() + "…"
        }
    }

    private fun contentPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_AGENT, true)
        }
        return PendingIntent.getActivity(context, REQUEST_CONTENT, intent, FLAGS)
    }
}
