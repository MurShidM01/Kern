package com.aliedu.kern.core.workspace

import com.aliedu.kern.core.model.FileContent
import com.aliedu.kern.core.model.FileNode
import com.aliedu.kern.core.model.SaveResult
import com.aliedu.kern.core.model.SearchMatch

/** Thrown when a workspace cannot be accessed (grant revoked, provider gone, path escaped). */
class WorkspaceAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Uniform file operations over any workspace source (PRD 13.3). SAF-backed and app-managed
 * workspaces implement this so the rest of the app never branches on the storage backend.
 * All [path] values are workspace-relative and validated against escape attempts (PRD 9.3).
 */
interface WorkspaceAdapter {
    val workspaceId: String

    /** Whether the underlying access (e.g. a persisted SAF grant) is still valid. */
    suspend fun isAccessValid(): Boolean

    suspend fun list(path: String): List<FileNode>
    suspend fun readText(path: String, maxBytes: Long = DEFAULT_MAX_READ_BYTES): FileContent
    suspend fun writeText(path: String, content: String): SaveResult
    /**
     * Write raw bytes, e.g. a captured preview screenshot. Backends that cannot store binary data
     * return false rather than throwing, so callers can report "not supported here" honestly.
     */
    suspend fun writeBytes(path: String, bytes: ByteArray): Boolean = false
    suspend fun exists(path: String): Boolean
    suspend fun createFile(path: String, content: String = ""): Boolean
    suspend fun createDirectory(path: String): Boolean
    suspend fun delete(path: String): Boolean
    suspend fun search(
        query: String,
        maxResults: Int = DEFAULT_MAX_SEARCH_RESULTS,
        includeContent: Boolean = true
    ): List<SearchMatch>

    companion object {
        const val DEFAULT_MAX_READ_BYTES: Long = 1_000_000L
        const val DEFAULT_MAX_SEARCH_RESULTS: Int = 200
        const val MAX_SEARCH_FILE_BYTES: Long = 512_000L
    }
}

/** Workspace-relative path helpers shared by adapters and tools (PRD 9.3 path containment). */
object WorkspacePaths {

    /**
     * Normalizes a workspace-relative path and rejects anything that escapes the workspace root.
     * Returns null when the path is unsafe or absolute.
     */
    fun normalize(path: String): String? {
        val trimmed = path.trim().replace('\\', '/')
        if (trimmed.startsWith("/")) return null
        val segments = trimmed.split('/').filter { it.isNotEmpty() && it != "." }
        val out = ArrayList<String>(segments.size)
        for (segment in segments) {
            when (segment) {
                ".." -> if (out.isEmpty()) return null else out.removeAt(out.size - 1)
                else -> out.add(segment)
            }
        }
        return out.joinToString("/")
    }

    fun isWithinWorkspace(path: String): Boolean = normalize(path) != null

    fun parentOf(path: String): String {
        val norm = normalize(path) ?: return ""
        val idx = norm.lastIndexOf('/')
        return if (idx <= 0) "" else norm.substring(0, idx)
    }

    fun nameOf(path: String): String {
        val norm = normalize(path) ?: return path
        val idx = norm.lastIndexOf('/')
        return if (idx < 0) norm else norm.substring(idx + 1)
    }
}
