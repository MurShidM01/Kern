package com.aliedu.kern.feature.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.aliedu.kern.core.designsystem.components.EmptyState
import com.aliedu.kern.core.designsystem.components.KernButton
import com.aliedu.kern.core.designsystem.components.KernConfirmDialog
import com.aliedu.kern.core.designsystem.components.KernIconBadge
import com.aliedu.kern.core.designsystem.components.KernIconButton
import com.aliedu.kern.core.designsystem.components.KernInfoNote
import com.aliedu.kern.core.designsystem.components.KernPullToRefresh
import com.aliedu.kern.core.designsystem.components.KernRefreshableState
import com.aliedu.kern.core.designsystem.components.KernScaffold
import com.aliedu.kern.core.designsystem.components.KernSectionLabel
import com.aliedu.kern.core.designsystem.components.KernSettingRow
import com.aliedu.kern.core.designsystem.components.KernTextButton
import com.aliedu.kern.core.designsystem.components.KernTextField
import com.aliedu.kern.core.designsystem.components.KernTextInputDialog
import com.aliedu.kern.core.designsystem.components.KernTone
import com.aliedu.kern.core.designsystem.components.KernTopBar
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.core.designsystem.theme.KernElevation
import com.aliedu.kern.core.designsystem.theme.KernRadius
import com.aliedu.kern.core.designsystem.theme.KernSizes
import com.aliedu.kern.core.designsystem.theme.KernSpacing
import com.aliedu.kern.core.model.Session
import com.aliedu.kern.core.model.Workspace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun rememberFolderPicker(onPicked: (Uri, String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }
                .getOrNull() ?: "Workspace"
            onPicked(uri, name)
        }
    }
    return { launcher.launch(null) }
}

/**
 * A folder picker that first offers all-files access. With it, the picked folder is used at its real
 * `/sdcard` path so the Files, Editor, Agent and Terminal all share one tree; without it the folder
 * still works through the SAF-backed mirror. [onRequestStorageAccess] opens the system screen.
 *
 * On Android 10 and older all-files access does not exist, so the prompt is skipped and the picker
 * opens directly: there is no settings screen to send the user to.
 */
@Composable
fun rememberStorageAwareFolderPicker(
    storageAccessGranted: Boolean,
    storageAccessSupported: Boolean = true,
    onRequestStorageAccess: () -> Unit,
    onPicked: (Uri, String) -> Unit
): () -> Unit {
    var showPrompt by remember { mutableStateOf(false) }
    val pickFolder = rememberFolderPicker(onPicked)

    if (showPrompt) {
        KernConfirmDialog(
            title = "All-files access",
            message = "Kern shares one folder between the Files, Editor, Agent and Terminal. " +
                "Granting \"All files access\" lets the terminal open that folder directly at its " +
                "real path. Without it, Kern edits a mirrored copy instead.",
            confirmLabel = "Grant access",
            dismissLabel = "Continue without",
            destructive = false,
            onConfirm = {
                showPrompt = false
                onRequestStorageAccess()
            },
            onDismiss = {
                showPrompt = false
                pickFolder()
            }
        )
    }

    return {
        if (storageAccessGranted || !storageAccessSupported) pickFolder() else showPrompt = true
    }
}

@Composable
fun NameWorkspaceDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    KernTextInputDialog(
        title = "New project",
        message = "The project is created as its own folder, named after the name you type. " +
            "In the terminal it appears at /workspace/<name>, next to your other projects.",
        initialValue = "",
        label = "Project name",
        placeholder = "first_app",
        icon = KernIcons.Folder,
        confirmLabel = "Create",
        onDismiss = onDismiss,
        onConfirm = { onConfirm(it) },
        validate = { if (it.isBlank()) "Enter a workspace name." else null }
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    providerLabel: String?,
    onRequestStorageAccess: () -> Unit,
    onOpenFolder: (Uri, String) -> Unit,
    onCreateWorkspace: (String) -> Unit,
    onOpenWorkspace: (Workspace) -> Unit,
    onNewTask: (Workspace) -> Unit,
    onOpenSession: (Session) -> Unit,
    onConfigureProvider: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTerminal: () -> Unit,
    onDeleteWorkspace: (Workspace) -> Unit,
    onDeleteSession: (Session) -> Unit,
    onClearSessions: () -> Unit,
    onRefresh: () -> Unit
) {
    val pickFolder = rememberStorageAwareFolderPicker(
        storageAccessGranted = state.storageAccessGranted,
        storageAccessSupported = state.storageAccessSupported,
        onRequestStorageAccess = onRequestStorageAccess,
        onPicked = onOpenFolder
    )
    var showCreateDialog by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Workspace?>(null) }
    var pendingDeleteSession by remember { mutableStateOf<Session?>(null) }
    var confirmClearSessions by remember { mutableStateOf(false) }

    KernScaffold(
        topBar = {
            KernTopBar(
                title = "Kern",
                subtitle = "Local-first AI coding agent",
                actions = {
                    ProviderChip(
                        label = providerLabel ?: "No provider",
                        onClick = onConfigureProvider
                    )
                    Spacer(Modifier.width(KernSpacing.xs))
                    KernIconButton(
                        icon = KernIcons.Terminal,
                        contentDescription = "Terminal",
                        onClick = onOpenTerminal
                    )
                    KernIconButton(
                        icon = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        onClick = onOpenSettings
                    )
                }
            )
        }
    ) { padding ->
        KernPullToRefresh(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                KernTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search projects",
                    leadingIcon = KernIcons.Search,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = KernSpacing.screenH, vertical = KernSpacing.sm)
                )

                if (state.workspaces.isEmpty() && state.sessions.isEmpty()) {
                    KernRefreshableState {
                        EmptyState(
                            title = "No projects yet",
                            message = "Create a project, or open a folder you already have.",
                            icon = KernIcons.Folder,
                            action = {
                                KernButton(onClick = { showCreateDialog = true }) {
                                    Icon(
                                        imageVector = KernIcons.AddSession,
                                        contentDescription = null,
                                        modifier = Modifier.size(KernSizes.iconSm)
                                    )
                                    Spacer(Modifier.width(KernSpacing.sm))
                                    Text("Create a project")
                                }
                                KernTextButton(
                                    onClick = pickFolder,
                                    modifier = Modifier.padding(top = KernSpacing.xs)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(KernSizes.iconSm)
                                    )
                                    Spacer(Modifier.width(KernSpacing.xs))
                                    Text("Open an existing folder")
                                }
                            }
                        )
                    }
                    return@Column
                }

                LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = KernSpacing.screenH,
                    end = KernSpacing.screenH,
                    bottom = KernSpacing.lg
                ),
                verticalArrangement = Arrangement.spacedBy(KernSpacing.sm)
            ) {
                item { KernSectionLabel("Quick actions") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)) {
                        QuickActionTile(
                            icon = KernIcons.Folder,
                            label = "Open folder",
                            onClick = pickFolder,
                            modifier = Modifier.weight(1f)
                        )
                        QuickActionTile(
                            icon = KernIcons.AddSession,
                            label = "New project",
                            onClick = { showCreateDialog = true },
                            modifier = Modifier.weight(1f)
                        )
                        QuickActionTile(
                            icon = KernIcons.Agent,
                            label = "New task",
                            enabled = state.activeWorkspace != null,
                            onClick = { state.activeWorkspace?.let(onNewTask) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (providerLabel == null) {
                    item {
                        KernInfoNote(
                            text = "No AI provider yet. Add an OpenAI-compatible endpoint to use the agent.",
                            tone = KernTone.WARNING,
                            modifier = Modifier.padding(top = KernSpacing.xs)
                        )
                    }
                }

                val filtered = state.workspaces.filter {
                    query.isBlank() || it.displayName.contains(query, ignoreCase = true)
                }
                if (filtered.isNotEmpty()) {
                    item { KernSectionLabel("Projects", modifier = Modifier.padding(top = KernSpacing.xs)) }
                    items(filtered, key = { it.id }) { workspace ->
                        WorkspaceCard(
                            workspace = workspace,
                            isActive = workspace.id == state.activeWorkspace?.id,
                            onOpen = { onOpenWorkspace(workspace) },
                            onNewTask = { onNewTask(workspace) },
                            onDelete = { pendingDelete = workspace }
                        )
                    }
                }

                if (state.sessions.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = KernSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            KernSectionLabel("Recent sessions", modifier = Modifier.weight(1f))
                            KernTextButton(onClick = { confirmClearSessions = true }) { Text("Clear all") }
                        }
                    }
                    items(state.sessions.take(10), key = { it.id }) { session ->
                        KernSettingRow(
                            title = session.title,
                            subtitle = "${session.status.name.lowercase()} • ${formatTime(session.lastEventAt)}",
                            icon = KernIcons.History,
                            onClick = { onOpenSession(session) },
                            trailing = {
                                KernIconButton(
                                    icon = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Delete session",
                                    onClick = { pendingDeleteSession = session }
                                )
                            }
                        )
                    }
                }
            }
            }
        }
    }

    if (showCreateDialog) {
        NameWorkspaceDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                showCreateDialog = false
                onCreateWorkspace(name)
            }
        )
    }

    pendingDelete?.let { workspace ->
        KernConfirmDialog(
            title = "Remove workspace?",
            message = "\"${workspace.displayName}\" will be removed from Kern. Your files on disk are " +
                "never deleted — you can add the folder again later.",
            confirmLabel = "Remove",
            onConfirm = {
                onDeleteWorkspace(workspace)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }

    pendingDeleteSession?.let { session ->
        KernConfirmDialog(
            title = "Delete session?",
            message = "\"${session.title}\" and its messages and tool history will be removed from " +
                "this device. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                onDeleteSession(session)
                pendingDeleteSession = null
            },
            onDismiss = { pendingDeleteSession = null }
        )
    }

    if (confirmClearSessions) {
        KernConfirmDialog(
            title = "Clear all sessions?",
            message = "All ${state.sessions.size} saved session(s), their messages, and tool history " +
                "will be removed from this device. Workspace files are never touched. " +
                "This cannot be undone.",
            confirmLabel = "Clear all",
            onConfirm = {
                onClearSessions()
                confirmClearSessions = false
            },
            onDismiss = { confirmClearSessions = false }
        )
    }
}

/** Compact, tappable action tile: an icon badge over a label, sized to a third of the row. */@Composable
private fun QuickActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = KernRadius.mdShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = KernElevation.none,
        shadowElevation = KernElevation.none,
        border = BorderStroke(KernElevation.hairline, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = KernSpacing.md, horizontal = KernSpacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KernSpacing.sm)
        ) {
            KernIconBadge(
                icon = icon,
                size = 36.dp,
                tint = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                container = if (enabled) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Workspace card: icon badge, name + meta, and the per-workspace actions. */
@Composable
private fun WorkspaceCard(
    workspace: Workspace,
    isActive: Boolean,
    onOpen: () -> Unit,
    onNewTask: () -> Unit,
    onDelete: () -> Unit
) {
    val meta = buildString {
        append(if (workspace.sourceType.name == "APP_MANAGED") "App-managed" else "Selected folder")
        if (isActive) append(" • active")
        if (workspace.grantState.name == "REVOKED") append(" • reconnect needed")
    }
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = KernRadius.mdShape,
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        tonalElevation = KernElevation.none,
        shadowElevation = KernElevation.none,
        border = BorderStroke(
            KernElevation.hairline,
            if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KernSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.md)
        ) {
            KernIconBadge(
                icon = Icons.Outlined.FolderOpen,
                container = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primaryContainer,
                tint = if (isActive) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onPrimaryContainer
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = workspace.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            KernIconButton(
                icon = Icons.Outlined.SmartToy,
                contentDescription = "New agent task",
                onClick = onNewTask,
                tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.primary
            )
            KernIconButton(
                icon = Icons.Outlined.DeleteOutline,
                contentDescription = "Remove workspace",
                onClick = onDelete,
                tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Tappable provider chip in the app bar — opens the provider editor. */
@Composable
private fun ProviderChip(label: String, onClick: () -> Unit) {
    Surface(
        shape = KernRadius.pillShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = KernSpacing.sm, vertical = KernSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.xs)
        ) {
            Icon(
                imageVector = Icons.Outlined.Hub,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(KernSizes.iconXs)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatTime(epochMillis: Long): String {
    val formatter = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
    return formatter.format(Date(epochMillis))
}
