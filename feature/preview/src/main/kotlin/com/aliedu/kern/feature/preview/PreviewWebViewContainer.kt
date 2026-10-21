package com.aliedu.kern.feature.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.aliedu.kern.core.tools.preview.PreviewController

/**
 * Hosts the preview's WebView and registers it with [controller], which is what lets the agent's
 * `preview_*` tools reach the exact view on screen.
 *
 * The host is remembered per [LocalContext] and torn down when the screen leaves composition —
 * a WebView leaks its renderer process otherwise.
 */
@Composable
fun PreviewWebViewContainer(
    controller: PreviewController,
    url: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val host = remember(context, controller) { WebViewPreviewHost(context, controller) }

    DisposableEffect(host) {
        controller.attach(host)
        onDispose {
            controller.attach(null)
            host.destroy()
        }
    }

    // A page keeps running JS in the background otherwise, which costs battery for a preview the
    // user cannot even see.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { host.resume() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { host.pause() }

    // The screen may open after the agent already asked for a URL, so load whatever is pending.
    // The URL comparison keeps the agent's own `preview_open` from loading the page twice.
    LaunchedEffect(host, url) {
        if (url != null && host.view.url != url) host.load(url)
    }

    AndroidView(factory = { host.view }, modifier = modifier)
}
