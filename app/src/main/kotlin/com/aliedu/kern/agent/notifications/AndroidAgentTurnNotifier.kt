package com.aliedu.kern.agent.notifications

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.aliedu.kern.feature.agent.AgentTurnNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts [AgentTurnNotifications] when a turn finishes.
 *
 * Suppressed while the app is in the foreground: the transcript is already on screen, and a
 * notification for something the user is watching is noise. The check is the process lifecycle, so
 * it holds across every screen — not just the agent tab.
 */
@Singleton
class AndroidAgentTurnNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) : AgentTurnNotifier {

    override fun onTurnStarted(workspaceName: String?) {
        AgentWorkService.start(context, workspaceName)
    }

    override fun onTurnStopped() {
        AgentWorkService.stop(context)
    }

    override fun onTurnFinished(workspaceName: String?, reply: String) {
        AgentWorkService.stop(context)
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        // Nothing to say when the turn produced no text (a cancelled or tool-only turn).
        if (reply.isBlank()) return
        AgentTurnNotifications.ensureChannel(context)
        val notification = AgentTurnNotifications.build(context, workspaceName, reply)
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(AgentTurnNotifications.NOTIFICATION_ID, notification)
        }
    }
}
