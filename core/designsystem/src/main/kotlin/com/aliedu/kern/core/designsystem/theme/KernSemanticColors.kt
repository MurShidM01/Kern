package com.aliedu.kern.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colors Material 3 does not model directly (success/warning/code). PRD 6.3 requires
 * state to never be conveyed by color alone, so these are always paired with a label + icon.
 */
@Immutable
data class KernSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val codeBackground: Color,
    val codeText: Color,
    val diffAdd: Color,
    val diffRemove: Color
)

internal val LightSemanticColors = KernSemanticColors(
    success = LightSuccess,
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFD1FAE5),
    warning = LightWarning,
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFEF3C7),
    codeBackground = Color(0xFFF1F5F9),
    codeText = Color(0xFF0F172A),
    diffAdd = Color(0xFFDCFCE7),
    diffRemove = Color(0xFFFEE2E2)
)

internal val DarkSemanticColors = KernSemanticColors(
    success = DarkSuccess,
    onSuccess = Color(0xFF00391F),
    successContainer = Color(0xFF064E3B),
    warning = DarkWarning,
    onWarning = Color(0xFF3A2A00),
    warningContainer = Color(0xFF78350F),
    codeBackground = Color(0xFF1E293B),
    codeText = Color(0xFFE2E8F0),
    diffAdd = Color(0xFF14301F),
    diffRemove = Color(0xFF3A1D1D)
)

val LocalKernSemanticColors = staticCompositionLocalOf { LightSemanticColors }
