package com.aliedu.kern.feature.files

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.aliedu.kern.core.designsystem.components.EmptyState
import com.aliedu.kern.core.designsystem.components.ErrorState
import com.aliedu.kern.core.designsystem.components.KernButton
import com.aliedu.kern.core.designsystem.components.KernConfirmDialog
import com.aliedu.kern.core.designsystem.components.KernDialog
import com.aliedu.kern.core.designsystem.components.KernDivider
import com.aliedu.kern.core.designsystem.components.KernIconButton
import com.aliedu.kern.core.designsystem.components.KernInfoNote
import com.aliedu.kern.core.designsystem.components.KernPullToRefresh
import com.aliedu.kern.core.designsystem.components.KernRefreshableState
import com.aliedu.kern.core.designsystem.components.KernScaffold
import com.aliedu.kern.core.designsystem.components.KernSettingRow
import com.aliedu.kern.core.designsystem.components.KernSidePanel
import com.aliedu.kern.core.designsystem.components.KernSidePanelHeader
import com.aliedu.kern.core.designsystem.components.KernTextField
import com.aliedu.kern.core.designsystem.components.KernTextInputDialog
import com.aliedu.kern.core.designsystem.components.KernTone
import com.aliedu.kern.core.designsystem.components.KernTopBar
import com.aliedu.kern.core.designsystem.components.LoadingState
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.core.designsystem.theme.KernElevation
import com.aliedu.kern.core.designsystem.theme.KernSizes
import com.aliedu.kern.core.designsystem.theme.KernSpacing
import com.aliedu.kern.core.designsystem.theme.LocalKernSemanticColors
import com.aliedu.kern.core.model.FileKind
import com.aliedu.kern.core.model.FileNode
import com.aliedu.kern.core.model.SearchMatchKind

@Composable
fun FilesExplorerScreen(
    state: FilesUiState,
    onOpen: (String) -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onNewFile: (String) -> Unit,
    onNewFolder: (String) -> Unit,
    onOpenFolder: (Uri, String) -> Unit,
    storageAccessGranted: Boolean,
    onRequestStorageAccess: () -> Unit,
    onToggleSidebar: () -> Unit,
    onCloseSidebar: () -> Unit,
    onToggleDirectory: (String) -> Unit
) {
    val context = LocalContext.current
    var showStoragePrompt by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull() ?: "Workspace"
            onOpenFolder(uri, name)
        }
    }
    // Offer all-files access first so the folder is used at its real path (shared with the terminal).
    val pickFolder: () -> Unit = {
        if (storageAccessGranted) launcher.launch(null) else showStoragePrompt = true
    }

    if (showStoragePrompt) {
        KernConfirmDialog(
            title = "All-files access",
            message = "Kern shares one folder between the Files, Editor, Agent and Terminal. " +
                "Granting \"All files access\" lets the terminal open that folder directly at its " +
                "real path. Without it, Kern edits a mirrored copy instead.",
            confirmLabel = "Grant access",
            dismissLabel = "Continue without",
            destructive = false,
            onConfirm = {
                showStoragePrompt = false
                onRequestStorageAccess()
            },
            onDismiss = {
                showStoragePrompt = false
                launcher.launch(null)
            }
        )
    }

    KernScaffold(
        topBar = {
            KernTopBar(
                title = state.workspace?.displayName ?: "Files",
                subtitle = state.breadcrumb.takeIf { state.currentPath.isNotEmpty() },
                navigationIcon = {
                    KernIconButton(
                        icon = Icons.Outlined.FolderOpen,
                        contentDescription = "Toggle folder tree",
                        onClick = onToggleSidebar
                    )
                },
                actions = {
                    if (state.currentPath.isNotEmpty()) {
                        KernIconButton(
                            icon = KernIcons.ArrowLeft,
                            contentDescription = "Up one level",
                            onClick = onUp
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                KernTextField(
                    value = state.searchQuery,
                    onValueChange = onSearch,
                    placeholder = "Search this workspace",
                    leadingIcon = KernIcons.Search,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = KernSpacing.screenH, vertical = KernSpacing.sm)
                )

                KernPullToRefresh(
                    isRefreshing = state.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    when {
                        state.workspace == null && !state.loading -> {
                            KernRefreshableState {
                                EmptyState(
                                    title = "No project selected",
                                    message = "Open a folder to browse and edit files.",
                                    icon = KernIcons.Folder,
                                    action = { KernButton(onClick = { pickFolder() }) { Text("Open folder") } }
                                )
                            }
                        }

                        state.grantRevoked -> {
                            KernRefreshableState {
                                ErrorState(
                                    message = state.error ?: "Folder access was revoked.",
                                    action = { KernButton(onClick = { pickFolder() }) { Text("Reconnect folder") } }
                                )
                            }
                        }

                        state.error != null && state.nodes.isEmpty() -> KernRefreshableState {
                            ErrorState(message = state.error)
                        }

                        state.loading -> LoadingState()

                        state.searchQuery.isNotBlank() -> {
                            if (state.searchResults.isEmpty()) {
                                KernRefreshableState {
                                    EmptyState(title = "No matches", message = "Nothing matched \"${state.searchQuery}\".")
                                }
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(
                                        start = KernSpacing.screenH,
                                        end = KernSpacing.screenH,
                                        bottom = KernSpacing.lg
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(KernSpacing.xs)
                                ) {
                                    items(state.searchResults) { match ->
                                        KernSettingRow(
                                            title = match.path,
                                            subtitle = if (match.kind == SearchMatchKind.CONTENT) {
                                                "${match.lineNumber}: ${match.lineText}"
                                            } else null,
                                            icon = KernIcons.File,
                                            iconContainer = MaterialTheme.colorScheme.surfaceVariant,
                                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            onClick = { onOpenFile(match.path) }
                                        )
                                    }
                                }
                            }
                        }

                        state.nodes.isEmpty() -> {
                            KernRefreshableState {
                                EmptyState(
                                    title = "Empty folder",
                                    message = "There are no files here yet.",
                                    icon = KernIcons.Folder,
                                    action = { KernButton(onClick = { onNewFile("new-file.txt") }) { Text("New file") } }
                                )
                            }
                        }

                        else -> {
                            LazyColumn(
                                contentPadding = PaddingValues(
                                    start = KernSpacing.screenH,
                                    end = KernSpacing.screenH,
                                    bottom = KernSpacing.lg
                                ),
                                verticalArrangement = Arrangement.spacedBy(KernSpacing.xs)
                            ) {
                                items(state.nodes, key = { it.path }) { node ->
                                    val isDir = node.kind == FileKind.DIRECTORY
                                    KernSettingRow(
                                        title = node.name,
                                        subtitle = node.sizeBytes?.let { formatSize(it) },
                                        icon = if (isDir) KernIcons.Folder else KernIcons.File,
                                        iconContainer = if (isDir) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        iconTint = if (isDir) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        onClick = { if (isDir) onOpen(node.path) else onOpenFile(node.path) },
                                        trailing = if (isDir) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Outlined.ChevronRight,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(KernSizes.iconMd)
                                                )
                                            }
                                        } else null
                                    )
                                }
                            }
                        }
                    }
                }
            }

            FileTreeDrawer(
                visible = state.sidebarOpen,
                state = state,
                onClose = onCloseSidebar,
                onOpenFile = onOpenFile,
                onToggleDirectory = onToggleDirectory,
                onRefresh = onRefresh,
                onCreateFile = onNewFile,
                onCreateFolder = onNewFolder
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Folder tree sidebar (shadcn-style: collapsible rows with chevrons)
// ---------------------------------------------------------------------------------------------

/** Which kind of entry the "add" flow is about to create. */
private enum class NewEntry { FILE, FOLDER }

/**
 * The workspace folder tree, shared by the Files screen and the Editor.
 *
 * The header carries the tree actions — refresh and add — because the panel is where the tree lives:
 * a file created from here appears in the tree at once, and a refresh picks up anything written by
 * the terminal or the agent.
 */
@Composable
private fun FileTreeDrawer(
    visible: Boolean,
    state: FilesUiState,
    onClose: () -> Unit,
    onOpenFile: (String) -> Unit,
    onToggleDirectory: (String) -> Unit,
    onRefresh: () -> Unit,
    onCreateFile: (String) -> Unit,
    onCreateFolder: (String) -> Unit
) {
    var addOpen by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<NewEntry?>(null) }

    if (addOpen) {
        KernDialog(
            title = "Add to workspace",
            message = "Create an empty file or folder in " + (state.workspace?.displayName ?: "this workspace") + ".",
            icon = KernIcons.AddSession,
            onDismiss = { addOpen = false },
            dismissLabel = "Cancel",
            content = {
                KernSettingRow(
                    title = "New file",
                    subtitle = "An empty text file",
                    icon = KernIcons.File,
                    onClick = {
                        addOpen = false
                        pending = NewEntry.FILE
                    }
                )
                KernSettingRow(
                    title = "New folder",
                    subtitle = "An empty directory",
                    icon = KernIcons.Folder,
                    onClick = {
                        addOpen = false
                        pending = NewEntry.FOLDER
                    }
                )
            }
        )
    }

    pending?.let { entry ->
        val isFile = entry == NewEntry.FILE
        KernTextInputDialog(
            title = if (isFile) "New file" else "New folder",
            label = "Name",
            initialValue = if (isFile) "new-file.txt" else "new-folder",
            confirmLabel = "Create",
            icon = if (isFile) KernIcons.File else KernIcons.Folder,
            validate = { name ->
                when {
                    name.isBlank() -> "Enter a name."
                    name.contains('/') -> "A name cannot contain a slash."
                    else -> null
                }
            },
            onDismiss = { pending = null },
            onConfirm = { name ->
                pending = null
                if (isFile) onCreateFile(name) else onCreateFolder(name)
            }
        )
    }

    KernSidePanel(visible = visible, onDismiss = onClose) {
        KernSidePanelHeader(
            title = state.workspace?.displayName ?: "Workspace",
            subtitle = "Explorer",
            icon = Icons.Outlined.FolderOpen,
            onClose = onClose,
            actions = {
                KernIconButton(
                    icon = KernIcons.Refresh,
                    contentDescription = "Refresh tree",
                    onClick = onRefresh
                )
                KernIconButton(
                    icon = KernIcons.AddSession,
                    contentDescription = "Add file or folder",
                    onClick = { addOpen = true }
                )
            }
        )
        KernDivider()
        val rootChildren = state.dirChildren[""]
        when {
            rootChildren == null -> LoadingState()
            rootChildren.isEmpty() -> Text(
                "This folder is empty.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(KernSpacing.md)
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = KernSpacing.xs)
            ) {
                TreeLevel(
                    parentPath = "",
                    depth = 0,
                    state = state,
                    onToggleDirectory = onToggleDirectory,
                    onOpenFile = onOpenFile
                )
            }
        }
    }
}

@Composable
private fun TreeLevel(
    parentPath: String,
    depth: Int,
    state: FilesUiState,
    onToggleDirectory: (String) -> Unit,
    onOpenFile: (String) -> Unit
) {
    val children = state.dirChildren[parentPath] ?: return
    Column(modifier = Modifier.fillMaxWidth()) {
        children.forEach { node ->
            val isDir = node.kind == FileKind.DIRECTORY
            val expanded = state.expandedDirs.contains(node.path)
            TreeRow(
                node = node,
                depth = depth,
                isDirectory = isDir,
                expanded = expanded,
                loading = state.loadingDirs.contains(node.path),
                selected = isDir && state.currentPath == node.path,
                onClick = { if (isDir) onToggleDirectory(node.path) else onOpenFile(node.path) }
            )
            if (isDir && expanded) {
                TreeLevel(
                    parentPath = node.path,
                    depth = depth + 1,
                    state = state,
                    onToggleDirectory = onToggleDirectory,
                    onOpenFile = onOpenFile
                )
            }
        }
    }
}

@Composable
private fun TreeRow(
    node: FileNode,
    depth: Int,
    isDirectory: Boolean,
    expanded: Boolean,
    loading: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    val nameColor = when {
        selected -> MaterialTheme.colorScheme.primary
        isDirectory -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = (KernSpacing.sm + (depth * 14).dp),
                end = KernSpacing.sm,
                top = 6.dp,
                bottom = 6.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isDirectory) {
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(KernSizes.iconSm)
            )
            Spacer(Modifier.width(KernSpacing.xxs))
            Icon(
                imageVector = if (expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(KernSizes.iconSm)
            )
        } else {
            Spacer(Modifier.width(KernSizes.iconSm))
            Spacer(Modifier.width(KernSpacing.xxs))
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(KernSizes.iconSm)
            )
        }
        Spacer(Modifier.width(KernSpacing.sm))
        Text(
            text = node.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isDirectory) FontWeight.Medium else FontWeight.Normal,
            color = nameColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(KernSizes.iconXs),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Editor (monospace with a line-number gutter and a status bar)
// ---------------------------------------------------------------------------------------------

@Composable
fun EditorScreen(
    state: EditorUiState,
    onBack: () -> Unit,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
    treeState: FilesUiState = FilesUiState(),
    onToggleSidebar: () -> Unit = {},
    onCloseSidebar: () -> Unit = {},
    onToggleDirectory: (String) -> Unit = {},
    onOpenFile: (String) -> Unit = {},
    onNewFile: (String) -> Unit = {},
    onNewFolder: (String) -> Unit = {},
    onRefreshTree: () -> Unit = {}
) {
    val semantic = LocalKernSemanticColors.current
    val status = when {
        state.saving -> "Saving…"
        state.isDirty -> "Unsaved changes"
        state.savedAt != null -> "Saved"
        state.readOnly -> "Read-only"
        else -> "Up to date"
    }
    val statusColor = when {
        state.isDirty -> semantic.warning
        state.savedAt != null -> semantic.success
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    KernScaffold(
        topBar = {
            KernTopBar(
                title = state.path.substringAfterLast('/').ifEmpty { "Editor" },
                subtitle = status,
                navigationIcon = {
                    KernIconButton(
                        icon = KernIcons.Back,
                        contentDescription = "Back",
                        onClick = onBack
                    )
                },
                actions = {
                    // The drawer replaces the reload button: it holds the tree, its refresh action
                    // and the add actions, which is what you reach for while editing.
                    KernIconButton(
                        icon = KernIcons.Files,
                        contentDescription = "Folder tree",
                        onClick = onToggleSidebar
                    )
                    KernIconButton(
                        icon = Icons.Outlined.Save,
                        contentDescription = "Save",
                        onClick = onSave,
                        enabled = state.isDirty && !state.readOnly && !state.saving,
                        tint = if (state.isDirty) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
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
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = semantic.codeBackground
        ) {
            when {
                state.loading -> LoadingState()

                state.error != null && state.readOnly -> ErrorState(message = state.error)

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        .navigationBarsPadding()
                ) {
                    if (state.truncated) {
                        NoticeBanner("This file is larger than the edit limit and was truncated for display.")
                    }
                    if (state.error != null) {
                        NoticeBanner(state.error, isError = true)
                    }
                    CodeEditor(
                        state = state,
                        onTextChange = onTextChange,
                        modifier = Modifier.weight(1f)
                    )
                    EditorStatusBar(state = state, status = status, statusColor = statusColor)
                }
            }
        }

            FileTreeDrawer(
                visible = treeState.sidebarOpen,
                state = treeState,
                onClose = onCloseSidebar,
                onOpenFile = onOpenFile,
                onToggleDirectory = onToggleDirectory,
                // Refresh the tree, and re-read the file when there is nothing unsaved to lose.
                onRefresh = {
                    onRefreshTree()
                    if (!state.isDirty) onReload()
                },
                onCreateFile = onNewFile,
                onCreateFolder = onNewFolder
            )
        }
    }
}

@Composable
private fun CodeEditor(
    state: EditorUiState,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalKernSemanticColors.current
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val lineHeightSp = state.fontSizeSp * 1.5f
    val lineCount = state.text.count { it == '\n' } + 1
    val gutterDigits = lineCount.toString().length.coerceAtLeast(2)
    val gutterWidth = (KernSpacing.sm.value + gutterDigits * 8).dp

    val codeStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = state.fontSizeSp.sp,
        lineHeight = lineHeightSp.sp,
        color = semantic.codeText
    )
    val gutterStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = state.fontSizeSp.sp,
        lineHeight = lineHeightSp.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    )

    val density = LocalDensity.current
    val lineHeightPx = with(density) { lineHeightSp.sp.toPx() }
    val topPadPx = with(density) { KernSpacing.sm.toPx() }

    Box(modifier = modifier.fillMaxWidth()) {
        // The gutter is pinned to the viewport and offset by the scroll position, so it draws a few
        // dozen numbers instead of one composable (or one huge canvas) per line. That keeps it exact
        // for any file size — a 3000-line file used to show numbers only for the first screenful.
        Row(modifier = Modifier.fillMaxWidth()) {
            LineNumberGutter(
                lineCount = lineCount,
                width = gutterWidth,
                lineHeightPx = lineHeightPx,
                topPadPx = topPadPx,
                // A lambda, not a value: reading the scroll state inside the draw scope keeps a
                // scroll from recomposing the whole editor.
                scrollOffset = { verticalScroll.value },
                style = gutterStyle
            )
            Box(
                modifier = Modifier
                    .width(KernElevation.hairline)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(verticalScroll)
            ) {
                Box(modifier = Modifier.horizontalScroll(horizontalScroll)) {
                    BasicTextField(
                        value = state.text,
                        onValueChange = onTextChange,
                        enabled = !state.readOnly,
                        textStyle = codeStyle,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .widthIn(min = 320.dp)
                            .padding(
                                start = KernSpacing.sm,
                                end = KernSpacing.md,
                                top = KernSpacing.sm,
                                bottom = KernSpacing.sm
                            )
                    )
                }
            }
        }
    }
}

/**
 * The line-number gutter, drawn rather than composed.
 *
 * A `Column` of one `Text` per line is measured against the viewport height, so past the first
 * screenful the remaining rows collapse to zero height and the numbers simply stop — which read as
 * "the gutter caps at line N". Drawing only the rows inside the scroll window keeps the numbers
 * correct for any file size and costs a few dozen measurements per frame instead of one composable
 * per line.
 */
@Composable
private fun LineNumberGutter(
    lineCount: Int,
    width: Dp,
    lineHeightPx: Float,
    topPadPx: Float,
    scrollOffset: () -> Int,
    style: TextStyle
) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    Canvas(modifier = Modifier.width(width).fillMaxHeight()) {
        if (lineCount <= 0 || size.height <= 0f) return@Canvas
        val offset = scrollOffset().toFloat()
        val first = ((offset - topPadPx) / lineHeightPx).toInt().coerceIn(0, lineCount - 1)
        val visible = (size.height / lineHeightPx).toInt() + 2
        val last = (first + visible).coerceAtMost(lineCount - 1)
        val endPad = 8.dp.toPx()
        for (index in first..last) {
            val layout = measurer.measure(text = "${index + 1}", style = style)
            drawText(
                textLayoutResult = layout,
                // The gutter is outside the scroll container, so the scroll offset is applied here.
                topLeft = Offset(
                    x = size.width - endPad - layout.size.width,
                    y = topPadPx + index * lineHeightPx - offset
                )
            )
        }
    }
}

@Composable
private fun EditorStatusBar(state: EditorUiState, status: String, statusColor: Color) {
    val lineCount = state.text.count { it == '\n' } + 1
    val charCount = state.text.length
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            KernDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KernSpacing.md, vertical = KernSpacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$lineCount lines · $charCount chars · ${state.fontSizeSp.toInt()}sp",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
            }
        }
    }
}

@Composable
private fun NoticeBanner(text: String, isError: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isError) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(horizontal = KernSpacing.screenH, vertical = KernSpacing.sm)
    ) {
        KernInfoNote(text = text, tone = if (isError) KernTone.ERROR else KernTone.NEUTRAL)
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
