package com.aliedu.kern.feature.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.aliedu.kern.core.designsystem.components.KernClickableSurface
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.core.designsystem.theme.KernRadius
import com.aliedu.kern.core.designsystem.theme.KernSizes
import com.aliedu.kern.core.designsystem.theme.KernSpacing

/**
 * Row of keys the soft keyboard cannot produce. CTRL/ALT are sticky: arming one applies it to the next
 * character typed in the input line (PRD 12.4).
 */
@Composable
fun TerminalKeyRow(
    ctrlArmed: Boolean,
    altArmed: Boolean,
    onKey: (TerminalKey) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleKeyboard: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.padding(horizontal = KernSpacing.screenH, vertical = KernSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyButton("ESC") { onKey(TerminalKey.ESC) }
            KeyButton("TAB") { onKey(TerminalKey.TAB) }
            KeyButton("CTRL", armed = ctrlArmed, onClick = onToggleCtrl)
            KeyButton("ALT", armed = altArmed, onClick = onToggleAlt)
            KeyIcon(KernIcons.ArrowLeft, "Left arrow") { onKey(TerminalKey.LEFT) }
            KeyIcon(KernIcons.ArrowUp, "Up arrow") { onKey(TerminalKey.UP) }
            KeyIcon(KernIcons.ArrowDown, "Down arrow") { onKey(TerminalKey.DOWN) }
            KeyIcon(KernIcons.ArrowRight, "Right arrow") { onKey(TerminalKey.RIGHT) }
            KeyButton("^C") { onKey(TerminalKey.CTRL_C) }
            KeyButton("^D") { onKey(TerminalKey.CTRL_D) }
            KeyButton("^L") { onKey(TerminalKey.CTRL_L) }
        }
        if (onToggleKeyboard != null) {
            Spacer(Modifier.width(KernSpacing.xs))
            KeyIcon(KernIcons.Keyboard, "Show or hide keyboard", onToggleKeyboard)
        }
    }
}

@Composable
private fun KeyButton(label: String, armed: Boolean = false, onClick: () -> Unit) {
    KernClickableSurface(
        onClick = onClick,
        shape = KernRadius.smShape,
        color = if (armed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (armed) FontWeight.SemiBold else FontWeight.Normal,
            color = if (armed) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = KernSpacing.sm, vertical = KernSpacing.sm)
        )
    }
}

@Composable
private fun KeyIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    KernClickableSurface(
        onClick = onClick,
        shape = KernRadius.smShape,
        color = MaterialTheme.colorScheme.surface
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(horizontal = KernSpacing.sm, vertical = KernSpacing.xs)
                .size(KernSizes.iconMd)
        )
    }
}
