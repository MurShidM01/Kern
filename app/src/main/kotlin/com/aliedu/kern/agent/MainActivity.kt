package com.aliedu.kern.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliedu.kern.agent.notifications.AgentTurnNotifications
import com.aliedu.kern.agent.terminal.TerminalNotifications
import com.aliedu.kern.core.designsystem.theme.KernTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

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
}
