package com.aliedu.kern.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.core.designsystem.theme.KernElevation
import com.aliedu.kern.core.designsystem.theme.KernRadius
import com.aliedu.kern.core.designsystem.theme.KernSizes
import com.aliedu.kern.core.designsystem.theme.KernSpacing

/**
 * The one dialog used across Kern. A flat card (no shadow — separation comes from the container
 * colour and a hairline border) with an optional icon badge, a centred title + message, an optional
 * content slot, and a stacked button column: the primary action is a full-width filled button and
 * the dismiss action sits beneath it as a quiet text button.
 *
 * Prefer this over the Material [androidx.compose.material3.AlertDialog] so every confirmation,
 * rename and prompt in the app looks identical.
 */
@Composable
fun KernDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    iconContainer: Color = MaterialTheme.colorScheme.primaryContainer,
    confirmLabel: String? = null,
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp),
            shape = KernRadius.lgShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = KernElevation.none,
            border = BorderStroke(KernElevation.hairline, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(KernSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(KernSpacing.md)
            ) {
                if (icon != null) {
                    KernIconBadge(
                        icon = icon,
                        container = iconContainer,
                        tint = iconTint,
                        size = 48.dp
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(KernSpacing.xs)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    if (!message.isNullOrBlank()) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                if (content != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(KernSpacing.sm),
                        content = content
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = KernSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(KernSpacing.xs)
                ) {
                    if (confirmLabel != null && onConfirm != null) {
                        KernButton(
                            onClick = onConfirm,
                            enabled = confirmEnabled,
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (destructive) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            } else {
                                ButtonDefaults.buttonColors()
                            }
                        ) {
                            Text(confirmLabel, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    KernTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text(dismissLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/**
 * Confirmation dialog for destructive or irreversible actions. A thin wrapper over [KernDialog] so
 * every "are you sure?" in the app is styled identically.
 */
@Composable
fun KernConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String = "Confirm",
    dismissLabel: String = "Cancel",
    destructive: Boolean = true,
    icon: ImageVector? = null
) {
    KernDialog(
        title = title,
        message = message,
        onDismiss = onDismiss,
        modifier = modifier,
        icon = icon ?: if (destructive) KernIcons.Delete else KernIcons.Check,
        iconTint = if (destructive) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onPrimaryContainer,
        iconContainer = if (destructive) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.primaryContainer,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        dismissLabel = dismissLabel,
        destructive = destructive
    )
}

/**
 * Single-field prompt dialog (rename, create, enter a value). Validates on every keystroke via
 * [validate] — returning a non-null string shows it as the field error and disables the primary
 * action, so the caller never has to re-check the value.
 */
@Composable
fun KernTextInputDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialValue: String = "",
    message: String? = null,
    label: String = "Name",
    placeholder: String? = null,
    icon: ImageVector? = null,
    confirmLabel: String = "Save",
    dismissLabel: String = "Cancel",
    validate: ((String) -> String?)? = null
) {
    var value by remember { mutableStateOf(initialValue) }
    val error = validate?.invoke(value)
    val canConfirm = value.isNotBlank() && error == null
    val focusRequester = remember { FocusRequester() }

    KernDialog(
        title = title,
        message = message,
        onDismiss = onDismiss,
        modifier = modifier,
        icon = icon,
        confirmLabel = confirmLabel,
        confirmEnabled = canConfirm,
        onConfirm = { onConfirm(value.trim()) },
        dismissLabel = dismissLabel
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            label = { Text(label) },
            placeholder = placeholder?.let { hint -> { Text(hint) } },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { msg -> { Text(msg) } },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { if (canConfirm) onConfirm(value.trim()) })
        )
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/**
 * Rounded, flat badge holding a single icon. The shared visual anchor for dialogs, option cards and
 * status cards — keeps icon containers the same size and corner radius everywhere.
 */
@Composable
fun KernIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    tint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    size: Dp = 40.dp,
    iconSize: Dp = KernSizes.iconMd,
    shape: Shape = KernRadius.mdShape
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

