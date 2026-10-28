package com.aliedu.kern.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

/** One published release, as far as the update check cares about it. */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val downloadUrl: String?,
    val pageUrl: String
)

/**
 * Checks GitHub for a newer release and installs it.
 *
 * The check is deliberately small and dependency-free (a plain HTTPS GET plus `org.json`): it is
 * the only network call in Settings, and it must not drag the provider stack into this module. The
 * download goes to the app's own cache and is handed to the package installer through a
 * [FileProvider], so no storage permission is involved.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * App-wide background work for the update flow. It lives here rather than in a ViewModel
     * because the cold-start splash starts the check before any screen exists, and the result has
     * to still be there when Settings opens.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(UpdateUiState())

    /** Shared update state: started by the splash, read and driven by Settings → About Kern. */
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /**
     * Asks GitHub for the newest release and compares it with [currentVersion], publishing the
     * result on [state]. Concurrent calls collapse into the running check.
     */
    suspend fun check(currentVersion: String) {
        if (_state.value.checking || _state.value.downloading) return
        _state.value = UpdateUiState(checking = true)
        val result = latest()
        _state.value = result.fold(
            onSuccess = { info ->
                if (isNewerVersion(info.version, currentVersion)) {
                    UpdateUiState(info = info)
                } else {
                    UpdateUiState(message = "Kern is up to date (v" + currentVersion + ").")
                }
            },
            onFailure = { error ->
                UpdateUiState(message = error.message ?: "Could not reach the release feed.")
            }
        )
    }

    /** Fire-and-forget [check], for callers that must not block (the cold-start splash). */
    fun checkInBackground(currentVersion: String) {
        scope.launch { check(currentVersion) }
    }

    /** Downloads the offered APK, then hands it to the system installer. */
    fun downloadUpdate() {
        val info = _state.value.info ?: return
        if (_state.value.downloading) return
        _state.value = _state.value.copy(downloading = true, progress = 0f, message = null)
        scope.launch {
            val result = download(info) { fraction ->
                _state.value = _state.value.copy(progress = fraction)
            }
            result.fold(
                onSuccess = { apk ->
                    _state.value = _state.value.copy(downloading = false, progress = 1f)
                    install(apk).onFailure {
                        // No installer available (rare): at least open the release page.
                        openReleasePage(info.pageUrl)
                    }
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        downloading = false,
                        message = error.message ?: "The download failed."
                    )
                }
            )
        }
    }

    /** Opens the release page for the release currently being offered, if any. */
    fun openReleasePageForCurrent() {
        _state.value.info?.let { openReleasePage(it.pageUrl) }
    }

    /** Hides the update dialog without touching the result of the check. */
    fun dismiss() {
        _state.value = _state.value.copy(info = null, message = null)
    }

    /**
     * The newest published release, or a failure describing why it could not be read.
     *
     * This deliberately avoids api.github.com. The REST API allows 60 unauthenticated requests an
     * hour per IP, and on a mobile carrier NAT that budget is shared with every other device behind
     * the same address — the check would fail with 403 exactly when it is most useful. The public
     * release pages have no such limit: `releases/latest` answers with a 302 to the tag, and that
     * tag's asset list is a small HTML fragment. Both are read here.
     */
    suspend fun latest(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val tag = latestTag()
            UpdateInfo(
                version = tag.removePrefix("v").removePrefix("V"),
                notes = "",
                downloadUrl = latestApk(tag),
                pageUrl = RELEASE_PAGE + tag
            )
        }
    }

    /** Follows the `releases/latest` redirect and returns its tag, e.g. `v1.0.2+261029`. */
    private fun latestTag(): String {
        val connection = (URL(LATEST_RELEASE_PAGE).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "Kern-Android")
        }
        val location = try {
            if (connection.responseCode !in 300..399) {
                error("GitHub returned HTTP " + connection.responseCode + ".")
            }
            connection.getHeaderField("Location")
        } finally {
            connection.disconnect()
        }
        val encoded = location?.substringAfterLast("/tag/", "").orEmpty()
        if (encoded.isBlank()) error("The release feed did not name a version.")
        // GitHub sends the tag as it is, so a literal '+' in "v1.0.2+261029" must survive: decode
        // percent escapes only, never '+' as a space.
        return runCatching {
            URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(encoded)
    }

    /**
     * The APK asset for [tag], from the release's asset-list fragment. Prefers arm64 (the ABI of
     * every device that can run the Linux environment), then a universal build, then anything.
     */
    private fun latestApk(tag: String): String? {
        val html = runCatching { get(EXPANDED_ASSETS + Uri.encode(tag)) }.getOrNull() ?: return null
        val paths = APK_HREF.findAll(html).map { it.groupValues[1] }.toList()
        if (paths.isEmpty()) return null
        val best = paths.firstOrNull { it.contains("arm64", ignoreCase = true) }
            ?: paths.firstOrNull { it.contains("universal", ignoreCase = true) }
            ?: paths.first()
        return "https://github.com" + best
    }

    /** Downloads the release APK into the app cache, reporting 0..1 progress. */
    suspend fun download(
        info: UpdateInfo,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val url = info.downloadUrl ?: error("This release has no APK attached yet.")
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "kern-" + info.version + ".apk")
            if (target.exists()) target.delete()
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/octet-stream")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    error("The download failed (HTTP " + connection.responseCode + ").")
                }
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        var reported = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            read += count
                            if (total > 0 && read - reported > total / 100) {
                                reported = read
                                onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            onProgress(1f)
            target
        }
    }

    /** Hands a downloaded APK to the system installer. */
    fun install(apk: File): Result<Unit> = runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /** Fallback when there is no APK asset: open the release page in the browser. */
    fun openReleasePage(url: String): Result<Unit> = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "text/html")
            setRequestProperty("User-Agent", "Kern-Android")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                error("GitHub returned HTTP " + connection.responseCode + ".")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val LATEST_RELEASE_PAGE = "https://github.com/MurShidM01/Kern/releases/latest"
        private const val RELEASE_PAGE = "https://github.com/MurShidM01/Kern/releases/tag/"
        private const val EXPANDED_ASSETS = "https://github.com/MurShidM01/Kern/releases/expanded_assets/"
        private const val APK_MIME = "application/vnd.android.package-archive"

        /** Asset links in the fragment, e.g. `/MurShidM01/Kern/releases/download/v1.0.2/app-arm64-v8a-release.apk`. */
        private val APK_HREF = Regex("href=\"(/MurShidM01/Kern/releases/download/[^\"]*?\\.apk)\"")
    }
}

/**
 * True when [candidate] is a strictly newer dotted version than [current]. Non-numeric suffixes
 * (`1.0.3-beta`) compare on their numeric head only, which is what the release tags use.
 */
fun isNewerVersion(candidate: String, current: String): Boolean {
    // Build metadata after '+' (the release date in Kern's tags) is not part of version
    // precedence, so "1.0.2+261029" must compare equal to "1.0.2" and not read as a fourth
    // component of 261029.
    fun parts(value: String) = value.trim()
        .removePrefix("v")
        .substringBefore('+')
        .split('.', '-')
        .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    val a = parts(candidate)
    val b = parts(current)
    for (index in 0 until maxOf(a.size, b.size)) {
        val left = a.getOrElse(index) { 0 }
        val right = b.getOrElse(index) { 0 }
        if (left != right) return left > right
    }
    return false
}
