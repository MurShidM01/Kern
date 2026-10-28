package com.aliedu.kern.core.terminal.implementation.install

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.File
import java.io.OutputStream

/** Builds small `.tar.gz` / `.tar.xz` fixtures for extraction / install tests. */
object TarTestFactory {

    data class Entry(val name: String, val content: ByteArray = ByteArray(0), val mode: Int = 0b110_100_100)

    fun write(
        file: File,
        entries: List<Entry>,
        symlinks: List<Pair<String, String>> = emptyList(),
        hardLinks: List<Pair<String, String>> = emptyList(),
        format: String = ArchiveFormat.TAR_GZ
    ) {
        val raw: OutputStream = when (format) {
            ArchiveFormat.TAR_XZ -> XZCompressorOutputStream(file.outputStream())
            else -> GzipCompressorOutputStream(file.outputStream())
        }
        TarArchiveOutputStream(raw).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            entries.forEach { entry ->
                val archiveEntry = TarArchiveEntry(entry.name)
                archiveEntry.size = entry.content.size.toLong()
                archiveEntry.mode = entry.mode
                tar.putArchiveEntry(archiveEntry)
                tar.write(entry.content)
                tar.closeArchiveEntry()
            }
            symlinks.forEach { (name, target) ->
                val link = TarArchiveEntry(name, TarConstants.LF_SYMLINK)
                link.linkName = target
                tar.putArchiveEntry(link)
                tar.closeArchiveEntry()
            }
            hardLinks.forEach { (name, target) ->
                val link = TarArchiveEntry(name, TarConstants.LF_LINK)
                link.linkName = target
                link.mode = 0b111_111_111
                tar.putArchiveEntry(link)
                tar.closeArchiveEntry()
            }
        }
    }
}
