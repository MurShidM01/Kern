package com.aliedu.kern.feature.terminal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliedu.kern.core.designsystem.components.EmptyState
import com.aliedu.kern.core.designsystem.components.KernConfirmDialog
import com.aliedu.kern.core.designsystem.components.KernIconButton
import com.aliedu.kern.core.designsystem.components.KernInfoNote
import com.aliedu.kern.core.designsystem.components.KernScaffold
import com.aliedu.kern.core.designsystem.components.KernStatusCard
import com.aliedu.kern.core.designsystem.components.KernTextButton
import com.aliedu.kern.core.designsystem.components.KernTextInputDialog
import com.aliedu.kern.core.designsystem.components.KernTone
import com.aliedu.kern.core.designsystem.components.KernTopBar
import com.aliedu.kern.core.designsystem.components.LoadingState
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.core.designsystem.theme.KernRadius
import com.aliedu.kern.core.designsystem.theme.KernSizes
import com.aliedu.kern.core.designsystem.theme.KernSpacing
import com.aliedu.kern.core.designsystem.theme.LocalKernSemanticColors
import com.aliedu.kern.core.terminal.api.EnvironmentInfo
import com.aliedu.kern.core.terminal.api.EnvironmentState
import com.aliedu.kern.core.terminal.api.SessionKind

/**
 * The terminal: a full-screen session (no bottom navigation) with the AppBar carrying a back arrow and
 * a menu that opens the session sidebar. Input is inline like Termux — tapping the screen focuses the
 * IME, and the shortcut keys dock directly above the keyboard. Colours come from the user's chosen
 * terminal theme, which follows the app colour scheme by default.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    onBack: () -> Unit,
    onOpenEnvironmentSetup: () -> Unit,
    onOpenPackages: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val inputController = rememberTerminalInputController()
    val imeVisible = WindowInsets.isImeVisible
    val appearance = terminalAppearance(state.terminalTheme)

    DisposableEffect(Unit) {
        onDispose { inputController.dismiss() }
    }

    // Re-sync the environment and sessions whenever the screen comes back to the foreground, so an
    // Linux install (or removal) performed elsewhere is reflected here without a manual restart.
    // The ViewModel also stops rebuilding screen snapshots while the screen is hidden — that work
    // runs on the main thread and would otherwise throttle the shared shell.
    LifecycleResumeEffect(Unit) {
        viewModel.setScreenVisible(true)
        viewModel.syncEnvironment()
        onPauseOrDispose { viewModel.setScreenVisible(false) }
    }

    // The session notification is a foreground-service notification, so on Android 13+ it is only
    // visible once the user grants POST_NOTIFICATIONS. Ask the first time the terminal is opened.
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var renameTarget by remember { mutableStateOf<TerminalSessionUi?>(null) }
    var closeTarget by remember { mutableStateOf<TerminalSessionUi?>(null) }

    val active = state.active

    KernScaffold(
        topBar = {
            KernTopBar(
                title = "Terminal",
                subtitle = state.workspaceName,
                navigationIcon = {
                    KernIconButton(
                        icon = KernIcons.Back,
                        contentDescription = "Back",
                        onClick = {
                            inputController.dismiss()
                            onBack()
                        }
                    )
                },
                actions = {
                    EnvironmentChip(state.environment, active?.kind)
                    KernIconButton(
                        icon = KernIcons.Package,
                        contentDescription = "Package manager",
                        onClick = {
                            inputController.dismiss()
                            onOpenPackages()
                        }
                    )
                    KernIconButton(
                        icon = KernIcons.AddSession,
                        contentDescription = "New session",
                        onClick = viewModel::createSession
                    )
                    KernIconButton(
                        icon = KernIcons.Menu,
                        contentDescription = "Sessions",
                        onClick = viewModel::toggleSidebar
                    )
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.loading -> LoadingState(label = "Starting shell…")

                state.sessions.isEmpty() -> EmptyState(
                    title = "No terminal sessions",
                    message = "Open a session to run shell commands in your workspace. " +
                        "Sessions run on the built-in Android shell until a Linux environment is set up.",
                    icon = KernIcons.Terminal,
                    action = {
                        KernTextButton(onClick = viewModel::createSession) { Text("New session") }
                    }
                )

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                    ) {
                        val environment = state.environment
                        val runtimeUnusable = environment?.state == EnvironmentState.READY &&
                            !environment.runtimeUsable
                        if (environment?.state != EnvironmentState.READY || runtimeUnusable) {
                            EnvironmentBanner(
                                environment = environment,
                                onOpen = onOpenEnvironmentSetup
                            )
                        }
                        if (!active?.alive.orFalse()) {
                            SessionEndedBanner(active?.exitCode)
                        }
                        state.message?.let { message ->
                            MessageBanner(message = message, onDismiss = viewModel::dismissMessage)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            active?.let { session ->
                                TerminalRenderer(
                                    snapshot = session.snapshot,
                                    fontSizeSp = state.fontSizeSp,
                                    appearance = appearance,
                                    modifier = Modifier.fillMaxSize(),
                                    selection = state.selection,
                                    onResize = viewModel::onResize,
                                    onScroll = viewModel::scrollBy,
                                    onTap = {
                                        viewModel.clearSelection()
                                        inputController.focus()
                                    },
                                    onSelectionStart = viewModel::beginSelection,
                                    onSelectionExtend = viewModel::extendSelection
                                )
                            }
                        }

                        if (state.selection != null) {
                            SelectionActionBar(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = KernSpacing.screenH, vertical = KernSpacing.xs),
                                onCopy = {
                                    val text = viewModel.selectedText()
                                    if (text.isNotEmpty()) clipboard.setText(AnnotatedString(text))
                                },
                                onPaste = {
                                    clipboard.getText()?.text?.let { viewModel.sendInput(it) }
                                    viewModel.clearSelection()
                                },
                                onSelectAll = viewModel::selectAll,
                                onClear = viewModel::clearSelection
                            )
                        }

                        TerminalKeyRow(
                            ctrlArmed = state.ctrlArmed,
                            altArmed = state.altArmed,
                            onKey = viewModel::sendKey,
                            onToggleCtrl = viewModel::toggleCtrl,
                            onToggleAlt = viewModel::toggleAlt,
                            onToggleKeyboard = { inputController.toggleKeyboard(imeVisible) }
                        )
                    }

                    // Invisible input surface that owns the IME and forwards keys to the PTY.
                    TerminalInputHost(
                        controller = inputController,
                        onText = viewModel::sendInput,
                        onSequence = viewModel::sendSequence,
                        applicationCursorKeys = active?.snapshot?.applicationCursorKeys == true,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                            .size(1.dp)
                    )
                }
            }

            TerminalSessionSidebar(
                visible = state.sidebarOpen,
                sessions = state.sessions,
                activeId = state.activeSessionId,
                environment = state.environment,
                imeVisible = imeVisible,
                refreshing = state.syncing,
                onRefresh = viewModel::syncEnvironment,
                onClose = viewModel::closeSidebar,
                onSelect = viewModel::selectSession,
                onNewSession = viewModel::createSession,
                onRename = { renameTarget = it },
                onCloseSession = { closeTarget = it },
                onOpenEnvironment = {
                    viewModel.closeSidebar()
                    onOpenEnvironmentSetup()
                },
                onOpenPackages = {
                    viewModel.closeSidebar()
                    inputController.dismiss()
                    onOpenPackages()
                },
                onToggleKeyboard = {
                    if (imeVisible) inputController.dismiss() else inputController.focus()
                }
            )
        }
    }

    renameTarget?.let { target ->
        KernTextInputDialog(
            title = "Rename session",
            initialValue = target.displayName,
            label = "Session name",
            placeholder = "Shell",
            icon = KernIcons.Rename,
            confirmLabel = "Save",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                viewModel.renameSession(target.id, name)
                renameTarget = null
            }
        )
    }

    closeTarget?.let { target ->
        KernConfirmDialog(
            title = "Close session?",
            message = "\"${target.displayName}\" and its running processes will be terminated.",
            confirmLabel = "Close",
            onConfirm = {
                viewModel.closeSession(target.id)
                closeTarget = null
            },
            onDismiss = { closeTarget = null }
        )
    }
}

@Composable
private fun SelectionActionBar(
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = KernRadius.mdShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KernSpacing.sm, vertical = KernSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ActionChip(KernIcons.Copy, "Copy", onCopy)
            ActionChip(KernIcons.Paste, "Paste", onPaste)
            ActionChip(KernIcons.SelectAll, "Select all", onSelectAll)
            ActionChip(KernIcons.Close, "Clear selection", onClear)
        }
    }
}

@Composable
private fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    KernIconButton(
        icon = icon,
        contentDescription = description,
        onClick = onClick,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun EnvironmentBanner(environment: EnvironmentInfo?, onOpen: () -> Unit) {
    // The environment can be installed yet unable to run (a kernel that denies the proot sandbox),
    // in which case the terminal silently uses the built-in shell — this is where it says so.
    val installed = environment?.state == EnvironmentState.READY
    KernStatusCard(
        title = if (installed) (environment?.distroName ?: "Linux") + " unavailable" else "Device shell",
        detail = if (installed) {
            environment?.detail ?: "The Linux environment cannot run on this device."
        } else {
            "Set up a Linux environment for package tools such as apk, apt, git and python."
        },
        tone = if (installed) KernTone.ERROR else KernTone.WARNING,
        icon = KernIcons.Terminal,
        trailing = {
            KernTextButton(onClick = onOpen) { Text(if (installed) "Details" else "Set up") }
        },
        modifier = Modifier.padding(horizontal = KernSpacing.screenH, vertical = KernSpacing.xs)
    )
}

@Composable
private fun EnvironmentChip(environment: EnvironmentInfo?, kind: SessionKind?) {
    val semantic = LocalKernSemanticColors.current
    val ready = environment?.state == EnvironmentState.READY && kind == SessionKind.LINUX_PROOT
    val label = if (ready) environment?.distroName ?: "Linux" else "Device shell"
    val container = if (ready) semantic.successContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (ready) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = KernRadius.pillShape, color = container) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = KernSpacing.sm, vertical = KernSpacing.xxs)
        )
    }
}

@Composable
private fun SessionEndedBanner(exitCode: Int?) {
    KernInfoNote(
        text = "Session ended${exitCode?.let { " (exit $it)" } ?: ""}.",
        tone = KernTone.WARNING,
        modifier = Modifier.padding(horizontal = KernSpacing.screenH, vertical = KernSpacing.xs)
    )
}

@Composable
private fun MessageBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KernSpacing.screenH, vertical = KernSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
    ) {
        Icon(
            imageVector = KernIcons.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(KernSizes.iconSm)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        KernTextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

private fun Boolean?.orFalse(): Boolean = this == true
