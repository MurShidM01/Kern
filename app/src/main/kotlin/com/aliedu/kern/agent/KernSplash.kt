package com.aliedu.kern.agent

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.core.designsystem.theme.KernSpacing

/**
 * The cold-start screen: no artwork, just the mark, the name and a progress line, all drawn from the
 * active colour scheme so it matches whichever palette the user picked (and dynamic colour when it
 * is on).
 *
 * It is deliberately plain: a flat surface, one accent tile with a soft glow, tight type. The
 * window background is the same surface colour, so the system splash hands over without a flash,
 * and the caller keeps this on screen until the app actually knows which screen to show.
 */
@Composable
fun KernSplash(modifier: Modifier = Modifier) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    // Only the mark animates. Fading the whole screen in meant that a fast cold start — which is
    // the normal case here — showed a blank wash for the entire life of the splash.
    val markScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.92f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f),
        label = "splashScale"
    )

    val scheme = MaterialTheme.colorScheme

    Box(modifier = modifier.fillMaxSize().background(scheme.surface)) {
        // A single soft wash behind the mark: enough to lift it off the surface, not a gradient
        // across the whole screen.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(300.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(scheme.primary.copy(alpha = 0.08f), Color.Transparent)
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = KernSpacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // A small, flat tile: the mark reads as a logo, not as artwork filling the screen.
            Box(
                modifier = Modifier
                    .scale(markScale)
                    .size(56.dp)
                    .shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(17.dp),
                        ambientColor = scheme.primary,
                        spotColor = scheme.primary
                    )
                    .clip(RoundedCornerShape(17.dp))
                    .background(scheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = KernIcons.Sparkle,
                    contentDescription = null,
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(Modifier.height(KernSpacing.lg))

            Text(
                text = "Kern",
                style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-0.3).sp),
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface
            )
            Spacer(Modifier.height(KernSpacing.xxs))
            Text(
                text = "Local-first AI coding agent",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(KernSpacing.xl))

            // The splash is doing real work (the release check), so it says so with a progress bar
            // instead of looking like a still image.
            LinearProgressIndicator(
                modifier = Modifier
                    .width(132.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = scheme.primary,
                trackColor = scheme.surfaceContainerHigh
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Edge-to-edge: without this the version line and the progress bar sit under the
                // gesture bar and disappear.
                .navigationBarsPadding()
                .padding(bottom = KernSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "v" + BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
