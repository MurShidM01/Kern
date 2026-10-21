package com.aliedu.kern.feature.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.aliedu.kern.core.designsystem.components.KernClickableSurface
import com.aliedu.kern.core.designsystem.components.KernDivider
import com.aliedu.kern.core.designsystem.components.KernIconButton
import com.aliedu.kern.core.designsystem.components.KernListRow
import com.aliedu.kern.core.designsystem.components.KernPullToRefresh
import com.aliedu.kern.core.designsystem.components.KernSidePanel
import com.aliedu.kern.core.designsystem.components.KernSidePanelHeader
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.core.designsystem.theme.KernRadius
import com.aliedu.kern.core.designsystem.theme.KernSpacing
import com.aliedu.kern.core.terminal.api.EnvironmentInfo
import com.aliedu.kern.core.terminal.api.EnvironmentState
import com.aliedu.kern.core.terminal.api.SessionKind

/**
 * The session manager, opened from the terminal's AppBar menu. Lets the user create, switch, rename,
 * and close sessions, jump to the Linux environment setup, and show/hide the soft keyboard — Termux's
 * drawer, in Kern's flat style. The panel rises above the keyboard so every row stays reachable.
 */
@Composable
fun TerminalSessionSidebar(
    visible: Boolean,
    sessions: List<TerminalSessionUi>,
    activeId: String?,
    environment: EnvironmentInfo?,
    imeVisible: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    onSelect: (String) -> Unit,
    onNewSession: () -> Unit,
    onRename: (TerminalSessionUi) -> Unit,
    onCloseSession: (TerminalSessionUi) -> Unit,
    onOpenEnvironment: () -> Unit,
    onOpenPackages: () -> Unit,
    onToggleKeyboard: () -> Unit
) {
    KernSidePanel(visible = visible, onDismiss = onClose, imeAware = true) {
        KernSidePanelHeader(
            title = "Sessions",
            subtitle = "${sessions.size} open",
            icon = KernIcons.Terminal,
            onClose = onClose
        )
        KernDivider()

        KernListRow(
            title = "New session",
            leading = KernIcons.AddSession,
            leadingTint = MaterialTheme.colorScheme.primary,
            onClick = onNewSession
        )
        KernDivider()

        KernPullToRefresh(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        active = session.id == activeId,
                        onSelect = { onSelect(session.id) },
                        onRename = { onRename(session) },
                        onClose = { onCloseSession(session) }
                    )
                }
            }
        }

        KernDivider()
        KernListRow(
            title = "Packages",
            subtitle = "Install and remove packages in the guest",
            leading = KernIcons.Package,
            leadingTint = MaterialTheme.colorScheme.primary,
            onClick = onOpenPackages
        )
        KernDivider()
        KernListRow(
            title = if (imeVisible) "Hide keyboard" else "Show keyboard",
            subtitle = if (imeVisible) "The soft keyboard is open" else "Open the soft keyboard to type",
            leading = if (imeVisible) KernIcons.KeyboardHide else KernIcons.Keyboard,
            leadingTint = MaterialTheme.colorScheme.primary,
            onClick = onToggleKeyboard
        )
        KernDivider()
        KernListRow(
            title = "Linux environment",
            subtitle = environmentLabel(environment),
            leading = KernIcons.Terminal,
            onClick = onOpenEnvironment
        )
    }
}

@Composable
private fun SessionRow(
    session: TerminalSessionUi,
    active: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit
) {
    KernClickableSurface(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KernSpacing.sm, vertical = KernSpacing.xxs),
        shape = KernRadius.smShape,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        bordered = active
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = KernSpacing.sm, end = KernSpacing.xxs, top = KernSpacing.xxs, bottom = KernSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.xxs)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = sessionSubtitle(session),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            KernIconButton(
                icon = KernIcons.Rename,
                contentDescription = "Rename session",
                onClick = onRename
            )
            KernIconButton(
                icon = KernIcons.Close,
                contentDescription = "Close session",
                onClick = onClose
            )
        }
    }
}

private fun sessionSubtitle(session: TerminalSessionUi): String {
    val kind = if (session.kind == SessionKind.LINUX_PROOT) "Linux" else "Device shell"
    return if (session.alive) kind else "$kind · ended${session.exitCode?.let { " ($it)" } ?: ""}"
}

private fun environmentLabel(environment: EnvironmentInfo?): String = when (environment?.state) {
    EnvironmentState.READY -> if (environment.runtimeUsable) {
        "${environment.distroName ?: "Linux"} ${environment.version ?: ""}".trim()
    } else {
        "${environment.distroName ?: "Linux"} unavailable — using device shell"
    }
    EnvironmentState.INSTALLING -> "Installing…"
    EnvironmentState.FAILED -> "Setup failed — tap to retry"
    EnvironmentState.UNSUPPORTED_ABI -> "Unsupported on this device"
    else -> "Set up a Linux environment"
}
