package com.aliedu.kern.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliedu.kern.agent.notifications.AgentTurnNotifications
import com.aliedu.kern.agent.terminal.TerminalNotifications
import com.aliedu.kern.core.designsystem.theme.KernTheme
import com.aliedu.kern.feature.settings.UpdateRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** The same instance Settings uses, so a check started on the splash is not thrown away. */
    @Inject
    lateinit var updateRepository: UpdateRepository

    /**
     * Set when the activity is opened from the terminal's session notification, so the UI can jump
     * straight to the terminal instead of Home.
     */
    private val openTerminal = MutableStateFlow(false)

    /** Set when the activity is opened from the agent's "task finished" notification. */
    private val openAgent = MutableStateFlow(false)

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        requestNotificationPermission()
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val shouldOpenTerminal by openTerminal.collectAsStateWithLifecycle()
            val shouldOpenAgent by openAgent.collectAsStateWithLifecycle()
            // The splash is drawn over the app, not instead of it: the first screen composes and
            // loads underneath, so dismissing the splash never reveals an empty frame.
            //
            // It also stays up until the app actually knows which screen to show. Dismissing on a
            // fixed timer used to expose the shell's "Loading Kern…" placeholder for a frame or two
            // — a second splash with a spinner — before onboarding or Home replaced it.
            var showSplash by remember { mutableStateOf(!splashShown) }
            LaunchedEffect(Unit) {
                if (!showSplash) return@LaunchedEffect
                // The splash is where the release check runs: there is nothing else to do during
                // the cold start, and by the time Settings opens the answer is already there.
                if (!startupCheckStarted) {
                    startupCheckStarted = true
                    updateRepository.checkInBackground(BuildConfig.VERSION_NAME)
                }
                val startedAt = SystemClock.elapsedRealtime()
                while (true) {
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    val heldLongEnough = elapsed >= SPLASH_MIN_MILLIS
                    // Offline, the request fails fast and the minimum hold carries the splash. On a
                    // slow network we wait for the answer, but never past the cap.
                    val checkSettled = !updateRepository.state.value.checking
                    if (heldLongEnough && (checkSettled || elapsed >= SPLASH_MAX_MILLIS)) break
                    delay(SPLASH_POLL_MILLIS)
                }
                while (!state.ready) delay(SPLASH_POLL_MILLIS)
                splashShown = true
                showSplash = false
            }
            Box(modifier = Modifier.fillMaxSize()) {
                KernTheme(
                    themeMode = state.themeMode,
                    dynamicColor = state.dynamicColor,
                    palette = state.themePalette
                ) {
                    KernApp(
                        mainViewModel = viewModel,
                        openTerminal = shouldOpenTerminal,
                        onTerminalOpened = { openTerminal.value = false },
                        openAgent = shouldOpenAgent,
                        onAgentOpened = { openAgent.value = false }
                    )
                }
                AnimatedVisibility(
                    visible = showSplash,
                    exit = fadeOut(tween(durationMillis = SPLASH_FADE_MILLIS))
                ) {
                    KernTheme(
                        themeMode = state.themeMode,
                        dynamicColor = state.dynamicColor,
                        palette = state.themePalette
                    ) {
                        KernSplash()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(TerminalNotifications.EXTRA_OPEN_TERMINAL, false) == true) {
            openTerminal.value = true
        }
        if (intent?.getBooleanExtra(AgentTurnNotifications.EXTRA_OPEN_AGENT, false) == true) {
            openAgent.value = true
        }
    }

    /**
     * Asks for the notification permission the first time the app runs on Android 13+. Without it
     * the agent's "finished" notification (and the terminal's session notification) cannot be shown.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        /**
         * False until the splash has been seen once in this process, so returning to the activity
         * (rotation, a notification tap on a running app) does not replay it.
         */
        var splashShown = false

        /** False until the cold-start release check has been started in this process. */
        var startupCheckStarted = false

        /** The splash is held for this long even when the release check returns instantly or fails. */
        const val SPLASH_MIN_MILLIS = 5_000L

        /** Upper bound on waiting for the release check, so a slow network cannot park the splash. */
        const val SPLASH_MAX_MILLIS = 8_000L
        const val SPLASH_POLL_MILLIS = 50L
        const val SPLASH_FADE_MILLIS = 240
    }
}
