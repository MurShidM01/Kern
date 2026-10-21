package com.aliedu.kern.core.workspace

import com.aliedu.kern.core.model.FileClassifier
import com.aliedu.kern.core.model.FileContent
import com.aliedu.kern.core.model.FileKind
import com.aliedu.kern.core.model.FileNode
import com.aliedu.kern.core.model.SaveResult
import com.aliedu.kern.core.model.SearchMatch
import com.aliedu.kern.core.model.SearchMatchKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Workspace stored in Kern-controlled app-specific storage (PRD 11.1). Uses ordinary File I/O
 * and is easy to map to execution paths in Part 2.
 */
class AppManagedWorkspaceAdapter(
    private val rootDir: File,
    override val workspaceId: String
) : WorkspaceAdapter {

    override suspend fun isAccessValid(): Boolean = rootDir.exists() && rootDir.isDirectory

    override suspend fun list(path: String): List<FileNode> = withContext(Dispatchers.IO) {
        val dir = fileFor(path)
        if (!dir.isDirectory) return@withContext emptyList()
        dir.listFiles().orEmpty()
            .map { it.toNode() }
            .sortedWith(compareBy({ it.kind != FileKind.DIRECTORY }, { it.name.lowercase() }))
    }

    override suspend fun readText(path: String, maxBytes: Long): FileContent = withContext(Dispatchers.IO) {
        val file = fileFor(path)
        if (!file.exists()) throw WorkspaceAccessException("File not found: $path")
        if (file.isDirectory) throw WorkspaceAccessException("$path is a folder.")
        val total = file.length()
        val bytes = file.inputStream().use { readBounded(it, maxBytes) }
        if (bytes.data.contains(0)) {
            throw WorkspaceAccessException("This file appears to be binary and cannot be opened as text.")
        }
        FileContent(
            path = WorkspacePaths.normalize(path) ?: path,
            text = String(bytes.data, Charsets.UTF_8),
            encoding = "UTF-8",
            sizeBytes = total,
            truncated = bytes.truncated
        )
    }

    override suspend fun writeText(path: String, content: String): SaveResult = withContext(Dispatchers.IO) {
        val file = fileFor(path)
        file.parentFile?.mkdirs()
        val bytes = content.toByteArray(Charsets.UTF_8)
        // Atomic-ish replace: write temp then rename where the filesystem supports it.
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
        SaveResult(WorkspacePaths.normalize(path) ?: path, bytes.size.toLong(), System.currentTimeMillis())
    }

    override suspend fun writeBytes(path: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val file = fileFor(path)
        file.parentFile?.mkdirs()
        // Same atomic-ish replace as writeText, so a half-written image never lands in the workspace.
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
        true
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) { fileFor(path).exists() }

    override suspend fun createFile(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val file = fileFor(path)
        if (file.exists()) throw WorkspaceAccessException("$path already exists.")
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        true
    }

    override suspend fun createDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        fileFor(path).mkdirs()
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = WorkspacePaths.normalize(path)
        if (normalized.isNullOrEmpty()) throw WorkspaceAccessException("Refusing to delete the workspace root.")
        fileFor(path).deleteRecursively()
    }

    override suspend fun search(
        query: String,
        maxResults: Int,
        includeContent: Boolean
    ): List<SearchMatch> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val needle = query.lowercase()
        val results = ArrayList<SearchMatch>()
        val root = rootDir
        if (!root.exists()) return@withContext results
        val stack = ArrayDeque<File>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && results.size < maxResults && visited < MAX_VISITED) {
            val current = stack.removeFirst()
            val children = current.listFiles().orEmpty()
            for (child in children) {
                visited++
                if (results.size >= maxResults) break
                val rel = child.relativeTo(root).path.replace('\\', '/')
                if (child.isDirectory) {
                    stack.add(child)
                    if (child.name.lowercase().contains(needle)) {
                        results.add(SearchMatch(rel, SearchMatchKind.FILENAME, null, null))
                    }
                } else {
                    if (child.name.lowercase().contains(needle)) {
                        results.add(SearchMatch(rel, SearchMatchKind.FILENAME, null, null))
                    } else if (includeContent && child.length() <= WorkspaceAdapter.MAX_SEARCH_FILE_BYTES) {
                        runCatching {
                            child.bufferedReader(Charsets.UTF_8).useLines { lines ->
                                lines.forEachIndexed { index, line ->
                                    if (results.size >= maxResults) return@forEachIndexed
                                    if (line.lowercase().contains(needle)) {
                                        results.add(SearchMatch(rel, SearchMatchKind.CONTENT, index + 1, line.trim()))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        results
    }

    private fun fileFor(path: String): File {
        val normalized = WorkspacePaths.normalize(path)
            ?: throw WorkspaceAccessException("Invalid path: $path")
        return if (normalized.isEmpty()) rootDir else File(rootDir, normalized)
    }

    private fun File.toNode(): FileNode {
        val rel = relativeTo(rootDir).path.replace('\\', '/')
        val kind = when {
            isDirectory -> FileKind.DIRECTORY
            FileClassifier.isLikelyText(name) -> FileKind.TEXT
            else -> FileKind.UNKNOWN
        }
        return FileNode(name, rel, kind, if (isFile) length() else null, lastModified())
    }

    private class BoundedBytes(val data: ByteArray, val truncated: Boolean)

    private fun readBounded(input: java.io.InputStream, maxBytes: Long): BoundedBytes {
        val buffer = ByteArray(64 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var truncated = false
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
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
        return BoundedBytes(out.toByteArray(), truncated)
    }

    private companion object {
        const val MAX_VISITED = 5000
    }
}
