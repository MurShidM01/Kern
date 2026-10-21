package com.aliedu.kern.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aliedu.kern.core.tools.preview.PreviewController
import com.aliedu.kern.feature.preview.WebViewPreviewHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device regression checks for the preview WebView.
 *
 * The preview loaded pages — title, console and `preview_eval` all worked — but a real page painted
 * nothing. The cause was the viewport settings: with `useWideViewPort`/`loadWithOverviewMode` a page
 * that does not declare `<meta name="viewport">` (a hand-written `index.html` usually does not) got
 * a wide layout viewport scaled down to almost nothing. Both pages below must paint.
 *
 * Each page is a solid red body hosted through the same Compose `AndroidView` path the preview uses
 * and captured through [WebViewPreviewHost.screenshot], so this also covers the capture path.
 */
@RunWith(AndroidJUnit4::class)
class PreviewWebViewRenderTest {

    @Test
    fun pageWithViewportMetaPaints() {
        val ratio = redRatio(render(page(viewportMeta = true)))
        println("PREVIEW_RENDER meta redRatio=$ratio")
        assertTrue("the page with a viewport meta did not paint (red ratio $ratio)", ratio > 0.25)
    }

    /** The regression: no `<meta name="viewport">` used to collapse to a blank surface. */
    @Test
    fun pageWithoutViewportMetaPaints() {
        val ratio = redRatio(render(page(viewportMeta = false)))
        println("PREVIEW_RENDER nometa redRatio=$ratio")
        assertTrue("the page without a viewport meta did not paint (red ratio $ratio)", ratio > 0.25)
    }

    private fun page(viewportMeta: Boolean): String {
        val meta = if (viewportMeta) {
            """<meta name="viewport" content="width=device-width, initial-scale=1">"""
        } else {
            ""
        }
        return """
            <!doctype html>
            <html><head>$meta</head>
            <body style="margin:0;background:#ff0000"><h1 style="color:#ffffff">KERN RENDER</h1></body>
            </html>
        """.trimIndent()
    }

    /** Loads [html] through the real host and returns the captured bitmap. */
    private fun render(html: String): Bitmap {
        val url = "data:text/html;base64," + Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var host: WebViewPreviewHost
            scenario.onActivity { activity ->
                host = WebViewPreviewHost(activity, PreviewController())
                activity.setContent {
                    AndroidView(factory = { host.view }, modifier = Modifier.fillMaxSize())
                }
            }
            runBlocking { host.load(url) }
            awaitPage(host)

            val png = runBlocking { host.screenshot() } ?: error("screenshot returned nothing")
            return BitmapFactory.decodeByteArray(png, 0, png.size)
                ?: error("screenshot did not decode")
        }
    }

    private fun awaitPage(host: WebViewPreviewHost) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            var progress = 0
            instrumentation.runOnMainSync { progress = host.view.progress }
            if (progress >= 100) break
            Thread.sleep(100)
        }
        instrumentation.waitForIdleSync()
        Thread.sleep(500)
    }

    private fun redRatio(bitmap: Bitmap): Double {
        var red = 0
        var sampled = 0
        for (y in 0 until bitmap.height step 4) {
            for (x in 0 until bitmap.width step 4) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r > 180 && g < 90 && b < 90) red++
                sampled++
            }
        }
        println("PREVIEW_RENDER ${bitmap.width}x${bitmap.height}")
        return if (sampled == 0) 0.0 else red.toDouble() / sampled
    }
}
