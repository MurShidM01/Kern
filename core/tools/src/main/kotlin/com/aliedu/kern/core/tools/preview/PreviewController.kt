package com.aliedu.kern.core.tools.preview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Console severity, mirroring the browser devtools levels. */
enum class ConsoleLevel { LOG, INFO, WARN, ERROR, DEBUG }

/** One captured console line or uncaught error (the "F12 console", PRD §4.3 bullet 8). */
data class ConsoleEntry(
    val id: Long,
    val level: ConsoleLevel,
    val message: String,
    /** The script URL the line came from, when the browser reported one. */
    val source: String?,
    val line: Int,
    val timestampMs: Long
)

/** What the preview is showing right now. */
data class PreviewState(
    val url: String? = null,
    val title: String? = null,
    val loading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val error: String? = null,
    /** False until the preview screen is on screen and has handed us a live WebView. */
    val attached: Boolean = false
)

/**
 * The WebView, as seen by the rest of the app. Implemented by the preview feature module, which owns
 * the Android view and marshals every call onto the main thread.
 *
 * Deliberately `suspend` and free of Android types: this controller lives in `:core:tools`, which
 * must not depend on `kotlinx-coroutines-android` or the framework.
 */
interface PreviewHost {
    suspend fun load(url: String)
    suspend fun reload()
    suspend fun goBack()
    suspend fun goForward()

    /** Runs [js] in the page and returns its result, already unquoted. Null when unavailable. */
    suspend fun evaluate(js: String): String?

    /** PNG bytes of the visible viewport, or null when it cannot be captured. */
    suspend fun screenshot(): ByteArray?
}

/**
 * Shared preview state: the URL being shown, and the console log captured from the page.
 *
 * A `@Singleton` because the agent's preview tools and the preview screen must see the same
 * WebView: the agent can be mid-`preview_eval` while the screen is open. When no screen is attached
 * the controller still records the requested URL, so the screen picks it up on open.
 */
@Singleton
class PreviewController @Inject constructor() {

    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private val _console = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    val console: StateFlow<List<ConsoleEntry>> = _console.asStateFlow()

    /**
     * Bumped when the agent asks for the preview to be shown. The app observes it and navigates, so
     * "open the preview" actually opens it rather than only setting a URL the user must go find.
     */
    private val _openSignal = MutableStateFlow(0L)
    val openSignal: StateFlow<Long> = _openSignal.asStateFlow()

    private val nextId = AtomicLong(0)
    private var host: PreviewHost? = null

    /** Attach the live WebView, or pass null when the preview screen goes away. */
    fun attach(host: PreviewHost?) {
        this.host = host
        _state.value = _state.value.copy(attached = host != null)
    }

    /**
     * Point the preview at [url]. Loads immediately when a screen is attached; otherwise the URL is
     * remembered and loaded when the screen next opens.
     */
    suspend fun open(url: String) {
        _state.value = _state.value.copy(url = url, error = null)
        host?.load(url)
    }

    /** Raise a request for the app to navigate to the preview screen. */
    fun requestOpen() {
        _openSignal.value = _openSignal.value + 1
    }

    suspend fun reload() = host?.reload()

    suspend fun goBack() = host?.goBack()

    suspend fun goForward() = host?.goForward()

    suspend fun evaluate(js: String): String? = host?.evaluate(js)

    suspend fun screenshot(): ByteArray? = host?.screenshot()

    /** True when a page can actually be driven right now. */
    val isAttached: Boolean get() = host != null

    fun clearConsole() {
        _console.value = emptyList()
    }

    // ---- Callbacks from the WebView (main thread; state-only writes) ----

    fun onPageStarted(url: String) {
        _state.value = _state.value.copy(
            url = url,
            loading = true,
            progress = 0,
            error = null
        )
    }

    fun onPageFinished(url: String, title: String?) {
        _state.value = _state.value.copy(
            url = url,
            title = title?.takeIf { it.isNotBlank() } ?: _state.value.title,
            loading = false,
            progress = 100
        )
    }

    fun onProgress(progress: Int) {
        _state.value = _state.value.copy(progress = progress)
    }

    fun onTitle(title: String?) {
        if (title.isNullOrBlank()) return
        _state.value = _state.value.copy(title = title)
    }

    fun onHistoryChanged(canGoBack: Boolean, canGoForward: Boolean) {
        _state.value = _state.value.copy(canGoBack = canGoBack, canGoForward = canGoForward)
    }

    fun onError(message: String) {
        _state.value = _state.value.copy(loading = false, error = message)
        onConsole(ConsoleLevel.ERROR, message, source = null, line = 0)
    }

    fun onConsole(level: ConsoleLevel, message: String, source: String?, line: Int) {
        val entry = ConsoleEntry(
            id = nextId.incrementAndGet(),
            level = level,
            message = message,
            source = source,
            line = line,
            timestampMs = System.currentTimeMillis()
        )
        // Bounded ring: a chatty page can log thousands of lines and the agent reads the tail.
        val current = _console.value
        _console.value = if (current.size >= MAX_ENTRIES) {
            current.subList(current.size - MAX_ENTRIES + 1, current.size) + entry
        } else {
            current + entry
        }
    }

    companion object {
        /** How many console entries to retain. */
        const val MAX_ENTRIES = 500
    }
}
