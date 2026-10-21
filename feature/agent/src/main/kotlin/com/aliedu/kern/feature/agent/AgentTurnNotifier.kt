package com.aliedu.kern.feature.agent

/**
 * Told when an agent turn starts and finishes, so the app can keep the process alive while the
 * agent works and raise a notification while the user is elsewhere.
 *
 * Kept as an interface in the feature module so the ViewModel does not depend on Android
 * notification or service APIs; the app module provides the implementation.
 */
interface AgentTurnNotifier {
    /**
     * The turn is about to run. The host promotes the app to the foreground so the provider stream
     * is not suspended when the screen is off or the user switches apps mid-task.
     */
    fun onTurnStarted(workspaceName: String?) {}

    /** The turn ended, for any reason. The host drops its foreground promotion. */
    fun onTurnStopped() {}

    /**
     * @param workspaceName the workspace the turn ran in, for the notification title.
     * @param reply the assistant's text, used as the notification body.
     */
    fun onTurnFinished(workspaceName: String?, reply: String)

    /** Used by tests and previews. */
    object None : AgentTurnNotifier {
        override fun onTurnFinished(workspaceName: String?, reply: String) = Unit
    }
}
