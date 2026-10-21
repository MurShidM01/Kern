package com.aliedu.kern.feature.preview

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliedu.kern.core.designsystem.components.EmptyState
import com.aliedu.kern.core.designsystem.components.KernButton
import com.aliedu.kern.core.designsystem.components.KernDropdownItem
import com.aliedu.kern.core.designsystem.components.KernDropdownMenu
import com.aliedu.kern.core.designsystem.components.KernIconButton
import com.aliedu.kern.core.designsystem.components.KernScaffold
import com.aliedu.kern.core.designsystem.components.KernSurface
import com.aliedu.kern.core.designsystem.components.KernTopBar
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.core.designsystem.theme.KernSpacing
import com.aliedu.kern.core.tools.devserver.DevServerState

/**
 * Full-screen preview of the dev server the agent started (PRD §4.3 bullet 8).
 *
 * This is the same WebView the agent drives: `preview_open`, `preview_eval`, `preview_console` and
 * `preview_screenshot` all act on this page, so "check the preview" means looking at what the user
 * sees rather than starting another process.
 */
@Composable
fun PreviewScreen(
    onBack: () -> Unit,
    viewModel: PreviewViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    // Nothing is selected when the screen opens from the app bar; pick the newest running server.
    LaunchedEffect(Unit) { viewModel.onScreenOpened() }

    val title = state.title?.takeIf { it.isNotBlank() } ?: "Preview"

    KernScaffold(
        topBar = {
            KernTopBar(
                title = title,
                subtitle = state.url?.removePrefix("http://"),
                navigationIcon = {
                    KernIconButton(
                        icon = KernIcons.Back,
                        contentDescription = "Back",
                        onClick = onBack
                    )
                },
                actions = {
                    KernIconButton(
                        icon = KernIcons.ArrowLeft,
                        contentDescription = "Back in page",
                        onClick = viewModel::goBack,
                        enabled = state.canGoBack
                    )
                    KernIconButton(
                        icon = KernIcons.ArrowRight,
                        contentDescription = "Forward in page",
                        onClick = viewModel::goForward,
                        enabled = state.canGoForward
                    )
                    KernIconButton(
                        icon = KernIcons.Refresh,
                        contentDescription = "Reload",
                        onClick = viewModel::reload,
                        enabled = state.url != null
                    )
                    ConsoleButton(
                        errorCount = state.errorCount,
                        open = state.consoleOpen,
                        onClick = viewModel::toggleConsole
                    )
                    KernIconButton(
                        icon = KernIcons.More,
                        contentDescription = "Preview options",
                        onClick = { menuOpen = true }
                    )
                    KernDropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        alignment = Alignment.TopEnd
                    ) {
                        state.servers.forEach { server ->
                            KernDropdownItem(
                                title = server.command,
                                subtitle = serverSubtitle(server.port, server.state),
                                leading = KernIcons.Terminal,
                                selected = server.id == state.activeServerId,
                                onClick = {
                                    menuOpen = false
                                    viewModel.selectServer(server)
                                }
                            )
                        }
                        if (state.servers.isNotEmpty()) {
                            KernDropdownItem(
                                title = "Stop server",
                                leading = KernIcons.Stop,
                                onClick = {
                                    menuOpen = false
                                    state.activeServerId?.let(viewModel::stopServer)
                                }
                            )
                        }
                        KernDropdownItem(
                            title = "Open in browser",
                            leading = KernIcons.Globe,
                            onClick = {
                                menuOpen = false
                                state.url?.let { url ->
                                    // A loopback URL is reachable by the device's own browser too.
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }
                                }
                            }
                        )
                        KernDropdownItem(
                            title = "Clear console",
                            leading = KernIcons.Clear,
                            onClick = {
                                menuOpen = false
                                viewModel.clearConsole()
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (!state.hasSomethingToShow) {
                EmptyState(
                    icon = KernIcons.Globe,
                    title = "No server running",
                    message = "Ask the agent to start one — for example “serve the project on port 8000” — " +
                        "and it will show up here with its console."
                )
                return@Box
            }

            PreviewWebViewContainer(
                controller = viewModel.controller,
                url = state.url,
                modifier = Modifier.fillMaxSize()
            )

            if (state.loading) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            state.error?.let { message ->
                ErrorBanner(
                    message = message,
                    onRetry = viewModel::reload,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(KernSpacing.md)
                )
            }

            if (state.consoleOpen) {
                ConsoleDrawer(
                    entries = state.visibleConsole,
                    levels = state.levels,
                    onToggleLevel = viewModel::toggleLevel,
                    onClear = viewModel::clearConsole,
                    onClose = viewModel::toggleConsole,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

/** The console toggle, badged with the error count so a broken page is visible at a glance. */
@Composable
private fun ConsoleButton(errorCount: Int, open: Boolean, onClick: () -> Unit) {
    val tint = when {
        errorCount > 0 -> MaterialTheme.colorScheme.error
        open -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box {
        KernIconButton(
            icon = KernIcons.Debug,
            contentDescription = if (errorCount > 0) "Console, $errorCount errors" else "Console",
            onClick = onClick,
            tint = tint
        )
        if (errorCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = KernSpacing.xs, end = KernSpacing.xxs)
                    .size(14.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (errorCount > 9) "9+" else errorCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onError
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    KernSurface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(KernSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "The page did not load",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            KernButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

private fun serverSubtitle(port: Int?, state: DevServerState): String = when (state) {
    DevServerState.RUNNING -> "running on port ${port ?: "?"}"
    DevServerState.STARTING -> "starting…"
    DevServerState.STOPPED -> "stopped"
    DevServerState.FAILED -> "failed"
}
