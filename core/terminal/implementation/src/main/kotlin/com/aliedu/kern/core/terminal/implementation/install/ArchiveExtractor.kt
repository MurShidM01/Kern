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
import java.nio.file.attribute.PosixFilePermissions
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
 * hard links and the archive's permission bits, and rejecting any entry that would escape the target
 * (path traversal). Uses Apache Commons Compress so it works without a system tar binary.
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
        // Directory modes are applied after the whole archive is unpacked: a read-only directory
        // chmod'ed before its children are written would make the extraction fail on its own files.
        val directoryModes = mutableListOf<Pair<File, Int>>()

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
                        entry.isDirectory -> {
                            outFile.mkdirs()
                            directoryModes += outFile to entry.mode
                        }
                        entry.isSymbolicLink -> {
                            outFile.parentFile?.mkdirs()
                            createSymlink(entry.linkName, outFile)
                        }
                        entry.isLink -> {
                            outFile.parentFile?.mkdirs()
                            createHardLink(File(targetDir, entry.linkName), outFile, entry.mode)
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
        directoryModes.forEach { (dir, mode) -> applyMode(dir, mode) }
        return written
    }

    /**
     * Applies the archive's permission bits instead of only ever adding "executable".
     *
     * Getting this wrong is not cosmetic: a rootfs ships hard links such as
     * `/usr/bin/perl -> /usr/bin/perl5.38.2`, and when the link cannot be created the copy fallback
     * used to leave the copy at the process umask (0600). `perl` was then present but not executable,
     * so every `dpkg` post-install script that runs debconf died with
     * `/usr/share/debconf/frontend: Permission denied` and apt could never configure a package.
     */
    private fun applyMode(file: File, mode: Int) {
        // A header without a mode field decodes to 0; keep the umask default rather than chmod 0000.
        if (mode == 0) return
        val applied = runCatching {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(symbolic(mode)))
        }.isSuccess
        if (!applied && mode and OWNER_EXECUTE != 0) file.setExecutable(true, false)
    }

    /** The `rwxrwxrwx` string for the permission bits of [mode] (setuid/sticky are not expressible). */
    internal fun symbolic(mode: Int): String = buildString(9) {
        append(if (mode and OWNER_READ != 0) 'r' else '-')
        append(if (mode and OWNER_WRITE != 0) 'w' else '-')
        append(if (mode and OWNER_EXECUTE != 0) 'x' else '-')
        append(if (mode and GROUP_READ != 0) 'r' else '-')
        append(if (mode and GROUP_WRITE != 0) 'w' else '-')
        append(if (mode and GROUP_EXECUTE != 0) 'x' else '-')
        append(if (mode and OTHER_READ != 0) 'r' else '-')
        append(if (mode and OTHER_WRITE != 0) 'w' else '-')
        append(if (mode and OTHER_EXECUTE != 0) 'x' else '-')
    }

    private fun createSymlink(linkTarget: String, link: File) {
        runCatching {
            Files.deleteIfExists(link.toPath())
            Files.createSymbolicLink(link.toPath(), Paths.get(linkTarget))
        }
    }

    /**
     * Creates the archive's hard link, falling back to a copy when the device refuses `link()`.
     *
     * The fallback must re-apply the entry's mode: `copyTo` creates the file with the process umask,
     * which silently dropped the executable bit on `/usr/bin/perl` and broke apt on-device.
     */
    private fun createHardLink(existing: File, link: File, mode: Int) {
        val linked = runCatching {
            Files.deleteIfExists(link.toPath())
            Files.createLink(link.toPath(), existing.toPath())
            true
        }.getOrDefault(false)
        if (linked) return
        runCatching { existing.copyTo(link, overwrite = true) }
            .onSuccess { applyMode(link, mode) }
    }

    private fun isWithin(root: File, file: File): Boolean {
        val rootPath = root.canonicalPath
        val filePath = file.canonicalPath
        return filePath == rootPath || filePath.startsWith(rootPath + File.separator)
    }

    private companion object {
        const val OWNER_READ = 0b100_000_000
        const val OWNER_WRITE = 0b010_000_000
        const val OWNER_EXECUTE = 0b001_000_000
        const val GROUP_READ = 0b000_100_000
        const val GROUP_WRITE = 0b000_010_000
        const val GROUP_EXECUTE = 0b000_001_000
        const val OTHER_READ = 0b000_000_100
        const val OTHER_WRITE = 0b000_000_010
        const val OTHER_EXECUTE = 0b000_000_001

        /** ~4 MB between progress callbacks: enough for a smooth bar, few enough to stay cheap. */
        const val PROGRESS_STEP_BYTES = 4L * 1024 * 1024
    }
}
