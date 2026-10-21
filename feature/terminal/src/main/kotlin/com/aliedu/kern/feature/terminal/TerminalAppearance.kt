package com.aliedu.kern.feature.terminal

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.aliedu.kern.core.designsystem.theme.LocalKernSemanticColors
import com.aliedu.kern.core.model.TerminalTheme

/**
 * Fully resolved terminal colours. [ansi] holds the 16 base entries (indices 0-15); the 256-colour
 * cube and truecolour values are derived from them by [TerminalPalette].
 */
data class TerminalAppearance(
    val background: Color,
    val foreground: Color,
    val ansi: List<Color>,
    val cursor: Color,
    val selection: Color,
    val dark: Boolean
)

private class NamedScheme(val background: Long, val foreground: Long, val ansi: List<Long>)

private val TangoDark = NamedScheme(
    0xFF2E3436, 0xFFEEEEEC,
    listOf(
        0xFF2E3436, 0xFFCC0000, 0xFF4E9A06, 0xFFC4A000,
        0xFF3465A4, 0xFF75507B, 0xFF06989A, 0xFFD3D7CF,
        0xFF555753, 0xFFEF2929, 0xFF8AE234, 0xFFFCE94F,
        0xFF729FCF, 0xFFAD7FA8, 0xFF34E2E2, 0xFFEEEEEC
    )
)

private val TangoLight = NamedScheme(
    0xFFFFFFFF, 0xFF111111,
    listOf(
        0xFF000000, 0xFFC91B00, 0xFF00A000, 0xFFA68A00,
        0xFF1B6ACB, 0xFFA000A0, 0xFF008080, 0xFF555555,
        0xFF666666, 0xFFD50000, 0xFF008700, 0xFF8A6D00,
        0xFF0043A0, 0xFF8A008A, 0xFF006A6A, 0xFF111111
    )
)

private val Dracula = NamedScheme(
    0xFF282A36, 0xFFF8F8F2,
    listOf(
        0xFF21222C, 0xFFFF5555, 0xFF50FA7B, 0xFFF1FA8C,
        0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF8F8F2,
        0xFF6272A4, 0xFFFF6E6E, 0xFF69FF94, 0xFFFFFFA5,
        0xFFD6ACFF, 0xFFFF92DF, 0xFFA4FFFF, 0xFFFFFFFF
    )
)

private val Nord = NamedScheme(
    0xFF2E3440, 0xFFD8DEE9,
    listOf(
        0xFF3B4252, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
        0xFF81A1C1, 0xFFB48EAD, 0xFF88C0D0, 0xFFE5E9F0,
        0xFF4C566A, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
        0xFF81A1C1, 0xFFB48EAD, 0xFF8FBCBB, 0xFFECEFF4
    )
)

private val SolarizedDark = NamedScheme(
    0xFF002B36, 0xFF839496,
    listOf(
        0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900,
        0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
        0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83,
        0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3
    )
)

private val Gruvbox = NamedScheme(
    0xFF282828, 0xFFEBDBB2,
    listOf(
        0xFF282828, 0xFFCC241D, 0xFF98971A, 0xFFD79921,
        0xFF458588, 0xFFB16286, 0xFF689D6A, 0xFFA89984,
        0xFF928374, 0xFFFB4934, 0xFFB8BB26, 0xFFFABD2F,
        0xFF83A598, 0xFFD3869B, 0xFF8EC07C, 0xFFEBDBB2
    )
)

private val namedSchemes: Map<TerminalTheme, NamedScheme> = mapOf(
    TerminalTheme.TANGO_DARK to TangoDark,
    TerminalTheme.TANGO_LIGHT to TangoLight,
    TerminalTheme.DRACULA to Dracula,
    TerminalTheme.NORD to Nord,
    TerminalTheme.SOLARIZED_DARK to SolarizedDark,
    TerminalTheme.GRUVBOX to Gruvbox
)

/**
 * Resolve the terminal colours for [theme]. [TerminalTheme.FOLLOW_APP] derives its background and
 * foreground from the app's code surface so the terminal blends with whatever colour scheme the user
 * picked in Settings; the named schemes are fixed, well-known terminal palettes.
 */
@Composable
fun terminalAppearance(theme: TerminalTheme): TerminalAppearance {
    val semantic = LocalKernSemanticColors.current
    val primary = MaterialTheme.colorScheme.primary
    val appBackground = semantic.codeBackground
    val appForeground = semantic.codeText

    return remember(theme, appBackground, appForeground, primary) {
        if (theme == TerminalTheme.FOLLOW_APP) {
            TerminalAppearance(
                background = appBackground,
                foreground = appForeground,
                ansi = TerminalPalette.baseFor(appBackground),
                cursor = primary.copy(alpha = 0.40f),
                selection = primary.copy(alpha = 0.28f),
                dark = appBackground.luminance() <= 0.5f
            )
        } else {
            val scheme = namedSchemes[theme] ?: TangoDark
            val background = Color(scheme.background)
            val foreground = Color(scheme.foreground)
            TerminalAppearance(
                background = background,
                foreground = foreground,
                ansi = scheme.ansi.map { Color(it) },
                cursor = foreground.copy(alpha = 0.40f),
                selection = foreground.copy(alpha = 0.22f),
                dark = background.luminance() <= 0.5f
            )
        }
    }
}
