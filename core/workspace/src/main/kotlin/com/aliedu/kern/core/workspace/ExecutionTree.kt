package com.aliedu.kern.core.workspace

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A flat, byte-oriented view of a workspace tree, used to mirror a SAF folder into app storage so
 * commands can run against a real POSIX directory (PRD 11.4 / FR-017). Unlike [WorkspaceAdapter] this
 * is binary-safe and intentionally minimal — the execution mirror never edits source metadata.
 *
 * All paths are workspace-relative and validated with [WorkspacePaths].
 */
interface ExecutionTree {
    /** Every file path (directories excluded), workspace-relative and '/'-separated. */
    suspend fun listFiles(): List<String>

    suspend fun read(relPath: String): ByteArray

    suspend fun write(relPath: String, bytes: ByteArray): Boolean

    suspend fun delete(relPath: String): Boolean
}

/** App-private tree backed by ordinary [File] I/O (app-managed sources and the execution mirror). */
class LocalExecutionTree(private val root: File) : ExecutionTree {

    override suspend fun listFiles(): List<String> = withContext(Dispatchers.IO) {
        if (!root.isDirectory) return@withContext emptyList()
        root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .toList()
    }

    override suspend fun read(relPath: String): ByteArray = withContext(Dispatchers.IO) {
        File(root, requirePath(relPath)).readBytes()
    }

    override suspend fun write(relPath: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val file = File(root, requirePath(relPath))
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        true
    }

    override suspend fun delete(relPath: String): Boolean = withContext(Dispatchers.IO) {
        File(root, requirePath(relPath)).delete()
    }

    private fun requirePath(path: String): String =
        WorkspacePaths.normalize(path) ?: throw WorkspaceAccessException("Invalid path: $path")
}

/** SAF-backed tree (user-selected folder). Reads/writes documents through the content resolver. */
class SafExecutionTree(
    private val context: Context,
    private val treeUri: Uri
) : ExecutionTree {

    private val resolver get() = context.contentResolver

    private fun root(): DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw WorkspaceAccessException("The selected folder is no longer available.")

    override suspend fun listFiles(): List<String> = withContext(Dispatchers.IO) {
        val out = ArrayList<String>()
        val queue = ArrayDeque<Pair<DocumentFile, String>>()
        queue.add(root() to "")
        while (queue.isNotEmpty()) {
            val (dir, prefix) = queue.removeFirst()
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) queue.add(child to rel) else out.add(rel)
            }
        }
        out
    }

    override suspend fun read(relPath: String): ByteArray = withContext(Dispatchers.IO) {
        val doc = resolve(relPath) ?: throw WorkspaceAccessException("File not found: $relPath")
        resolver.openInputStream(doc.uri)?.use { it.readBytes() }
            ?: throw WorkspaceAccessException("Could not open $relPath for reading.")
    }

    override suspend fun write(relPath: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val doc = resolveOrCreate(relPath) ?: return@withContext false
        resolver.openOutputStream(doc.uri, "wt")?.use { it.write(bytes) } ?: return@withContext false
        true
    }

    override suspend fun delete(relPath: String): Boolean = withContext(Dispatchers.IO) {
        resolve(relPath)?.delete() ?: false
    }

    private fun resolve(path: String): DocumentFile? {
        val normalized = WorkspacePaths.normalize(path) ?: return null
        if (normalized.isEmpty()) return root().takeIf { it.exists() }
        var current = root()
        for (segment in normalized.split('/')) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun resolveOrCreate(path: String): DocumentFile? {
        val normalized = WorkspacePaths.normalize(path) ?: return null
        if (normalized.isEmpty()) return root()
        val segments = normalized.split('/')
        var dir = root()
        for (segment in segments.dropLast(1)) {
            dir = dir.findFile(segment) ?: dir.createDirectory(segment) ?: return null
        }
        val name = segments.last()
        return dir.findFile(name) ?: dir.createFile("application/octet-stream", name)
    }
}
