package com.aliedu.kern.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliedu.kern.core.designsystem.components.KernButton
import com.aliedu.kern.core.designsystem.components.KernConfirmDialog
import com.aliedu.kern.core.designsystem.components.KernDropdownItem
import com.aliedu.kern.core.designsystem.components.KernDropdownMenu
import com.aliedu.kern.core.designsystem.components.KernIconBadge
import com.aliedu.kern.core.designsystem.components.KernIconButton
import com.aliedu.kern.core.designsystem.components.KernInfoNote
import com.aliedu.kern.core.designsystem.components.KernOptionCard
import com.aliedu.kern.core.designsystem.components.KernOutlinedButton
import com.aliedu.kern.core.designsystem.components.KernPaletteSwatch
import com.aliedu.kern.core.designsystem.components.KernPullToRefresh
import com.aliedu.kern.core.designsystem.components.KernScaffold
import com.aliedu.kern.core.designsystem.components.KernSegmentedControl
import com.aliedu.kern.core.designsystem.components.KernSettingDivider
import com.aliedu.kern.core.designsystem.components.KernSettingRow
import com.aliedu.kern.core.designsystem.components.KernSettingsGroup
import com.aliedu.kern.core.designsystem.components.KernStatusCard
import com.aliedu.kern.core.designsystem.components.KernTextButton
import com.aliedu.kern.core.designsystem.components.KernTextField
import com.aliedu.kern.core.designsystem.components.KernTone
import com.aliedu.kern.core.designsystem.components.KernTopBar
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.core.designsystem.theme.KernElevation
import com.aliedu.kern.core.designsystem.theme.KernRadius
import com.aliedu.kern.core.designsystem.theme.KernSizes
import com.aliedu.kern.core.designsystem.theme.KernSpacing
import com.aliedu.kern.core.designsystem.theme.isDarkTheme
import com.aliedu.kern.core.designsystem.theme.palettePreviewColors
import com.aliedu.kern.core.model.ProviderProfile
import com.aliedu.kern.core.model.ProviderProtocol
import com.aliedu.kern.core.model.TerminalTheme
import com.aliedu.kern.core.model.ThemeMode
import com.aliedu.kern.core.model.ThemePalette

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    appVersion: String,
    buildType: String,
    packageName: String,
    onThemeModeChange: (ThemeMode) -> Unit,
    onThemePaletteChange: (ThemePalette) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onEditorFontSizeChange: (Float) -> Unit,
    onChatFontSizeChange: (Float) -> Unit,
    onTerminalFontSizeChange: (Float) -> Unit,
    onTerminalThemeChange: (TerminalTheme) -> Unit,
    onTerminalScrollbackChange: (Int) -> Unit,
    onShowReasoningChange: (Boolean) -> Unit,
    onAutoScrollChange: (Boolean) -> Unit,
    onAddProvider: () -> Unit,
    onEditProvider: (String) -> Unit,
    modelsSheet: ProviderModelsUiState,
    onOpenProviderModels: (String) -> Unit,
    onDismissModels: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSetDefaultProvider: (String) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onClearSessions: () -> Unit,
    onRefresh: () -> Unit
) {
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var confirmClearSessions by remember { mutableStateOf(false) }
    val dark = isDarkTheme(state.themeMode)

    KernScaffold(
        topBar = { KernTopBar(title = "Settings", subtitle = "Make Kern yours") }
    ) { padding ->
        KernPullToRefresh(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
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
            AppearanceSection(
                state = state,
                dark = dark,
                onThemeModeChange = onThemeModeChange,
                onThemePaletteChange = onThemePaletteChange,
                onDynamicColorChange = onDynamicColorChange
            )

            KernSettingsGroup(title = "Chat") {
                SliderSettingRow(
                    icon = KernIcons.TextSize,
                    title = "Chat text size",
                    valueLabel = "${state.chatFontSizeSp.toInt()} sp",
                    value = state.chatFontSizeSp,
                    onValueChange = onChatFontSizeChange
                )
                KernSettingDivider()
                SwitchSettingRow(
                    icon = KernIcons.Reasoning,
                    title = "Show reasoning",
                    subtitle = "Stream the model's chain of thought when the provider supports it.",
                    checked = state.showReasoning,
                    onCheckedChange = onShowReasoningChange
                )
                KernSettingDivider()
                SwitchSettingRow(
                    icon = KernIcons.ArrowDown,
                    title = "Auto-scroll",
                    subtitle = "Keep the newest message in view while the agent responds.",
                    checked = state.autoScroll,
                    onCheckedChange = onAutoScrollChange
                )
            }

            KernSettingsGroup(title = "Editor") {
                SliderSettingRow(
                    icon = KernIcons.Editor,
                    title = "Editor font size",
                    valueLabel = "${state.editorFontSizeSp.toInt()} sp",
                    value = state.editorFontSizeSp,
                    onValueChange = onEditorFontSizeChange
                )
            }

            KernSettingsGroup(title = "Terminal") {
                SliderSettingRow(
                    icon = KernIcons.TextSize,
                    title = "Terminal text size",
                    valueLabel = "${state.terminalFontSizeSp.toInt()} sp",
                    value = state.terminalFontSizeSp,
                    onValueChange = onTerminalFontSizeChange
                )
                KernSettingDivider()
                SliderSettingRow(
                    icon = KernIcons.History,
                    title = "Scrollback",
                    valueLabel = "${state.terminalScrollback} lines",
                    value = state.terminalScrollback.toFloat(),
                    valueRange = 200f..10_000f,
                    steps = 48,
                    onValueChange = { onTerminalScrollbackChange(it.toInt()) }
                )
                KernSettingDivider()
                TerminalThemeRow(
                    selected = state.terminalTheme,
                    onSelect = onTerminalThemeChange
                )
            }

            KernSettingsGroup(
                title = "Providers",
                footer = "Your API key is stored encrypted on this device and is never written to logs."
            ) {
                if (state.profiles.isEmpty()) {
                    KernInfoNote(
                        text = "No provider profiles yet. Add an OpenAI-compatible endpoint to use the agent.",
                        modifier = Modifier.padding(KernSpacing.md)
                    )
                } else {
                    Column(
                        modifier = Modifier.padding(KernSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(KernSpacing.sm)
                    ) {
                        state.profiles.forEach { profile ->
                            ProviderCard(
                                profile = profile,
                                isDefault = profile.id == state.defaultProfileId,
                                onClick = { onOpenProviderModels(profile.id) },
                                onSetDefault = { onSetDefaultProvider(profile.id) },
                                onDelete = { pendingDeleteId = profile.id }
                            )
                        }
                    }
                }
                KernSettingDivider()
                if (state.defaultProviderKeyMissing) {
                    KernInfoNote(
                        text = "The default provider's API key is missing on this device. Open the " +
                            "profile and re-enter the key before sending an AI request.",
                        tone = KernTone.WARNING,
                        modifier = Modifier.padding(horizontal = KernSpacing.md, vertical = KernSpacing.sm)
                    )
                }
                KernOptionCard(
                    icon = KernIcons.AddSession,
                    title = "Add provider profile",
                    detail = "Connect an OpenAI-compatible endpoint for the agent.",
                    onClick = onAddProvider,
                    modifier = Modifier.padding(KernSpacing.sm)
                )
            }

            KernSettingsGroup(title = "Data & storage") {
                KernSettingRow(
                    title = "Clear all chat sessions",
                    subtitle = if (state.sessionCount == 0) {
                        "No saved sessions on this device."
                    } else {
                        "${state.sessionCount} saved session(s) — messages and tool history included."
                    },
                    icon = KernIcons.Storage,
                    iconContainer = MaterialTheme.colorScheme.errorContainer,
                    iconTint = MaterialTheme.colorScheme.error,
                    trailing = {
                        KernTextButton(
                            onClick = { confirmClearSessions = true },
                            enabled = state.sessionCount > 0
                        ) {
                            Text("Clear")
                        }
                    }
                )
            }

            KernSettingsGroup(title = "Privacy") {
                Column(modifier = Modifier.padding(KernSpacing.md)) {
                    Text(
                        text = "Local-first",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Workspace files, sessions, and settings stay on this device. Context is " +
                            "sent to your configured provider only when you send an AI request — no Kern " +
                            "server, no analytics, no telemetry.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                KernSettingDivider()
                AboutRow("API keys", "Encrypted (Android Keystore)")
                KernSettingDivider()
                AboutRow("Data sent", "Only on explicit AI requests")
            }

            KernSettingsGroup(title = "About Kern") {
                Row(
                    modifier = Modifier.padding(KernSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KernSpacing.md)
                ) {
                    KernIconBadge(
                        icon = KernIcons.Sparkle,
                        container = MaterialTheme.colorScheme.primary,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        size = 44.dp
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(KernSpacing.xxs)) {
                        Text(
                            "Kern",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "A local-first AI coding agent for Android. Read, edit, and run — with " +
                                "your approval.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                KernSettingDivider()
                AboutRow("Version", appVersion)
                KernSettingDivider()
                AboutRow("Build", buildType)
                KernSettingDivider()
                AboutRow("Package", packageName)
                KernSettingDivider()
                AboutRow("Platform", "Android · Jetpack Compose")
                KernSettingDivider()
                AboutRow("Open-source", "Kotlin · Compose · Material 3 · Hilt · Room · OkHttp")
                KernSettingDivider()
                AboutRow("License", "Apache-2.0")
            }
            }
        }
    }

    val pendingProfile = state.profiles.firstOrNull { it.id == pendingDeleteId }
    if (pendingProfile != null) {
        KernConfirmDialog(
            title = "Delete provider?",
            message = "\"${pendingProfile.displayName}\" and its stored API key will be removed from " +
                "this device. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                onDeleteProvider(pendingProfile.id)
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null }
        )
    }

    if (confirmClearSessions) {
        KernConfirmDialog(
            title = "Clear all chat sessions?",
            message = "All ${state.sessionCount} saved session(s), their messages, and tool history " +
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

    if (modelsSheet.visible) {
        ProviderModelsSheet(
            state = modelsSheet,
            onDismiss = onDismissModels,
            onEdit = {
                val id = modelsSheet.profileId
                onDismissModels()
                if (id != null) onEditProvider(id)
            },
            onSelect = onSelectModel
        )
    }
}

/**
 * Bottom sheet listing every model the provider's `GET {BASE_URL}/models` endpoint reports. The
 * profile's current model is marked; tapping another one makes it the active model for that provider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderModelsSheet(
    state: ProviderModelsUiState,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onSelect: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KernSpacing.md)
                .padding(bottom = KernSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(KernSpacing.sm)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
            ) {
                KernIconBadge(icon = KernIcons.Provider, size = 36.dp, iconSize = KernSizes.iconSm)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.profileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            state.loading -> "Fetching models…"
                            state.models.isNotEmpty() -> "${state.models.size} model(s) available"
                            else -> "Models"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                KernTextButton(onClick = onEdit) {
                    Text("Edit", style = MaterialTheme.typography.labelSmall)
                }
            }

            when {
                state.loading -> Row(
                    modifier = Modifier.padding(vertical = KernSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(KernSizes.iconMd),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Loading models from the provider…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                state.error != null -> KernStatusCard(
                    title = "Could not load models",
                    detail = state.error,
                    tone = KernTone.ERROR
                )

                state.models.isEmpty() -> KernInfoNote(
                    text = "This endpoint reported no models. You can still set the model manually in " +
                        "the provider editor.",
                    modifier = Modifier.padding(vertical = KernSpacing.md)
                )

                else -> {
                    KernInfoNote(
                        text = "Tap a model to make it the active model for this provider.",
                        modifier = Modifier.padding(bottom = KernSpacing.xxs)
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(KernSpacing.xs)
                    ) {
                        items(state.models, key = { it.id }) { model ->
                            val current = model.id == state.currentModelId
                            Surface(
                                onClick = { onSelect(model.id) },
                                shape = KernRadius.mdShape,
                                color = if (current) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    KernElevation.hairline,
                                    if (current) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        horizontal = KernSpacing.md,
                                        vertical = KernSpacing.sm
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = model.id,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            color = if (current) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        model.ownedBy?.let {
                                            Text(
                                                text = it,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (current) {
                                        Icon(
                                            imageVector = KernIcons.Check,
                                            contentDescription = "Current model",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(KernSizes.iconMd)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Appearance — theme mode, named colour schemes, dynamic colour
// ---------------------------------------------------------------------------------------------

@Composable
private fun AppearanceSection(
    state: SettingsUiState,
    dark: Boolean,
    onThemeModeChange: (ThemeMode) -> Unit,
    onThemePaletteChange: (ThemePalette) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit
) {
    KernSettingsGroup(title = "Appearance") {
        Column(
            modifier = Modifier.padding(KernSpacing.md),
            verticalArrangement = Arrangement.spacedBy(KernSpacing.md)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(KernSpacing.xs)) {
                SettingLabel("Theme")
                KernSegmentedControl(
                    options = ThemeMode.entries,
                    selected = state.themeMode,
                    onSelect = onThemeModeChange,
                    label = { mode ->
                        when (mode) {
                            ThemeMode.SYSTEM -> "System"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                        }
                    }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(KernSpacing.sm)) {
                SettingLabel("Colour scheme")
                PaletteGrid(
                    selected = state.themePalette,
                    dark = dark,
                    onSelect = onThemePaletteChange
                )
                KernInfoNote(
                    text = "Named schemes keep the same flat, high-contrast feel and apply across " +
                        "the whole app, including the terminal when it follows the app theme."
                )
            }
        }
        KernSettingDivider()
        SwitchSettingRow(
            icon = KernIcons.Appearance,
            title = "Dynamic color",
            subtitle = "Match the system palette on Android 12+ (overrides the colour scheme).",
            checked = state.dynamicColor,
            onCheckedChange = onDynamicColorChange
        )
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun PaletteGrid(
    selected: ThemePalette,
    dark: Boolean,
    onSelect: (ThemePalette) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(KernSpacing.sm)) {
        ThemePalette.entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)) {
                row.forEach { palette ->
                    KernPaletteSwatch(
                        label = palette.displayName,
                        preview = remember(palette, dark) { palettePreviewColors(palette, dark) },
                        selected = palette == selected,
                        onClick = { onSelect(palette) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------------

@Composable
private fun SwitchSettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    KernSettingRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}

@Composable
private fun SliderSettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 10f..22f,
    steps: Int = 11
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KernSpacing.md, vertical = KernSpacing.sm)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.md)
        ) {
            KernIconBadge(icon = icon)
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
    }
}

@Composable
private fun TerminalThemeRow(selected: TerminalTheme, onSelect: (TerminalTheme) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        KernSettingRow(
            title = "Terminal theme",
            subtitle = if (selected == TerminalTheme.FOLLOW_APP) {
                "Follows the app's colour scheme."
            } else {
                selected.displayName
            },
            icon = KernIcons.Palette,
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KernSpacing.xxs)
                ) {
                    Text(
                        text = selected.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                    Icon(
                        imageVector = Icons.Outlined.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(KernSizes.iconMd)
                    )
                }
            },
            onClick = { expanded = true }
        )
        KernDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TerminalTheme.entries.forEach { theme ->
                KernDropdownItem(
                    title = theme.displayName,
                    subtitle = if (theme == TerminalTheme.FOLLOW_APP) "Match the app colour scheme" else null,
                    leading = KernIcons.Palette,
                    leadingTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    selected = theme == selected,
                    onClick = {
                        onSelect(theme)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KernSpacing.md, vertical = KernSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Providers
// ---------------------------------------------------------------------------------------------

/**
 * Compact provider card: a single row with the icon badge, the name/default badge, the model +
 * protocol on one line, and the row actions trailing. Kept to one line so a list of providers reads
 * as a dense settings group rather than a stack of tall panels.
 */
@Composable
private fun ProviderCard(
    profile: ProviderProfile,
    isDefault: Boolean,
    onClick: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = KernRadius.mdShape,
        color = if (isDefault) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            KernElevation.hairline,
            if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = KernSpacing.md, end = KernSpacing.xs, top = KernSpacing.sm, bottom = KernSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
        ) {
            KernIconBadge(
                icon = KernIcons.Provider,
                container = if (isDefault) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primaryContainer,
                tint = if (isDefault) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onPrimaryContainer,
                size = 36.dp,
                iconSize = KernSizes.iconSm
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(KernSpacing.xxs)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KernSpacing.xs)
                ) {
                    Text(
                        text = profile.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDefault) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isDefault) {
                        PillBadge(
                            text = "Default",
                            container = MaterialTheme.colorScheme.primary,
                            content = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KernSpacing.xs)
                ) {
                    Text(
                        text = profile.modelId,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (isDefault) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    PillBadge(
                        text = profile.protocol.displayName,
                        container = if (isDefault) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!isDefault) {
                KernTextButton(
                    onClick = onSetDefault,
                    contentPadding = PaddingValues(horizontal = KernSpacing.sm)
                ) {
                    Text("Set default", style = MaterialTheme.typography.labelSmall)
                }
            }
            KernTextButton(
                onClick = onDelete,
                contentPadding = PaddingValues(horizontal = KernSpacing.sm)
            ) {
                Text(
                    "Delete",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun PillBadge(text: String, container: Color, content: Color) {
    Surface(shape = KernRadius.pillShape, color = container) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = KernSpacing.sm, vertical = KernSpacing.xxs)
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Provider editor
// ---------------------------------------------------------------------------------------------

@Composable
fun ProviderEditScreen(
    profileId: String?,
    state: ProviderEditUiState,
    onLoad: (String?) -> Unit,
    onBack: () -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelIdChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onToggleApiKeyVisibility: () -> Unit,
    onProtocolChange: (ProviderProtocol) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit
) {
    LaunchedEffect(profileId) { onLoad(profileId) }
    var protocolMenu by remember { mutableStateOf(false) }

    KernScaffold(
        topBar = {
            KernTopBar(
                title = if (state.isNew) "New provider" else "Edit provider",
                subtitle = "OpenAI-compatible endpoint",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KernSpacing.screenH, vertical = KernSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(KernSpacing.md)
        ) {
            KernSettingsGroup(title = "Endpoint") {
                Column(
                    modifier = Modifier.padding(KernSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(KernSpacing.md)
                ) {
                    KernTextField(
                        value = state.displayName,
                        onValueChange = onDisplayNameChange,
                        label = "Provider name",
                        placeholder = "My provider",
                        leadingIcon = KernIcons.Provider,
                        isError = state.fieldErrors.containsKey("displayName"),
                        supportingText = state.fieldErrors["displayName"],
                        modifier = Modifier.fillMaxWidth()
                    )

                    Box {
                        Surface(
                            onClick = { protocolMenu = true },
                            shape = KernRadius.mdShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(KernSpacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Protocol",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        state.protocol.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Icon(
                                    Icons.Outlined.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(KernSizes.iconMd)
                                )
                            }
                        }
                        KernDropdownMenu(
                            expanded = protocolMenu,
                            onDismissRequest = { protocolMenu = false }
                        ) {
                            ProviderProtocol.entries.forEach { protocol ->
                                KernDropdownItem(
                                    title = protocol.displayName,
                                    selected = protocol == state.protocol,
                                    onClick = {
                                        onProtocolChange(protocol)
                                        protocolMenu = false
                                    }
                                )
                            }
                        }
                    }

                    KernTextField(
                        value = state.baseUrl,
                        onValueChange = onBaseUrlChange,
                        label = "Base URL",
                        placeholder = "https://host.example/v1",
                        leadingIcon = KernIcons.Connect,
                        isError = state.fieldErrors.containsKey("baseUrl"),
                        supportingText = state.fieldErrors["baseUrl"],
                        modifier = Modifier.fillMaxWidth()
                    )

                    KernTextField(
                        value = state.modelId,
                        onValueChange = onModelIdChange,
                        label = "Model ID",
                        placeholder = "model-id",
                        leadingIcon = KernIcons.Agent,
                        isError = state.fieldErrors.containsKey("modelId"),
                        supportingText = state.fieldErrors["modelId"],
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            KernSettingsGroup(
                title = "Credential",
                footer = "Stored encrypted with a device-protected key (Android Keystore). Never written " +
                    "to logs, backups, or exports."
            ) {
                Column(modifier = Modifier.padding(KernSpacing.md)) {
                    KernTextField(
                        value = state.apiKeyInput,
                        onValueChange = onApiKeyChange,
                        label = "API key",
                        placeholder = if (state.hasStoredKey) "•••••••• (stored)" else "Enter API key",
                        leadingIcon = KernIcons.Credential,
                        visualTransformation = if (state.showApiKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        isError = state.fieldErrors.containsKey("apiKey"),
                        supportingText = state.fieldErrors["apiKey"],
                        trailingIcon = {
                            KernIconButton(
                                icon = if (state.showApiKey) Icons.Outlined.VisibilityOff
                                else Icons.Outlined.Visibility,
                                contentDescription = if (state.showApiKey) "Hide API key"
                                else "Show API key",
                                onClick = onToggleApiKeyVisibility
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            KernStatusCard(
                title = "Request preview",
                detail = "${state.baseUrl.ifBlank { "<base-url>" }}/chat/completions  •  " +
                    "model ${state.modelId.ifBlank { "<model-id>" }}  •  key never shown",
                tone = KernTone.INFO,
                icon = KernIcons.Globe
            )

            state.testMessage?.let { message ->
                KernStatusCard(
                    title = if (state.testSuccess == true) "Connection succeeded" else "Connection failed",
                    detail = message,
                    tone = if (state.testSuccess == true) KernTone.SUCCESS else KernTone.ERROR
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm),
                modifier = Modifier.fillMaxWidth()
            ) {
                KernOutlinedButton(
                    onClick = onTest,
                    enabled = !state.testing && !state.saving,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = KernIcons.Connect,
                        contentDescription = null,
                        modifier = Modifier.size(KernSizes.iconSm)
                    )
                    Spacer(Modifier.width(KernSpacing.xs))
                    Text(if (state.testing) "Testing…" else "Test")
                }
                KernButton(
                    onClick = onSave,
                    enabled = !state.saving && !state.testing,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = KernIcons.Check,
                        contentDescription = null,
                        modifier = Modifier.size(KernSizes.iconSm)
                    )
                    Spacer(Modifier.width(KernSpacing.xs))
                    Text(if (state.saving) "Saving…" else "Save profile")
                }
            }
        }
    }
}
