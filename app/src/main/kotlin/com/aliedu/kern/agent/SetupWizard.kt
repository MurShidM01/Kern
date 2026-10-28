package com.aliedu.kern.agent

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aliedu.kern.core.designsystem.components.KernBrandHeader
import com.aliedu.kern.core.designsystem.components.KernButton
import com.aliedu.kern.core.designsystem.components.KernConfirmDialog
import com.aliedu.kern.core.designsystem.components.KernDivider
import com.aliedu.kern.core.designsystem.components.KernInfoNote
import com.aliedu.kern.core.designsystem.components.KernOptionCard
import com.aliedu.kern.core.designsystem.components.KernOutlinedButton
import com.aliedu.kern.core.designsystem.components.KernStatusCard
import com.aliedu.kern.core.designsystem.components.KernSurface
import com.aliedu.kern.core.designsystem.components.KernTextButton
import com.aliedu.kern.core.designsystem.components.KernTone
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.core.designsystem.theme.KernSizes
import com.aliedu.kern.core.designsystem.theme.KernSpacing
import com.aliedu.kern.core.terminal.api.EnvironmentState
import com.aliedu.kern.feature.home.NameWorkspaceDialog
import com.aliedu.kern.feature.home.rememberStorageAwareFolderPicker
import com.aliedu.kern.feature.terminal.DistroPicker
import com.aliedu.kern.feature.terminal.EnvironmentSetupViewModel

private data class WizardStep(
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

private val wizardSteps = listOf(
    WizardStep("Workspace", "Where Kern keeps your files", KernIcons.Folder),
    WizardStep("Terminal", "Linux environment for the shell", KernIcons.Terminal),
    WizardStep("AI provider", "The endpoint that powers the agent", KernIcons.Provider)
)

/**
 * First-run onboarding as a professional three-step wizard: Workspace → Linux for Terminal → AI
 * provider. Each step is skippable and the user can move back and forth; the Linux and provider
 * steps are optional, so setup can always be completed from the final step.
 */
@Composable
fun SetupWizard(
    providerConfigured: Boolean,
    storageAccessGranted: Boolean,
    storageAccessSupported: Boolean = true,
    hasWorkspace: Boolean,
    workspaceLabel: String?,
    onRequestStorageAccess: () -> Unit,
    onOpenFolder: (Uri, String) -> Unit,
    onCreateWorkspace: (String) -> Unit,
    onConfigureProvider: () -> Unit,
    onFinish: () -> Unit,
    environmentViewModel: EnvironmentSetupViewModel = hiltViewModel()
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val pickFolder = rememberStorageAwareFolderPicker(
        storageAccessGranted = storageAccessGranted,
        storageAccessSupported = storageAccessSupported,
        onRequestStorageAccess = onRequestStorageAccess,
        onPicked = onOpenFolder
    )

    val goNext: () -> Unit = { if (step < wizardSteps.lastIndex) step++ else onFinish() }
    val goBack: () -> Unit = { if (step > 0) step-- }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            WizardHeader(step = step, onSkip = goNext)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val forward = targetState > initialState
                        val enter = slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()
                        val exit = slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut()
                        enter togetherWith exit
                    },
                    label = "wizard-step"
                ) { current ->
                    when (current) {
                        0 -> WorkspaceStep(
                            hasWorkspace = hasWorkspace,
                            workspaceLabel = workspaceLabel,
                            storageAccessGranted = storageAccessGranted,
                            onPickFolder = pickFolder,
                            onCreateWorkspace = onCreateWorkspace
                        )
                        1 -> LinuxStep(viewModel = environmentViewModel)
                        else -> ProviderStep(
                            providerConfigured = providerConfigured,
                            onConfigureProvider = onConfigureProvider
                        )
                    }
                }
            }

            WizardFooter(step = step, onBack = goBack, onNext = goNext)
        }
    }
}

@Composable
private fun WizardHeader(step: Int, onSkip: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = KernSpacing.screenH)) {
        KernBrandHeader(
            subtitle = "Set up in a few steps",
            actions = {
                KernTextButton(onClick = onSkip) {
                    Text("Skip", style = MaterialTheme.typography.labelLarge)
                }
            }
        )

        Spacer(Modifier.height(KernSpacing.sm))
        StepIndicator(current = step)
        Spacer(Modifier.height(KernSpacing.md))
        KernDivider()
    }
}

@Composable
private fun StepIndicator(current: Int) {
    Row(modifier = Modifier.fillMaxWidth()) {
        wizardSteps.forEachIndexed { index, step ->
            val done = index < current
            val active = index == current
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Connector(
                        modifier = Modifier.weight(1f),
                        visible = index > 0,
                        done = index <= current
                    )
                    StepDot(index = index, done = done, active = active)
                    Connector(
                        modifier = Modifier.weight(1f),
                        visible = index < wizardSteps.lastIndex,
                        done = done
                    )
                }
                Spacer(Modifier.height(KernSpacing.xs))
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index <= current) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun Connector(modifier: Modifier, visible: Boolean, done: Boolean) {
    Box(
        modifier
            .height(2.dp)
            .background(
                when {
                    !visible -> Color.Transparent
                    done -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
            )
    )
}

@Composable
private fun StepDot(index: Int, done: Boolean, active: Boolean) {
    val background = when {
        done || active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when {
        done || active -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(background)
            .then(
                if (done || active) {
                    Modifier
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (done) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(KernSizes.iconXs)
            )
        } else {
            Text(text = "${index + 1}", style = MaterialTheme.typography.labelSmall, color = foreground)
        }
    }
}

@Composable
private fun WizardFooter(step: Int, onBack: () -> Unit, onNext: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        KernDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = KernSpacing.screenH, vertical = KernSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
        ) {
            if (step > 0) {
                KernOutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = KernIcons.ArrowLeft,
                        contentDescription = null,
                        modifier = Modifier.size(KernSizes.iconSm)
                    )
                    Spacer(Modifier.width(KernSpacing.xs))
                    Text("Back")
                }
            }
            KernButton(onClick = onNext, modifier = Modifier.weight(if (step > 0) 1.6f else 1f)) {
                Text(if (step == wizardSteps.lastIndex) "Finish" else "Next")
                Spacer(Modifier.width(KernSpacing.xs))
                Icon(
                    imageVector = if (step == wizardSteps.lastIndex) {
                        Icons.Outlined.Check
                    } else {
                        KernIcons.ArrowRight
                    },
                    contentDescription = null,
                    modifier = Modifier.size(KernSizes.iconSm)
                )
            }
        }
    }
}

@Composable
private fun StepScaffold(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = KernSpacing.screenH, vertical = KernSpacing.md),
        verticalArrangement = Arrangement.spacedBy(KernSpacing.md),
        content = content
    )
}

@Composable
private fun StepHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(KernSpacing.xs)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WorkspaceStep(
    hasWorkspace: Boolean,
    workspaceLabel: String?,
    storageAccessGranted: Boolean,
    onPickFolder: () -> Unit,
    onCreateWorkspace: (String) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    StepScaffold {
        StepHeading(
            title = "Choose your workspace",
            subtitle = "Kern keeps your files in one folder shared by Files, the Editor, the Agent and " +
                "the Terminal — so every part of the app works on the same tree."
        )

        if (hasWorkspace) {
            KernStatusCard(
                title = workspaceLabel ?: "Workspace ready",
                detail = "You can switch workspaces any time from Home.",
                tone = KernTone.SUCCESS
            )
        }

        KernOptionCard(
            icon = Icons.Outlined.FolderOpen,
            title = "Open an existing folder",
            detail = "Pick a folder on your device and use it as your project.",
            onClick = onPickFolder
        )
        KernOptionCard(
            icon = Icons.Outlined.CreateNewFolder,
            title = "Create app-managed workspace",
            detail = "Start fresh inside Kern's private storage.",
            onClick = { showCreateDialog = true }
        )

        if (!storageAccessGranted) {
            KernInfoNote(
                text = "Grant \"All files access\" to open folders at their real /sdcard path and share " +
                    "them with the terminal. Without it, Kern edits a mirrored copy."
            )
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
}

@Composable
private fun LinuxStep(viewModel: EnvironmentSetupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val info = state.info
    val ready = info?.state == EnvironmentState.READY
    val selected = state.distros.firstOrNull { it.id == state.selectedDistroId }
    var confirmSwitch by remember { mutableStateOf(false) }
    val notEnoughRoom = info?.let { i ->
        val free = i.freeBytes
        val required = i.requiredBytes
        free != null && required != null && free < required
    } ?: false

    StepScaffold {
        StepHeading(
            title = "Set up Linux for the terminal",
            subtitle = "Kern ships a native shell. Optionally add a Linux userspace to run " +
                "package tools such as apt, git and python inside the terminal."
        )

        // The distribution is chosen here, not after onboarding: the first-run wizard is where a
        // user decides what their terminal is, and sending them to Settings to change it later is
        // exactly the extra step this screen exists to avoid.
        DistroPicker(
            distros = state.distros,
            selectedId = state.selectedDistroId,
            enabled = !state.installing,
            onSelect = viewModel::selectDistro
        )

        KernStatusCard(
            title = when {
                state.loading -> "Checking…"
                ready -> "${info?.distroName ?: info?.distroId ?: "Linux"} ${info?.version ?: ""}".trim()
                info?.state == EnvironmentState.FAILED -> "Install failed"
                else -> "Not installed"
            },
            detail = info?.detail ?: "You can skip this and add Linux later from the terminal.",
            tone = when {
                ready -> KernTone.SUCCESS
                info?.state == EnvironmentState.FAILED -> KernTone.ERROR
                else -> KernTone.NEUTRAL
            }
        )

        if (state.installing) {
            KernSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(KernSpacing.md)) {
                    Text(
                        text = state.progress?.phase ?: "Preparing…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(KernSpacing.sm))
                    val fraction = state.progress?.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    val detail = buildList {
                        state.progress?.bytesDownloaded?.let {
                            add(formatBytes(it) + (state.progress?.totalBytes?.let { t -> " / ${formatBytes(t)}" } ?: ""))
                        }
                        state.progress?.currentComponent?.let { add(it) }
                    }.joinToString(" · ")
                    if (detail.isNotEmpty()) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = KernSpacing.xs)
                        )
                    }
                }
            }
            KernOutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        } else {
            KernButton(
                onClick = {
                    if (ready && selected != null && info?.distroId != selected.id) {
                        confirmSwitch = true
                    } else {
                        viewModel.install()
                    }
                },
                enabled = !notEnoughRoom && selected != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        ready && selected != null && selected.id == info?.distroId -> "Repair " + selected.name
                        ready -> "Switch to " + (selected?.name ?: "the selected distribution")
                        else -> "Install " + (selected?.name ?: "Linux")
                    }
                )
            }
        }

        state.error?.let { KernInfoNote(it, tone = KernTone.ERROR) }
        if (notEnoughRoom) {
            KernInfoNote(
                "Not enough free space to install. Free up storage and try again.",
                tone = KernTone.ERROR
            )
        }
        KernInfoNote("Optional — the built-in shell works without a Linux userspace.")
    }

    if (confirmSwitch) {
        KernConfirmDialog(
            title = "Switch to " + (selected?.name ?: "this distribution") + "?",
            message = (info?.distroName ?: "The installed distribution") +
                " will be removed and " + (selected?.name ?: "the selected distribution") +
                " downloaded in its place. Your workspaces, chats, and settings are never touched.",
            confirmLabel = "Switch",
            onConfirm = {
                confirmSwitch = false
                viewModel.install()
            },
            onDismiss = { confirmSwitch = false }
        )
    }
}

@Composable
private fun ProviderStep(providerConfigured: Boolean, onConfigureProvider: () -> Unit) {
    StepScaffold {
        StepHeading(
            title = "Connect an AI provider",
            subtitle = "The agent needs an OpenAI-compatible endpoint. Your API key is stored on this " +
                "device only and is never sent anywhere except that endpoint."
        )

        if (providerConfigured) {
            KernStatusCard(
                title = "Provider configured",
                detail = "Kern can reach your endpoint. You can change it any time in Settings.",
                tone = KernTone.SUCCESS
            )
        } else {
            KernInfoNote(
                text = "No provider yet. You can add one now, or later from Settings.",
                tone = KernTone.WARNING
            )
        }

        KernButton(onClick = onConfigureProvider, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = KernIcons.Provider,
                contentDescription = null,
                modifier = Modifier.size(KernSizes.iconSm)
            )
            Spacer(Modifier.width(KernSpacing.sm))
            Text(if (providerConfigured) "Manage provider" else "Configure provider")
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
