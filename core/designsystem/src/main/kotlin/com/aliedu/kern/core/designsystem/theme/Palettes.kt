package com.aliedu.kern.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.aliedu.kern.core.model.ThemePalette

/**
 * Kern's named colour schemes.
 *
 * Every scheme pairs an **accent family** with one of a few **neutral scales**, so all schemes keep the
 * same flat, high-contrast developer-tool feel while looking clearly different. Colours are
 * Tailwind-derived steps (the palette the app has always used), which keeps the result crisp rather
 * than washed out. Adding a scheme is one entry in [accents] plus one line in [ThemePalette].
 */
private class Accent(
    val lightPrimary: Long,
    val darkPrimary: Long,
    val lightContainer: Long,
    val darkContainer: Long,
    val lightOnContainer: Long,
    val darkOnContainer: Long,
    val lightTertiary: Long,
    val darkTertiary: Long
)

private class Neutrals(
    val lightBg: Long,
    val lightSurface: Long,
    val lightVariant: Long,
    val lightInk: Long,
    val lightMuted: Long,
    val lightOutline: Long,
    val lightOutlineVariant: Long,
    val darkBg: Long,
    val darkSurface: Long,
    val darkVariant: Long,
    val darkInk: Long,
    val darkMuted: Long,
    val darkOutline: Long,
    val darkOutlineVariant: Long
)

private val Slate = Neutrals(
    lightBg = 0xFFF8FAFC, lightSurface = 0xFFFFFFFF, lightVariant = 0xFFF1F5F9,
    lightInk = 0xFF0F172A, lightMuted = 0xFF64748B, lightOutline = 0xFF94A3B8, lightOutlineVariant = 0xFFE2E8F0,
    darkBg = 0xFF0B1120, darkSurface = 0xFF0F172A, darkVariant = 0xFF1E293B,
    darkInk = 0xFFE2E8F0, darkMuted = 0xFF94A3B8, darkOutline = 0xFF475569, darkOutlineVariant = 0xFF334155
)

private val Stone = Neutrals(
    lightBg = 0xFFFAFAF9, lightSurface = 0xFFFFFFFF, lightVariant = 0xFFF5F5F4,
    lightInk = 0xFF1C1917, lightMuted = 0xFF78716C, lightOutline = 0xFFA8A29E, lightOutlineVariant = 0xFFE7E5E4,
    darkBg = 0xFF0C0A09, darkSurface = 0xFF1C1917, darkVariant = 0xFF292524,
    darkInk = 0xFFE7E5E4, darkMuted = 0xFFA8A29E, darkOutline = 0xFF57534E, darkOutlineVariant = 0xFF44403C
)

private val Zinc = Neutrals(
    lightBg = 0xFFFAFAFA, lightSurface = 0xFFFFFFFF, lightVariant = 0xFFF4F4F5,
    lightInk = 0xFF18181B, lightMuted = 0xFF71717A, lightOutline = 0xFFA1A1AA, lightOutlineVariant = 0xFFE4E4E7,
    darkBg = 0xFF09090B, darkSurface = 0xFF18181B, darkVariant = 0xFF27272A,
    darkInk = 0xFFE4E4E7, darkMuted = 0xFFA1A1AA, darkOutline = 0xFF52525B, darkOutlineVariant = 0xFF3F3F46
)

private val Gray = Neutrals(
    lightBg = 0xFFF9FAFB, lightSurface = 0xFFFFFFFF, lightVariant = 0xFFF3F4F6,
    lightInk = 0xFF111827, lightMuted = 0xFF6B7280, lightOutline = 0xFF9CA3AF, lightOutlineVariant = 0xFFE5E7EB,
    darkBg = 0xFF030712, darkSurface = 0xFF111827, darkVariant = 0xFF1F2937,
    darkInk = 0xFFE5E7EB, darkMuted = 0xFF9CA3AF, darkOutline = 0xFF4B5563, darkOutlineVariant = 0xFF374151
)

private val accents: Map<ThemePalette, Pair<Accent, Neutrals>> = mapOf(
    ThemePalette.INDIGO to (Accent(
        0xFF4F46E5, 0xFF818CF8, 0xFFE0E7FF, 0xFF312E81, 0xFF312E81, 0xFFE0E7FF, 0xFF0284C7, 0xFF38BDF8
    ) to Slate),
    ThemePalette.OCEAN to (Accent(
        0xFF0284C7, 0xFF38BDF8, 0xFFE0F2FE, 0xFF0C4A6E, 0xFF0C4A6E, 0xFFE0F2FE, 0xFF0891B2, 0xFF22D3EE
    ) to Slate),
    ThemePalette.TIDE to (Accent(
        0xFF0D9488, 0xFF2DD4BF, 0xFFCCFBF1, 0xFF134E4A, 0xFF134E4A, 0xFFCCFBF1, 0xFF0284C7, 0xFF38BDF8
    ) to Slate),
    ThemePalette.FOREST to (Accent(
        0xFF15803D, 0xFF4ADE80, 0xFFDCFCE7, 0xFF14532D, 0xFF14532D, 0xFFDCFCE7, 0xFF0D9488, 0xFF2DD4BF
    ) to Stone),
    ThemePalette.EMERALD to (Accent(
        0xFF059669, 0xFF34D399, 0xFFD1FAE5, 0xFF064E3B, 0xFF064E3B, 0xFFD1FAE5, 0xFF0891B2, 0xFF22D3EE
    ) to Gray),
    ThemePalette.DESERT to (Accent(
        0xFFB45309, 0xFFFBBF24, 0xFFFEF3C7, 0xFF78350F, 0xFF78350F, 0xFFFEF3C7, 0xFFEA580C, 0xFFFB923C
    ) to Stone),
    ThemePalette.SAND to (Accent(
        0xFFCA8A04, 0xFFFACC15, 0xFFFEF9C3, 0xFF713F12, 0xFF713F12, 0xFFFEF9C3, 0xFFD97706, 0xFFFBBF24
    ) to Stone),
    ThemePalette.SUNSET to (Accent(
        0xFFEA580C, 0xFFFB923C, 0xFFFFEDD5, 0xFF7C2D12, 0xFF7C2D12, 0xFFFFEDD5, 0xFFE11D48, 0xFFFB7185
    ) to Stone),
    ThemePalette.ROSE to (Accent(
        0xFFE11D48, 0xFFFB7185, 0xFFFFE4E6, 0xFF881337, 0xFF881337, 0xFFFFE4E6, 0xFFDB2777, 0xFFF472B6
    ) to Zinc),
    ThemePalette.CORAL to (Accent(
        0xFFEF4444, 0xFFF87171, 0xFFFEE2E2, 0xFF7F1D1D, 0xFF7F1D1D, 0xFFFEE2E2, 0xFFF97316, 0xFFFB923C
    ) to Zinc),
    ThemePalette.GRAPHITE to (Accent(
        0xFF3F3F46, 0xFFD4D4D8, 0xFFE4E4E7, 0xFF27272A, 0xFF18181B, 0xFFF4F4F5, 0xFF71717A, 0xFFA1A1AA
    ) to Zinc)
)

/** Build the Material 3 colour scheme for [palette] in the requested light/dark variant. */
fun buildColorScheme(palette: ThemePalette, dark: Boolean): ColorScheme {
    val (accent, neutral) = accents[palette] ?: accents.getValue(ThemePalette.INDIGO)
    return if (dark) darkScheme(accent, neutral) else lightScheme(accent, neutral)
}

/**
 * Four representative colours of [palette] (primary, primary container, tertiary, background) for
 * the Settings appearance swatch grid — a preview of the scheme rather than the full scheme.
 */
fun palettePreviewColors(palette: ThemePalette, dark: Boolean): List<Color> {
    val scheme = buildColorScheme(palette, dark)
    return listOf(scheme.primary, scheme.primaryContainer, scheme.tertiary, scheme.background)
}

private fun lightScheme(accent: Accent, neutral: Neutrals): ColorScheme {
    val primary = accent.lightPrimary.argb()
    val tertiary = accent.lightTertiary.argb()
    val surface = neutral.lightSurface.argb()
    val ink = neutral.lightInk.argb()
    return lightColorScheme(
        primary = primary,
        onPrimary = inkFor(primary),
        primaryContainer = accent.lightContainer.argb(),
        onPrimaryContainer = accent.lightOnContainer.argb(),
        inversePrimary = accent.darkPrimary.argb(),
        secondary = neutral.lightMuted.argb(),
        onSecondary = Color.White,
        secondaryContainer = neutral.lightVariant.argb(),
        onSecondaryContainer = neutral.lightInk.argb(),
        tertiary = tertiary,
        onTertiary = inkFor(tertiary),
        tertiaryContainer = blend(tertiary, Color.White, 0.84f),
        onTertiaryContainer = blend(tertiary, Color.Black, 0.42f),
        background = neutral.lightBg.argb(),
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = neutral.lightVariant.argb(),
        onSurfaceVariant = neutral.lightMuted.argb(),
        surfaceTint = primary,
        inverseSurface = ink,
        inverseOnSurface = surface,
        outline = neutral.lightOutline.argb(),
        outlineVariant = neutral.lightOutlineVariant.argb(),
        scrim = Color.Black,
        error = LightError,
        onError = LightOnError,
        errorContainer = LightErrorContainer,
        onErrorContainer = LightOnErrorContainer,
        surfaceDim = step(surface, ink, 0.05f),
        surfaceBright = surface,
        surfaceContainerLowest = surface,
        surfaceContainerLow = step(surface, ink, 0.04f),
        surfaceContainer = step(surface, ink, 0.07f),
        surfaceContainerHigh = step(surface, ink, 0.10f),
        surfaceContainerHighest = step(surface, ink, 0.13f)
    )
}

private fun darkScheme(accent: Accent, neutral: Neutrals): ColorScheme {
    val primary = accent.darkPrimary.argb()
    val tertiary = accent.darkTertiary.argb()
    val surface = neutral.darkSurface.argb()
    val ink = neutral.darkInk.argb()
    return darkColorScheme(
        primary = primary,
        onPrimary = inkFor(primary),
        primaryContainer = accent.darkContainer.argb(),
        onPrimaryContainer = accent.darkOnContainer.argb(),
        inversePrimary = accent.lightPrimary.argb(),
        secondary = neutral.darkMuted.argb(),
        onSecondary = neutral.darkSurface.argb(),
        secondaryContainer = neutral.darkVariant.argb(),
        onSecondaryContainer = neutral.darkInk.argb(),
        tertiary = tertiary,
        onTertiary = inkFor(tertiary),
        tertiaryContainer = blend(tertiary, Color.Black, 0.66f),
        onTertiaryContainer = blend(tertiary, Color.White, 0.80f),
        background = neutral.darkBg.argb(),
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = neutral.darkVariant.argb(),
        onSurfaceVariant = neutral.darkMuted.argb(),
        surfaceTint = primary,
        inverseSurface = ink,
        inverseOnSurface = surface,
        outline = neutral.darkOutline.argb(),
        outlineVariant = neutral.darkOutlineVariant.argb(),
        scrim = Color.Black,
        error = DarkError,
        onError = DarkOnError,
        errorContainer = DarkErrorContainer,
        onErrorContainer = DarkOnErrorContainer,
        surfaceDim = step(surface, ink, 0.03f),
        surfaceBright = step(surface, ink, 0.22f),
        surfaceContainerLowest = step(surface, ink, 0.00f),
        surfaceContainerLow = step(surface, ink, 0.05f),
        surfaceContainer = step(surface, ink, 0.09f),
        surfaceContainerHigh = step(surface, ink, 0.13f),
        surfaceContainerHighest = step(surface, ink, 0.17f)
    )
}

/** Semantic colours (success/warning/code) for a scheme; state is never conveyed by colour alone. */
fun buildSemanticColors(scheme: ColorScheme, dark: Boolean): KernSemanticColors {
    val base = if (dark) DarkSemanticColors else LightSemanticColors
    return base.copy(codeBackground = scheme.surfaceVariant, codeText = scheme.onSurface)
}

private fun Long.argb(): Color = Color(toInt())

/** Ink tones used on filled surfaces; the more legible one is chosen per colour. */
private val DarkInk = Color(0xFF0F172A)
private val LightInk = Color(0xFFFFFFFF)

/** Pick the ink (near-black or white) that contrasts more with [background]. */
private fun inkFor(background: Color): Color =
    if (contrast(LightInk, background) >= contrast(DarkInk, background)) LightInk else DarkInk

/** WCAG contrast ratio between two opaque colours. */
private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** Linear sRGB blend between [a] and [b] by fraction [t]. */
private fun blend(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f
)

/** One tonal step from [surface] toward [ink] — used for the M3 surface-container roles. */
private fun step(surface: Color, ink: Color, t: Float): Color = blend(surface, ink, t)
