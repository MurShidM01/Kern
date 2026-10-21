package com.aliedu.kern.feature.preview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.aliedu.kern.core.tools.preview.ConsoleLevel
import com.aliedu.kern.core.tools.preview.PreviewController
import com.aliedu.kern.core.tools.preview.PreviewHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * The real WebView behind the preview (PRD §4.3 bullet 8).
 *
 * Owns one [WebView] and implements [PreviewHost], which is the only surface `:core:tools` knows
 * about — so the agent's `preview_*` tools drive exactly the view the user is looking at. Every
 * host call hops to the main thread because the agent runs on an IO dispatcher.
 *
 * Two deliberate restrictions:
 *  * **Navigation is locked to the loopback dev server.** Anything else is handed to the system
 *    browser. This keeps the page inside the app's sandbox where the console bridge is exposed,
 *    instead of handing a `@JavascriptInterface` to an arbitrary remote site.
 *  * **`addJavascriptInterface` carries only the console bridge** — a single `report` method, no
 *    file or storage access.
 */
class WebViewPreviewHost(
    private val context: Context,
    private val controller: PreviewController
) : PreviewHost {

    val view: WebView = WebView(context)

    private val main = Dispatchers.Main.immediate

    init {
        // JavaScript is required: a preview of a dev server without it shows nothing.
        view.settings.apply {
            @Suppress("SetJavaScriptEnabled")
            javaScriptEnabled = true
            domStorageEnabled = true
            // Dev servers are not laid out for a phone: let the user pinch to read them.
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // Do NOT use the wide viewport. With `useWideViewPort`/`loadWithOverviewMode` a page
            // that does not declare `<meta name="viewport">` — a hand-written `index.html` usually
            // does not — gets a 980px layout viewport scaled down to almost nothing, so the preview
            // looks blank even though the page loaded and runs. Laying out at the device width
            // renders both kinds of page correctly; pinch-zoom still handles desktop-width pages.
            useWideViewPort = false
            loadWithOverviewMode = false
            // The preview only ever serves loopback HTTP, so the app's own files are not needed —
            // and leaving them reachable from page scripts would be a needless hole.
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // Lets a dev server detect the preview and serve a mobile-friendly build.
            userAgentString = "$userAgentString KernPreview/1.0"
        }

        // chrome://inspect on a developer machine can drive the same WebView. Debug builds only.
        if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        view.addJavascriptInterface(ConsoleBridge(), ConsoleInjection.BRIDGE_NAME)
        view.webViewClient = PreviewClient()
        view.webChromeClient = PreviewChromeClient()

        // An opaque background, like a browser: a page that sets no background of its own should
        // read as white, not as the app's theme colour showing through the WebView.
        view.setBackgroundColor(android.graphics.Color.WHITE)
    }

    /** Receives the console lines the native callback cannot see. Runs on the JavaBridge thread. */
    private inner class ConsoleBridge {
        @JavascriptInterface
        fun report(level: String, message: String, source: String, line: Int) {
            if (message.isBlank()) return
            controller.onConsole(
                level = if (level == "error") ConsoleLevel.ERROR else ConsoleLevel.LOG,
                message = message,
                source = source.takeIf { it.isNotBlank() },
                line = line
            )
        }
    }

    private inner class PreviewClient : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (url != null) controller.onPageStarted(url)
            injectConsoleHook()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            // Re-injected because a page can replace its own handlers after the first pass.
            injectConsoleHook()
            controller.onPageFinished(url.orEmpty(), view.title)
            controller.onHistoryChanged(view.canGoBack(), view.canGoForward())
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            controller.onHistoryChanged(view.canGoBack(), view.canGoForward())
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (!request.isForMainFrame) return
            controller.onError(error.description?.toString() ?: "The page could not be loaded.")
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            if (!request.isForMainFrame) return
            controller.onError("The server replied ${errorResponse.statusCode} for ${request.url}.")
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (isPreviewable(uri)) return false
            openExternally(uri)
            return true
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: android.net.http.SslError
        ) {
            // A dev server commonly uses a self-signed certificate. The origin lock means this can
            // only ever be the loopback server, so proceeding is not a remote-MITM risk — but it is
            // worth saying out loud in the console.
            if (isLoopbackUrl(error.url)) {
                controller.onConsole(
                    level = ConsoleLevel.WARN,
                    message = "Accepted a self-signed certificate for ${error.url}.",
                    source = null,
                    line = 0
                )
                handler.proceed()
            } else {
                handler.cancel()
                controller.onError("The certificate for ${error.url} is not trusted.")
            }
        }
    }

    private inner class PreviewChromeClient : WebChromeClient() {

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            controller.onConsole(
                level = when (message.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> ConsoleLevel.ERROR
                    ConsoleMessage.MessageLevel.WARNING -> ConsoleLevel.WARN
                    ConsoleMessage.MessageLevel.DEBUG -> ConsoleLevel.DEBUG
                    else -> ConsoleLevel.LOG
                },
                message = message.message(),
                source = message.sourceId()?.takeIf { it.isNotBlank() },
                line = message.lineNumber()
            )
            return true
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            controller.onProgress(newProgress)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            controller.onTitle(title)
        }
    }

    private fun injectConsoleHook() {
        view.evaluateJavascript(ConsoleInjection.SCRIPT, null)
    }

    // ---- PreviewHost (called from the agent's dispatchers) ----

    override suspend fun load(url: String) = withContext(main) {
        controller.onPageStarted(url)
        view.loadUrl(url)
    }

    override suspend fun reload() = withContext(main) {
        view.reload()
    }

    override suspend fun goBack() = withContext(main) {
        if (view.canGoBack()) view.goBack()
    }

    override suspend fun goForward() = withContext(main) {
        if (view.canGoForward()) view.goForward()
    }

    override suspend fun evaluate(js: String): String? = withContext(main) {
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(js) { raw -> continuation.resume(unquote(raw)) }
        }
    }

    override suspend fun screenshot(): ByteArray? = withContext(main) {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return@withContext null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // PixelCopy reads what the window actually composited, which is the only way to capture a
        // WebView: `View.draw()` re-runs the software draw path and a hardware-rendered page comes
        // out empty. Fall back to draw() only when the window cannot be copied.
        if (!copyFromWindow(bitmap)) view.draw(Canvas(bitmap))
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    /**
     * Copies the WebView's on-screen pixels into [bitmap]. Returns false when the view is not
     * attached or the copy failed, so the caller can fall back to drawing the view directly.
     */
    private suspend fun copyFromWindow(bitmap: Bitmap): Boolean {
        if (!view.isAttachedToWindow) return false
        val window = context.findActivity()?.window ?: return false
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val source = Rect(
            location[0],
            location[1],
            location[0] + view.width,
            location[1] + view.height
        )
        return suspendCancellableCoroutine { continuation ->
            try {
                PixelCopy.request(
                    window,
                    source,
                    bitmap,
                    { result -> continuation.resume(result == PixelCopy.SUCCESS) },
                    Handler(Looper.getMainLooper())
                )
            } catch (_: IllegalArgumentException) {
                // The view sits partly outside the window, or the window is already gone.
                continuation.resume(false)
            }
        }
    }

    /** Unwraps Compose's `ContextThemeWrapper` chain to the Activity that owns the window. */
    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current != null) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }

    /** Releases the WebView. Must run on the main thread, which is where composition disposes. */
    @SuppressLint("WebViewApiAvailability")
    fun destroy() {
        runCatching {
            view.stopLoading()
            view.webChromeClient = null
            view.removeJavascriptInterface(ConsoleInjection.BRIDGE_NAME)
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
        }
    }

    fun pause() {
        runCatching { view.onPause() }
    }

    fun resume() {
        runCatching { view.onResume() }
    }

    private fun openExternally(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Nothing can open $uri", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /** Schemes a previewed page may use for sub-resources and in-page navigations. */
        private val INLINE_SCHEMES = setOf("about", "data", "blob", "javascript")

        /** True when [uri] belongs to the local dev server the preview exists to show. */
        fun isPreviewable(uri: Uri): Boolean = isPreviewableUrl(uri.scheme, uri.host)

        /**
         * The rule behind the navigation lock: an inline scheme, or an `http(s)` URL on the loopback
         * interface. Everything else is handed to the system browser, so the console bridge is never
         * exposed to a remote page.
         */
        internal fun isPreviewableUrl(scheme: String?, host: String?): Boolean {
            val name = scheme?.lowercase() ?: return false
            if (name in INLINE_SCHEMES) return true
            if (name != "http" && name != "https") return false
            return isLoopback(host.orEmpty())
        }

        private fun isLoopbackUrl(url: String?): Boolean =
            isLoopback(Uri.parse(url.orEmpty()).host.orEmpty())

        /** `10.0.2.2` is the host machine as seen from the Android emulator. */
        internal fun isLoopback(host: String): Boolean {
            val name = host.lowercase().removeSurrounding("[", "]")
            return name == "localhost" ||
                name == "127.0.0.1" ||
                name == "::1" ||
                name == "0.0.0.0" ||
                name == "10.0.2.2"
        }

        /**
         * Turns the JSON value `evaluateJavascript` returns into a plain string: a quoted JSON
         * string is decoded, anything else (number, object, array) is passed through as its JSON
         * text, and `null` becomes null.
         *
         * Hand-rolled rather than `org.json` so it is a pure function that unit tests can exercise.
         */
        internal fun unquote(raw: String?): String? {
            if (raw == null || raw == "null") return null
            if (raw.length < 2 || raw.first() != '"' || raw.last() != '"') return raw
            return unescapeJsonString(raw.substring(1, raw.length - 1))
        }

        private fun unescapeJsonString(body: String): String {
            if ('\\' !in body) return body
            val out = StringBuilder(body.length)
            var i = 0
            while (i < body.length) {
                val char = body[i]
                if (char != '\\' || i + 1 >= body.length) {
                    out.append(char)
                    i++
                    continue
                }
                when (val escape = body[i + 1]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        val code = body.substring(i + 2, minOf(i + 6, body.length)).toIntOrNull(16)
                        if (code != null && i + 6 <= body.length) {
                            out.append(code.toChar())
                            i += 6
                            continue
                        }
                        out.append(escape)
                    }
                    else -> out.append(escape)
                }
                i += 2
            }
            return out.toString()
        }
    }
}
