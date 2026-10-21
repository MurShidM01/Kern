package com.aliedu.kern.feature.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliedu.kern.core.designsystem.components.EmptyState
import com.aliedu.kern.core.designsystem.components.KernButton
import com.aliedu.kern.core.designsystem.components.KernConfirmDialog
import com.aliedu.kern.core.designsystem.components.KernIconBadge
import com.aliedu.kern.core.designsystem.components.KernIconButton
import com.aliedu.kern.core.designsystem.components.KernInfoNote
import com.aliedu.kern.core.designsystem.components.KernPullToRefresh
import com.aliedu.kern.core.designsystem.components.KernScaffold
import com.aliedu.kern.core.designsystem.components.KernSegmentedControl
import com.aliedu.kern.core.designsystem.components.KernStatusCard
import com.aliedu.kern.core.designsystem.components.KernTextButton
import com.aliedu.kern.core.designsystem.components.KernTextField
import com.aliedu.kern.core.designsystem.components.KernTone
import com.aliedu.kern.core.designsystem.components.KernTopBar
import com.aliedu.kern.core.designsystem.components.LoadingState
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.core.designsystem.theme.KernElevation
import com.aliedu.kern.core.designsystem.theme.KernRadius
import com.aliedu.kern.core.designsystem.theme.KernSizes
import com.aliedu.kern.core.designsystem.theme.KernSpacing
import com.aliedu.kern.core.designsystem.theme.LocalKernSemanticColors

/** Which slice of the package database the list shows. */
private enum class PackageTab { AVAILABLE, INSTALLED }

/**
 * Ubuntu package manager (`apt`). Lists every package the index offers with an Install action, marks
 * the installed ones with a Remove action, and filters locally as you type. The raw command output is
 * not shown here — the debug FAB opens [PackageLogsScreen], which streams it — so the list keeps the
 * whole screen below the app bar. Every command runs in the same Ubuntu session the terminal and the
 * agent use.
 */
@Composable
fun PackageManagerScreen(
    onBack: () -> Unit,
    onOpenEnvironmentSetup: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenTerminal: () -> Unit,
    viewModel: PackageManagerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingRemove by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(PackageTab.AVAILABLE) }

    KernScaffold(
        topBar = {
            KernTopBar(
                title = "Packages",
                subtitle = "Ubuntu apt",
                navigationIcon = {
                    KernIconButton(
                        icon = KernIcons.Back,
                        contentDescription = "Back",
                        onClick = onBack
                    )
                },
                actions = {
                    KernIconButton(
                        icon = KernIcons.Download,
                        contentDescription = "Update package index",
                        enabled = !state.busy,
                        onClick = viewModel::updateIndex
                    )
                }
            )
        },
        floatingActionButton = {
            if (state.environmentReady) {
                FloatingActionButton(
                    onClick = onOpenLogs,
                    shape = KernRadius.mdShape,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp)
                ) {
                    Icon(
                        imageVector = KernIcons.Debug,
                        contentDescription = "Package logs"
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            when {
                state.loading -> LoadingState(label = "Checking the Linux environment…")

                state.unsupportedDistro != null -> EmptyState(
                    title = state.unsupportedDistro + " uses its own package manager",
                    message = "This screen drives apt, which Ubuntu ships. Install and remove " +
                        "packages for " + state.unsupportedDistro + " from the terminal instead.",
                    icon = KernIcons.Package,
                    action = {
                        KernButton(onClick = onOpenTerminal) { Text("Open terminal") }
                    }
                )

                !state.environmentReady -> EmptyState(
                    title = "A Linux environment is required",
                    message = "The package manager needs Ubuntu. Set it up once and apt, git, python " +
                        "and friends become available here and in the terminal.",
                    icon = KernIcons.Package,
                    action = {
                        KernButton(onClick = onOpenEnvironmentSetup) { Text("Set up Linux") }
                    }
                )

                else -> PackageManagerContent(
                    state = state,
                    tab = tab,
                    onTabChange = { tab = it },
                    onQueryChange = viewModel::onQueryChange,
                    onClearQuery = viewModel::clearQuery,
                    onRefresh = viewModel::refresh,
                    onUpdateIndex = viewModel::updateIndex,
                    onInstall = viewModel::install,
                    onRemoveRequest = { pendingRemove = it }
                )
            }
        }
    }

    pendingRemove?.let { name ->
        KernConfirmDialog(
            title = "Remove $name?",
            message = "The package will be removed from the Ubuntu environment. Packages that " +
                "depend on it may stop working.",
            confirmLabel = "Remove",
            onConfirm = {
                viewModel.remove(name)
                pendingRemove = null
            },
            onDismiss = { pendingRemove = null }
        )
    }
}

@Composable
private fun PackageManagerContent(
    state: PackageManagerUiState,
    tab: PackageTab,
    onTabChange: (PackageTab) -> Unit,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onRefresh: () -> Unit,
    onUpdateIndex: () -> Unit,
    onInstall: (String) -> Unit,
    onRemoveRequest: (String) -> Unit
) {
    val installedSet = remember(state.installed) { state.installed.toSet() }
    val query = state.query.trim()
    val visible = when (tab) {
        PackageTab.AVAILABLE -> state.results
        PackageTab.INSTALLED -> if (query.isEmpty()) {
            state.installed
        } else {
            state.installed.filter { it.contains(query, ignoreCase = true) }
        }
    }

    KernPullToRefresh(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        // The filter, the tabs and the index button are list items rather than a pinned header, so
        // the whole screen travels with the packages instead of only the rows scrolling.
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = KernSpacing.screenH,
                end = KernSpacing.screenH,
                top = KernSpacing.sm,
                bottom = FAB_CLEARANCE
            )
        ) {
            item(key = "controls") {
                Column(verticalArrangement = Arrangement.spacedBy(KernSpacing.sm)) {
                    KernTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        placeholder = "Filter packages (e.g. git, python3, nodejs)",
                        leadingIcon = KernIcons.Search,
                        trailingIcon = if (state.query.isNotEmpty()) {
                            {
                                KernIconButton(
                                    icon = KernIcons.Close,
                                    contentDescription = "Clear filter",
                                    onClick = onClearQuery
                                )
                            }
                        } else null,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    )

                    KernSegmentedControl(
                        options = PackageTab.entries,
                        selected = tab,
                        onSelect = onTabChange,
                        label = { if (it == PackageTab.AVAILABLE) "All packages" else "Installed" },
                        enabled = !state.busy
                    )

                    if (state.busy) {
                        KernStatusCard(
                            title = state.busyLabel ?: "Working…",
                            detail = "Running inside the Ubuntu shell.",
                            tone = KernTone.INFO,
                            trailing = {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(KernSizes.iconMd),
                                    strokeWidth = 1.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                    }

                    state.message?.let {
                        KernInfoNote(text = it, tone = KernTone.ERROR)
                    }
                }
            }

            item(key = "count") {
                Text(
                    text = when (tab) {
                        PackageTab.AVAILABLE -> if (query.isEmpty()) {
                            "Available · ${state.available.size}"
                        } else {
                            "${visible.size} match(es) for \"$query\" · ${state.available.size} total"
                        }
                        PackageTab.INSTALLED -> "Installed · ${state.installed.size}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = KernSpacing.sm, bottom = KernSpacing.xs)
                )
            }

            if (visible.isEmpty()) {
                item(key = "empty") {
                    KernInfoNote(
                        text = when {
                            tab == PackageTab.INSTALLED ->
                                "No packages are installed yet. Install one from All packages."
                            !state.catalogLoaded ->
                                "Loading the package index…"
                            query.isNotEmpty() ->
                                "Nothing matched. Try a different name."
                            else ->
                                "The index returned no packages. Tap Update to fetch it."
                        },
                        modifier = Modifier.padding(vertical = KernSpacing.md)
                    )
                }
            }

            items(visible, key = { it }) { name ->
                PackageRow(
                    name = name,
                    installed = name in installedSet,
                    busy = state.busy,
                    onInstall = { onInstall(name) },
                    onRemove = { onRemoveRequest(name) },
                    modifier = Modifier.padding(top = KernSpacing.xs)
                )
            }
        }
    }
}

@Composable
private fun PackageRow(
    name: String,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = KernRadius.mdShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(KernElevation.hairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = KernSpacing.sm, vertical = KernSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
        ) {
            KernIconBadge(
                icon = KernIcons.Package,
                size = 32.dp,
                iconSize = KernSizes.iconSm,
                container = if (installed) LocalKernSemanticColors.current.successContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                tint = if (installed) LocalKernSemanticColors.current.success
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (installed) {
                KernTextButton(onClick = onRemove, enabled = !busy) {
                    Text(
                        "Remove",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                KernTextButton(onClick = onInstall, enabled = !busy) {
                    Text("Install", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Keeps the last list row clear of the logs FAB. */
private val FAB_CLEARANCE = 88.dp
