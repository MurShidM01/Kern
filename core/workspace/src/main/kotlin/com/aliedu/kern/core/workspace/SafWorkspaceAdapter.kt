package com.aliedu.kern.core.workspace

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.aliedu.kern.core.common.logging.KernLog
import com.aliedu.kern.core.model.FileClassifier
import com.aliedu.kern.core.model.FileContent
import com.aliedu.kern.core.model.FileKind
import com.aliedu.kern.core.model.FileNode
import com.aliedu.kern.core.model.SaveResult
import com.aliedu.kern.core.model.SearchMatch
import com.aliedu.kern.core.model.SearchMatchKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Workspace backed by a user-selected Android document tree (Storage Access Framework).
 * Operations follow document-provider capabilities; a document URI is never treated as a POSIX
 * path (PRD 11.1, 11.4, 13.3).
 */
class SafWorkspaceAdapter(
    private val context: Context,
    override val workspaceId: String,
    private val treeUri: Uri
) : WorkspaceAdapter {

    private val resolver get() = context.contentResolver

    private fun root(): DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw WorkspaceAccessException("The selected folder is no longer available.")

    override suspend fun isAccessValid(): Boolean = withContext(Dispatchers.IO) {
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission } ||
            DocumentFile.fromTreeUri(context, treeUri)?.exists() == true
    }

    override suspend fun list(path: String): List<FileNode> = withContext(Dispatchers.IO) {
        val normalized = requirePath(path)
        val dir = resolve(normalized)
            ?: throw WorkspaceAccessException("Folder not found: $normalized")
        if (!dir.isDirectory) return@withContext emptyList()
        dir.listFiles()
            .map { it.toNode(normalized) }
            .sortedWith(compareBy({ it.kind != FileKind.DIRECTORY }, { it.name.lowercase() }))
    }

    override suspend fun readText(path: String, maxBytes: Long): FileContent = withContext(Dispatchers.IO) {
        val normalized = requirePath(path)
        val file = resolve(normalized)
            ?: throw WorkspaceAccessException("File not found: $normalized")
        if (file.isDirectory) throw WorkspaceAccessException("$normalized is a folder.")

        val bytes = resolver.openInputStream(file.uri)?.use { input ->
            readBounded(input, maxBytes)
        } ?: throw WorkspaceAccessException("Could not open $normalized for reading.")

        if (bytes.data.contains(0)) {
            throw WorkspaceAccessException("This file appears to be binary and cannot be opened as text.")
        }
        FileContent(
            path = normalized,
            text = String(bytes.data, Charsets.UTF_8),
            encoding = "UTF-8",
            sizeBytes = bytes.totalSize,
            truncated = bytes.truncated
        )
    }

    override suspend fun writeText(path: String, content: String): SaveResult = withContext(Dispatchers.IO) {
        val normalized = requirePath(path)
        val file = resolve(normalized)
            ?: throw WorkspaceAccessException("File not found: $normalized")
        if (file.isDirectory) throw WorkspaceAccessException("$normalized is a folder.")
        val bytes = content.toByteArray(Charsets.UTF_8)
        resolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) }
            ?: throw WorkspaceAccessException("Could not open $normalized for writing.")
        SaveResult(path = normalized, bytesWritten = bytes.size.toLong(), savedAt = System.currentTimeMillis())
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        resolve(requirePath(path)) != null
    }

    override suspend fun createFile(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = requirePath(path)
        val parentPath = WorkspacePaths.parentOf(normalized)
        val name = WorkspacePaths.nameOf(normalized)
        val parent = resolve(parentPath) ?: throw WorkspaceAccessException("Parent folder not found: $parentPath")
        if (parent.findFile(name) != null) throw WorkspaceAccessException("$normalized already exists.")
        val created = parent.createFile(mimeFor(name), name)
            ?: throw WorkspaceAccessException("The provider did not allow creating $normalized.")
        if (content.isNotEmpty()) {
            resolver.openOutputStream(created.uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        }
        true
    }

    override suspend fun createDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = requirePath(path)
        val parentPath = WorkspacePaths.parentOf(normalized)
        val name = WorkspacePaths.nameOf(normalized)
        val parent = resolve(parentPath) ?: throw WorkspaceAccessException("Parent folder not found: $parentPath")
        parent.createDirectory(name) != null
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = requirePath(path)
        if (normalized.isEmpty()) throw WorkspaceAccessException("Refusing to delete the workspace root.")
        val file = resolve(normalized) ?: return@withContext false
        file.delete()
    }

    override suspend fun search(
        query: String,
        maxResults: Int,
        includeContent: Boolean
    ): List<SearchMatch> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val needle = query.lowercase()
        val results = ArrayList<SearchMatch>()
        val queue = ArrayDeque<String>()
        queue.add("")
        var visited = 0
        while (queue.isNotEmpty() && results.size < maxResults && visited < MAX_VISITED) {
            val current = queue.removeFirst()
            val dir = resolve(current) ?: continue
            for (child in dir.listFiles()) {
                visited++
                if (results.size >= maxResults) break
                val childPath = if (current.isEmpty()) child.name.orEmpty() else "$current/${child.name}"
                if (child.isDirectory) {
                    queue.add(childPath)
                    if (child.name?.lowercase()?.contains(needle) == true) {
                        results.add(SearchMatch(childPath, SearchMatchKind.FILENAME, null, null))
                    }
                } else {
                    if (child.name?.lowercase()?.contains(needle) == true) {
                        results.add(SearchMatch(childPath, SearchMatchKind.FILENAME, null, null))
                    } else if (includeContent && child.length() <= WorkspaceAdapter.MAX_SEARCH_FILE_BYTES) {
                        findContentMatches(child, childPath, needle, results, maxResults)
                    }
                }
            }
        }
        results
    }

    private fun findContentMatches(
        file: DocumentFile,
        path: String,
        needle: String,
        out: MutableList<SearchMatch>,
        maxResults: Int
    ) {
        runCatching {
            resolver.openInputStream(file.uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (out.size >= maxResults) return@forEachIndexed
                        if (line.lowercase().contains(needle)) {
                            out.add(SearchMatch(path, SearchMatchKind.CONTENT, index + 1, line.trim()))
                        }
                    }
                }
            }
        }.onFailure { KernLog.d("SafWorkspace", "search read failed for $path: ${it.message}") }
    }

    private fun resolve(path: String): DocumentFile? {
        val normalized = WorkspacePaths.normalize(path) ?: return null
        if (normalized.isEmpty()) {
            val r = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            return if (r.exists()) r else null
        }
        var current: DocumentFile = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        for (segment in normalized.split('/')) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun requirePath(path: String): String =
        WorkspacePaths.normalize(path) ?: throw WorkspaceAccessException("Invalid path: $path")

    private fun DocumentFile.toNode(parentPath: String): FileNode {
        val name = name.orEmpty()
        val childPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
        val kind = when {
            isDirectory -> FileKind.DIRECTORY
            FileClassifier.isLikelyText(name) -> FileKind.TEXT
            else -> FileKind.UNKNOWN
        }
        return FileNode(
            name = name,
            path = childPath,
            kind = kind,
            sizeBytes = if (isFile) length() else null,
            lastModified = if (isFile) lastModified().takeIf { it > 0 } else null
        )
    }

    private fun mimeFor(name: String): String {
        val ext = FileClassifier.extensionOf(name)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "text/plain"
    }

    private class BoundedBytes(val data: ByteArray, val truncated: Boolean, val totalSize: Long)

    private fun readBounded(input: java.io.InputStream, maxBytes: Long): BoundedBytes {
        val buffer = ByteArray(64 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0L
        var truncated = false
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (out.size() < maxBytes) {
                // Clamped: a caller may pass a huge limit, and the Long→Int cast would wrap negative.
                val remaining = (maxBytes - out.size()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                if (read <= remaining) out.write(buffer, 0, read) else {
                    out.write(buffer, 0, remaining)
                    truncated = true
                }
            } else {
                truncated = true
            }
        }
        return BoundedBytes(out.toByteArray(), truncated, total)
    }

    private companion object {
        const val MAX_VISITED = 5000
    }
}
