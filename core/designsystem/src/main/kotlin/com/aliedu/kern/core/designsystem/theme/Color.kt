package com.aliedu.kern.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * Fixed state colours that every scheme shares (PRD 6.3). Everything else — primary, surface,
 * outline, the surface containers — is generated per palette in [buildColorScheme], so adding a
 * scheme never touches this file.
 *
 * PRD 6.3 requires state to never be conveyed by colour alone, so these are always paired with a
 * label and an icon.
 */

// Light
val LightError = Color(0xFFDC2626)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFEE2E2)
val LightOnErrorContainer = Color(0xFF7F1D1D)
val LightSuccess = Color(0xFF059669)
val LightWarning = Color(0xFFD97706)

// Dark
val DarkError = Color(0xFFF87171)
val DarkOnError = Color(0xFF450A0A)
val DarkErrorContainer = Color(0xFF7F1D1D)
val DarkOnErrorContainer = Color(0xFFFEE2E2)
val DarkSuccess = Color(0xFF34D399)
val DarkWarning = Color(0xFFFBBF24)
