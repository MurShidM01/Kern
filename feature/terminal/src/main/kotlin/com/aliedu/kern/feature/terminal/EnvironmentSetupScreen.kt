package com.aliedu.kern.feature.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliedu.kern.core.designsystem.components.ErrorState
import com.aliedu.kern.core.designsystem.components.KernButton
import com.aliedu.kern.core.designsystem.components.KernConfirmDialog
import com.aliedu.kern.core.designsystem.components.KernIconBadge
import com.aliedu.kern.core.designsystem.components.KernIconButton
import com.aliedu.kern.core.designsystem.components.KernInfoNote
import com.aliedu.kern.core.designsystem.components.KernOptionCard
import com.aliedu.kern.core.designsystem.components.KernOutlinedButton
import com.aliedu.kern.core.designsystem.components.KernPullToRefresh
import com.aliedu.kern.core.designsystem.components.KernScaffold
import com.aliedu.kern.core.designsystem.components.KernSettingDivider
import com.aliedu.kern.core.designsystem.components.KernSettingRow
import com.aliedu.kern.core.designsystem.components.KernSettingsGroup
import com.aliedu.kern.core.designsystem.components.KernStatusCard
import com.aliedu.kern.core.designsystem.components.KernTextButton
import com.aliedu.kern.core.designsystem.components.KernTone
import com.aliedu.kern.core.designsystem.components.KernTopBar
import com.aliedu.kern.core.designsystem.components.LoadingState
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.core.designsystem.theme.KernSizes
import com.aliedu.kern.core.designsystem.theme.KernSpacing
import com.aliedu.kern.core.terminal.api.DistroOption
import com.aliedu.kern.core.terminal.api.EnvironmentInfo
import com.aliedu.kern.core.terminal.api.EnvironmentState
import com.aliedu.kern.core.terminal.api.InstallProgress

@Composable
fun EnvironmentSetupScreen(
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenPackages: () -> Unit,
    viewModel: EnvironmentSetupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmSwitch by remember { mutableStateOf(false) }

    LaunchedEffect(state.completed) {
        if (state.completed) viewModel.consumeCompleted()
    }

    KernScaffold(
        topBar = {
            KernTopBar(
                title = "Linux environment",
                subtitle = "Choose a Linux userspace for the terminal",
                navigationIcon = {
                    KernIconButton(
                        icon = KernIcons.Back,
                        contentDescription = "Back",
                        onClick = onBack
                    )
                }
            )
        }
    ) { padding ->
        KernPullToRefresh(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = KernSpacing.screenH, vertical = KernSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(KernSpacing.lg)
            ) {
                if (state.loading) {
                    LoadingState(label = "Checking environment…")
                } else {
                    val info = state.info
                    when (info?.state) {
                        EnvironmentState.UNSUPPORTED_ABI -> UnsupportedAbi(info)

                        else -> {
                            EnvironmentSummary(info)
                            DistroPicker(
                                distros = state.distros,
                                selectedId = state.selectedDistroId,
                                enabled = !state.installing,
                                onSelect = viewModel::selectDistro
                            )
                            InstallControls(
                                info = info,
                                selected = state.distros.firstOrNull { it.id == state.selectedDistroId },
                                installing = state.installing,
                                progress = state.progress,
                                onInstall = {
                                    val current = info?.takeIf { it.state == EnvironmentState.READY }
                                    val target = state.selectedDistroId
                                    if (current != null && target != null && current.distroId != target) {
                                        confirmSwitch = true
                                    } else {
                                        viewModel.install()
                                    }
                                },
                                onCancel = viewModel::cancel,
                                onOpenTerminal = onOpenTerminal,
                                onOpenPackages = onOpenPackages,
                                onRemove = { confirmRemove = true }
                            )
                        }
                    }

                    state.error?.let { ErrorBanner(message = it, onDismiss = viewModel::dismissError) }

                    InstallLogSection(
                        log = state.log,
                        expanded = state.logExpanded,
                        onToggle = viewModel::toggleLog
                    )
                }
            }
        }
    }

    if (confirmSwitch) {
        val current = state.info?.distroName ?: state.info?.distroId ?: "the installed distribution"
        val target = state.distros.firstOrNull { it.id == state.selectedDistroId }?.name ?: "the selected distribution"
        KernConfirmDialog(
            title = "Switch to $target?",
            message = "$current will be removed and $target downloaded in its place. Your workspaces, " +
                "chats, and settings are never touched.",
            confirmLabel = "Switch",
            onConfirm = {
                confirmSwitch = false
                viewModel.install()
            },
            onDismiss = { confirmSwitch = false }
        )
    }

    if (confirmRemove) {
        KernConfirmDialog(
            title = "Remove Linux environment?",
            message = "The downloaded environment will be deleted from this device. Your workspaces, " +
                "chats, and settings are never touched. The built-in shell stays available.",
            confirmLabel = "Remove",
            onConfirm = {
                viewModel.uninstall()
                confirmRemove = false
            },
            onDismiss = { confirmRemove = false }
        )
    }
}

@Composable
private fun UnsupportedAbi(info: EnvironmentInfo) {
    ErrorState(
        message = info.detail ?: "This device's CPU architecture is not supported.",
        action = {
            Text(
                "Supported architecture: arm64-v8a.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
private fun EnvironmentSummary(info: EnvironmentInfo?) {
    val ready = info?.state == EnvironmentState.READY
    // Installed, but the sandbox cannot start on this device (e.g. the kernel denies `ptrace` to
    // apps): the terminal falls back to the built-in shell, so don't claim a working Linux guest.
    val runtimeBlocked = info?.state == EnvironmentState.READY && !info.runtimeUsable
    val tone = when {
        runtimeBlocked -> KernTone.WARNING
        info?.state == EnvironmentState.READY -> KernTone.SUCCESS
        info?.state == EnvironmentState.FAILED -> KernTone.ERROR
        info?.state == EnvironmentState.UNSUPPORTED_ABI -> KernTone.ERROR
        else -> KernTone.NEUTRAL
    }

    KernStatusCard(
        title = when {
            runtimeBlocked -> "${info.distroName ?: "Linux"} cannot run here"
            info?.state == EnvironmentState.READY ->
                "${info.distroName ?: info.distroId ?: "Linux"} ${info.version ?: ""}".trim()
            info?.state == EnvironmentState.FAILED -> "Install failed"
            else -> "Not installed"
        },
        detail = info?.detail ?: "Download a Linux userspace to run package tools.",
        tone = tone,
        icon = KernIcons.Terminal
    )

    KernSettingsGroup(title = "Details") {
        MetricRow("Device ABI", info?.abi ?: "unknown")
        KernSettingDivider()
        MetricRow("Free space", info?.freeBytes?.let(::formatBytes) ?: "—")
        KernSettingDivider()
        MetricRow("Required", info?.requiredBytes?.let(::formatBytes) ?: "—")
        val installed = info?.installedSizeBytes
        if (installed != null) {
            KernSettingDivider()
            MetricRow("Installed size", formatBytes(installed))
        }
    }

    if (ready && !runtimeBlocked) {
        KernInfoNote(
            text = "Your workspace is shared with the terminal at /sdcard, so edits made in Files, " +
                "the Editor or the Agent appear in the shell at once."
        )
    }
}


/**
 * The distribution chooser. Each card is one catalog entry with its version and download size; the
 * installed one is marked, and an unsupported one (no bundle for this ABI) cannot be selected.
 */
@Composable
fun DistroPicker(
    distros: List<DistroOption>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    // With a single distribution there is nothing to choose: the status card and the button name it.
    if (distros.size <= 1) return
    Column(verticalArrangement = Arrangement.spacedBy(KernSpacing.sm)) {
        Text(
            text = "Distribution",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        distros.forEach { distro ->
            KernOptionCard(
                icon = KernIcons.Terminal,
                title = distro.name,
                detail = buildString {
                    append(distro.version)
                    val size = distro.downloadBytes
                    if (distro.supported && size != null) {
                        append(" · ")
                        append(formatBytes(size))
                        append(" download")
                    }
                    if (!distro.supported) append(" · not available for this device")
                    if (distro.installed) append(" · installed")
                },
                selected = distro.id == selectedId,
                enabled = enabled && distro.supported,
                onClick = { onSelect(distro.id) },
                trailing = {
                    if (distro.installed) {
                        Text(
                            text = "Installed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
        if (distros.none { it.supported }) {
            KernInfoNote(
                text = "No distribution in the catalog supports this device's CPU architecture.",
                tone = KernTone.ERROR
            )
        }
    }
}

@Composable
private fun InstallControls(
    info: EnvironmentInfo?,
    selected: DistroOption?,
    installing: Boolean,
    progress: InstallProgress?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenPackages: () -> Unit,
    onRemove: () -> Unit
) {
    val ready = info?.state == EnvironmentState.READY
    val runtimeBlocked = info?.state == EnvironmentState.READY && !info.runtimeUsable
    val notEnoughRoom = info?.let { i ->
        val free = i.freeBytes
        val required = i.requiredBytes
        free != null && required != null && free < required
    } ?: false

    if (installing) {
        KernStatusCard(
            title = progress?.phase ?: "Preparing…",
            detail = "Downloading and unpacking the " + (selected?.name ?: "Linux") + " userspace.",
            tone = KernTone.INFO,
            icon = KernIcons.Download
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(KernSpacing.xs)
        ) {
            val fraction = progress?.fraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            val detail = buildList {
                progress?.bytesDownloaded?.let {
                    add("${formatBytes(it)}${progress.totalBytes?.let { t -> " / ${formatBytes(t)}" } ?: ""}")
                }
                progress?.bytesPerSecond?.takeIf { it > 0 }?.let { add("${formatBytes(it)}/s") }
                progress?.currentComponent?.let { add(it) }
            }.joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        KernOutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)) {
            KernButton(
                onClick = onInstall,
                enabled = !notEnoughRoom,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = KernIcons.Download,
                    contentDescription = null,
                    modifier = Modifier.size(KernSizes.iconSm)
                )
                Spacer(Modifier.width(KernSpacing.xs))
                Text(
                    when {
                        ready && selected?.id == info?.distroId -> "Repair"
                        ready -> "Switch"
                        else -> "Download"
                    }
                )
            }
            if (ready) {
                KernOutlinedButton(onClick = onOpenTerminal, modifier = Modifier.weight(1f)) {
                    Text("Open terminal")
                }
            }
        }
        if (ready) {
            // apt only exists inside the guest, so the package manager is pointless when the sandbox
            // cannot start; the environment can still be removed.
            if (!runtimeBlocked) {
                KernSettingRow(
                    title = "Manage packages",
                    subtitle = "Install and remove packages inside " + (info?.distroName ?: "the guest"),
                    icon = KernIcons.Package,
                    onClick = onOpenPackages,
                    trailing = {
                        KernTextButton(onClick = onOpenPackages) { Text("Open") }
                    }
                )
            }
            KernOutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                Text("Remove environment")
            }
        }
        if (notEnoughRoom) {
            KernInfoNote(
                text = "Not enough free space to install. Free up storage and try again.",
                tone = KernTone.ERROR
            )
        }
    }
}

@Composable
private fun InstallLogSection(log: String, expanded: Boolean, onToggle: () -> Unit) {
    KernSettingsGroup(title = "Install log") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KernSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.md)
        ) {
            KernIconBadge(
                icon = KernIcons.History,
                container = MaterialTheme.colorScheme.surfaceVariant,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (expanded) "Hide details" else "Show details",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            KernTextButton(onClick = onToggle) {
                Text(if (expanded) "Hide" else "Show")
            }
        }
        if (expanded) {
            KernSettingDivider()
            Text(
                text = log.ifBlank { "No install activity yet." },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 40,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(KernSpacing.md)
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KernSpacing.md, vertical = KernSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
    ) {
        KernInfoNote(
            text = message,
            tone = KernTone.ERROR,
            modifier = Modifier.weight(1f)
        )
        KernTextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
