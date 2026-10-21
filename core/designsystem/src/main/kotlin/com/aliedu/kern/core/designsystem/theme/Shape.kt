package com.aliedu.kern.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner-radius tokens. Kern uses a restrained, slightly-rounded geometry — surfaces are flat
 * (see [KernElevation]) and separated by hairlines rather than shadows.
 */
object KernRadius {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp

    /** Soft card/composer corners (chat surfaces). */
    val xl = 22.dp

    /** Notch chips that sit in a surface's top border. */
    val chip = 10.dp

    val pill = 999.dp

    val xsShape = RoundedCornerShape(xs)
    val smShape = RoundedCornerShape(sm)
    val mdShape = RoundedCornerShape(md)
    val lgShape = RoundedCornerShape(lg)
    val xlShape = RoundedCornerShape(xl)
    val chipShape = RoundedCornerShape(chip)
    val pillShape = RoundedCornerShape(pill)
}

/** Wired into [androidx.compose.material3.MaterialTheme.shapes]. */
val KernShapes = Shapes(
    extraSmall = KernRadius.xsShape,
    small = KernRadius.smShape,
    medium = KernRadius.mdShape,
    large = KernRadius.lgShape,
    extraLarge = KernRadius.lgShape
)

/**
 * Elevation tokens. Kern deliberately avoids shadows: every surface sits flat on the background and
 * uses a hairline border or divider for separation. Keeping the tokens explicit means "no
 * elevation" is a rule, not an accident.
 */
object KernElevation {
    val none = 0.dp
    val flat = 0.dp

    /** Hairline used for borders and dividers. */
    val hairline = 1.dp
}
