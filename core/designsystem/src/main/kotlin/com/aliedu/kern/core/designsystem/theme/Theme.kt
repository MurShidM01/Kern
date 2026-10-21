package com.aliedu.kern.core.designsystem.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.aliedu.kern.core.model.ThemeMode
import com.aliedu.kern.core.model.ThemePalette

/**
 * Resolves [ThemeMode.SYSTEM] against the device setting; LIGHT/DARK are explicit user choices
 * and never overridden by the system (PRD 6.2).
 */
@Composable
fun isDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Applies the Kern design system. The colour scheme comes from either the system palette (when
 * [dynamicColor] is on, Android 12+) or the user's chosen [palette]; either way the flat surface,
 * type scale and shapes are the same, so every scheme looks like Kern.
 */
@Composable
fun KernTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    palette: ThemePalette = ThemePalette.INDIGO,
    content: @Composable () -> Unit
) {
    val darkTheme = isDarkTheme(themeMode)
    val context = LocalContext.current

    val colorScheme = remember(darkTheme, dynamicColor, palette) {
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            buildColorScheme(palette, darkTheme)
        }
    }
    val semanticColors = remember(colorScheme, darkTheme) {
        buildSemanticColors(colorScheme, darkTheme)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Edge-to-edge: bars are transparent and the app paints beneath them, so the app bar
            // and the system edge always share one continuous colour (the activity enables
            // edge-to-edge; here we only keep the bar icon appearance in sync with the theme).
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalKernSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = KernTypography,
            shapes = KernShapes,
            content = content
        )
    }
}
