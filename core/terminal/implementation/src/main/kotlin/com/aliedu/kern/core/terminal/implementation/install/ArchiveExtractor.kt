package com.aliedu.kern.core.terminal.implementation.install

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import javax.inject.Inject
import javax.inject.Singleton

/** Compression formats the catalog can point at. */
object ArchiveFormat {
    const val TAR_GZ = "tar.gz"
    const val TAR_XZ = "tar.xz"
    const val NONE = "none"

    fun isSupported(format: String): Boolean = format == TAR_GZ || format == TAR_XZ || format == NONE
}

/**
 * Extracts a tar.gz / tar.xz rootfs into a target directory, preserving directories, symlinks,
 * hard links and the executable bit, and rejecting any entry that would escape the target (path
 * traversal). Uses Apache Commons Compress so it works without a system tar binary.
 *
 * AnLinux ships most distributions as tar.xz, so both compressors are supported; the format comes
 * from the catalog component rather than the file name, so a redirect cannot change how it is read.
 */
@Singleton
class ArchiveExtractor @Inject constructor() {

    /**
     * @param onProgress called with the running total of **uncompressed** bytes written, roughly every
     * [PROGRESS_STEP_BYTES]. Unpacking a 358 MB rootfs takes about a minute on a phone, so the caller
     * needs a real figure to show instead of a stalled 0 B.
     */
    fun extract(
        archive: File,
        targetDir: File,
        format: String,
        onProgress: (Long) -> Unit = {}
    ): Long {
        val input: InputStream = BufferedInputStream(archive.inputStream())
        val decompressed: InputStream = when (format) {
            ArchiveFormat.TAR_GZ -> GzipCompressorInputStream(input)
            ArchiveFormat.TAR_XZ -> XZCompressorInputStream(input)
            else -> throw IOException("Unsupported archive format '" + format + "'.")
        }
        return extractTar(TarArchiveInputStream(decompressed), targetDir, onProgress)
    }

    fun extractTarGz(archive: File, targetDir: File) =
        extract(archive, targetDir, ArchiveFormat.TAR_GZ)

    /** Returns the uncompressed bytes written. */
    private fun extractTar(
        tar: TarArchiveInputStream,
        targetDir: File,
        onProgress: (Long) -> Unit
    ): Long {
        if (!targetDir.exists()) targetDir.mkdirs()
        val targetRoot = targetDir.canonicalFile
        var written = 0L
        var reported = 0L

        tar.use {
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val relative = entry.name.removePrefix("./").trimEnd('/')
                if (relative.isNotEmpty()) {
                    val outFile = File(targetDir, relative)
                    if (!isWithin(targetRoot, outFile)) {
                        throw IOException("Blocked archive entry outside target: " + entry.name)
                    }
                    when {
                        entry.isDirectory -> outFile.mkdirs()
                        entry.isSymbolicLink -> {
                            outFile.parentFile?.mkdirs()
                            createSymlink(entry.linkName, outFile)
                        }
                        entry.isLink -> {
                            outFile.parentFile?.mkdirs()
                            createHardLink(File(targetDir, entry.linkName), outFile)
                        }
                        // Device nodes and FIFOs cannot exist in app storage; skipping them keeps the
                        // extraction from writing a bogus regular file where the guest expects one.
                        entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> Unit
                        else -> {
                            outFile.parentFile?.mkdirs()
                            val copied = FileOutputStream(outFile).use { output -> tar.copyTo(output) }
                            applyMode(outFile, entry.mode)
                            written += copied
                            if (written - reported >= PROGRESS_STEP_BYTES) {
                                reported = written
                                onProgress(written)
                            }
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
        return written
    }

    private fun applyMode(file: File, mode: Int) {
        if (mode and OWNER_EXECUTE != 0) file.setExecutable(true, false)
    }

    private fun createSymlink(linkTarget: String, link: File) {
        runCatching {
            Files.deleteIfExists(link.toPath())
            Files.createSymbolicLink(link.toPath(), Paths.get(linkTarget))
        }
    }

    private fun createHardLink(existing: File, link: File) {
        runCatching { Files.deleteIfExists(link.toPath()); Files.createLink(link.toPath(), existing.toPath()) }
            .onFailure { runCatching { existing.copyTo(link, overwrite = true) } }
    }

    private fun isWithin(root: File, file: File): Boolean {
        val rootPath = root.canonicalPath
        val filePath = file.canonicalPath
        return filePath == rootPath || filePath.startsWith(rootPath + File.separator)
    }

    private companion object {
        const val OWNER_EXECUTE = 0b001_000_000

        /** ~4 MB between progress callbacks: enough for a smooth bar, few enough to stay cheap. */
        const val PROGRESS_STEP_BYTES = 4L * 1024 * 1024
    }
}
