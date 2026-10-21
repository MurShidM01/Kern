package com.aliedu.kern.core.designsystem.theme

import androidx.compose.ui.unit.dp

/** Spacing scale (PRD 6.4). Compact steps for a dense, consistent layout. */
object KernSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp

    /** Horizontal gutter used by screen content and list rows. */
    val screenH = 12.dp

    /** Minimum touch target where practical (PRD 6.4). */
    val minTouchTarget = 48.dp
}

/**
 * Fixed component sizes. Kept here so no composable hard-codes a magic number; every screen pulls
 * the same metrics, which is what keeps the bar heights and icon sizes identical across the app.
 */
object KernSizes {
    /** Compact top app bar content height (excludes the status-bar inset it paints under). */
    val topBarHeight = 44.dp

    /** Bottom navigation bar content height (excludes the navigation-bar inset). */
    val bottomBarHeight = 52.dp

    val iconXs = 12.dp
    val iconSm = 14.dp
    val iconMd = 18.dp
    val iconLg = 22.dp

    /** Dense toolbar icon button — still comfortably tappable. */
    val iconButton = 36.dp

    /** Fixed height of a segmented-control bar (matches the Material 3 spec). */
    val segmentHeight = 40.dp

    /** Floating composer input pill. */
    val composerMinHeight = 40.dp
    val composerMaxHeight = 140.dp

    /** Height of the fade scrim that sits above the floating composer. */
    val composerScrim = 20.dp

    /** Height of the chips (permission / tasks) that straddle the composer's top border. */
    val composerChipHeight = 28.dp

    /** Compact circular send/stop button sitting at the composer's trailing edge. */
    val composerSendButton = 36.dp
}
