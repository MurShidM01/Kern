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
 * The ongoing "the agent is working" notification. It is the visible half of the foreground service
 * that keeps a turn alive while the user is on another screen or in another app.
 */
object AgentWorkNotifications {

    const val CHANNEL_ID = "kern_agent_work"
    const val NOTIFICATION_ID = 0x4B455242 // "KERB"

    const val ACTION_STOP = "com.aliedu.kern.agent.action.STOP_AGENT"
    const val EXTRA_WORKSPACE = "com.aliedu.kern.agent.extra.WORKSPACE_NAME"

    private const val REQUEST_CONTENT = 21
    private const val REQUEST_STOP = 22

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.agent_work_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.agent_work_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, workspaceName: String?): Notification {
        val title = if (workspaceName.isNullOrBlank()) {
            context.getString(R.string.agent_work_title_generic)
        } else {
            context.getString(R.string.agent_work_title, workspaceName)
        }
        val stop = PendingIntent.getService(
            context,
            REQUEST_STOP,
            Intent(context, AgentWorkService::class.java).setAction(AgentWorkService.ACTION_STOP),
            FLAGS
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kern_terminal)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.agent_work_body))
            .setContentIntent(contentPendingIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, context.getString(R.string.agent_work_stop), stop)
            .build()
    }

    private fun contentPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CONTENT,
        Intent(context, MainActivity::class.java)
            .putExtra(AgentTurnNotifications.EXTRA_OPEN_AGENT, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        FLAGS
    )
}
