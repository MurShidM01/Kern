package com.aliedu.kern.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.aliedu.kern.core.model.ThemePalette
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Verifies the generated colour schemes stay legible for every palette, in both variants. */
class PalettesTest {

    private fun ratio(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    @Test
    fun everyPaletteBuildsLegibleLightAndDarkSchemes() {
        ThemePalette.entries.forEach { palette ->
            val light = buildColorScheme(palette, dark = false)
            val dark = buildColorScheme(palette, dark = true)

            // Light canvases must be lighter than dark ones.
            assertThat(light.background.luminance()).isGreaterThan(dark.background.luminance())
            assertThat(light.surface.luminance()).isGreaterThan(dark.surface.luminance())

            // Body text and accent text must clear WCAG AA / large-text thresholds.
            assertThat(ratio(light.onSurface, light.surface)).isAtLeast(4.5f)
            assertThat(ratio(dark.onSurface, dark.surface)).isAtLeast(4.5f)
            assertThat(ratio(light.onPrimary, light.primary)).isAtLeast(3.0f)
            assertThat(ratio(dark.onPrimary, dark.primary)).isAtLeast(3.0f)
            assertThat(ratio(light.onSurfaceVariant, light.surfaceVariant)).isAtLeast(3.0f)
            assertThat(ratio(dark.onSurfaceVariant, dark.surfaceVariant)).isAtLeast(3.0f)
            assertThat(ratio(light.onPrimaryContainer, light.primaryContainer)).isAtLeast(4.5f)
            assertThat(ratio(dark.onPrimaryContainer, dark.primaryContainer)).isAtLeast(4.5f)
        }
    }

    @Test
    fun palettesProduceDistinctAccents() {
        val primaries = ThemePalette.entries.map { buildColorScheme(it, dark = false).primary }
        assertThat(primaries.toSet()).hasSize(ThemePalette.entries.size)
    }

    @Test
    fun semanticColorsTrackTheSchemeSurface() {
        val scheme = buildColorScheme(ThemePalette.OCEAN, dark = true)
        val semantic = buildSemanticColors(scheme, dark = true)
        assertThat(semantic.codeBackground).isEqualTo(scheme.surfaceVariant)
        assertThat(semantic.codeText).isEqualTo(scheme.onSurface)
    }
}
