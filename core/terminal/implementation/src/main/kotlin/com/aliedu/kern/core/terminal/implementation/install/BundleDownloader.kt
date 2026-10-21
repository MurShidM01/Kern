package com.aliedu.kern.core.terminal.implementation.install

import com.aliedu.kern.core.terminal.implementation.di.TerminalHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads one bundle component with progress, HTTP Range resume, bounded retries and mandatory
 * checksum verification (PRD 12.4). The partial file is kept between attempts so a retry resumes.
 */
@Singleton
class BundleDownloader @Inject constructor(
    @TerminalHttpClient private val client: OkHttpClient,
    private val checksum: ChecksumVerifier
) {

    suspend fun download(
        component: BundleComponent,
        target: File,
        onProgress: (downloaded: Long, total: Long?) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val partial = File(target.parentFile, target.name + PART_SUFFIX)
        target.parentFile?.mkdirs()

        var lastError: Throwable? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            try {
                transfer(component, partial, onProgress)
                if (!checksum.verify(partial, component.sha256)) {
                    partial.delete()
                    return@withContext Result.failure(
                        IOException("Checksum mismatch for '${component.id}'.")
                    )
                }
                if (target.exists()) target.delete()
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                return@withContext Result.success(Unit)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                lastError = t
                if (attempt < MAX_ATTEMPTS - 1) delay(BACKOFF_MS * (attempt + 1))
            }
        }
        Result.failure(lastError ?: IOException("Download failed for '${component.id}'."))
    }

    private suspend fun transfer(
        component: BundleComponent,
        partial: File,
        onProgress: (Long, Long?) -> Unit
    ) {
        val existing = if (partial.exists()) partial.length() else 0L
        val request = Request.Builder()
            .url(component.url)
            .header("Accept-Encoding", "identity")
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 416 && existing > 0) {
                // Stale partial — start over.
                partial.delete()
                throw IOException("Range not satisfiable; retrying from scratch.")
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for '${component.id}'.")

            val resuming = response.code == 206 && existing > 0
            if (!resuming && existing > 0) partial.delete()

            val start = if (resuming) existing else 0L
            val body = response.body ?: throw IOException("Empty response body.")
            val total = component.sizeBytes
                ?: body.contentLength().takeIf { it >= 0 }?.let { it + start }

            var downloaded = start
            var lastEmit = 0L
            FileOutputStream(partial, resuming).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (!currentCoroutineContext().isActive) throw CancellationException("Download cancelled.")
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= PROGRESS_THROTTLE_MS || downloaded == total) {
                            lastEmit = now
                            onProgress(downloaded, total)
                        }
                    }
                }
                out.flush()
            }
        }
    }

    private companion object {
        const val PART_SUFFIX = ".part"
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MS = 600L
        const val PROGRESS_THROTTLE_MS = 120L
    }
}
