package com.aliedu.kern.core.workspace

import android.net.Uri
import com.aliedu.kern.core.model.Workspace

/**
 * Maps a Storage Access Framework tree URI back to the real filesystem path it points at.
 *
 * Kern's workspace model is unified on real `/sdcard` paths: a folder picked from primary external
 * storage is reachable by Files, Editor, Agent tools **and** the Linux terminal at the same path,
 * so nothing has to be mirrored. Only the external-storage provider exposes a path we can derive
 * (`content://com.android.externalstorage.documents/tree/primary%3AKern` → `/storage/emulated/0/Kern`);
 * every other provider returns null and the caller keeps the SAF-backed fallback.
 */
object ExternalPathResolver {

    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    const val PRIMARY_VOLUME = "primary"
    const val PRIMARY_ROOT = "/storage/emulated/0"
    const val VOLUME_ROOT = "/storage"

    private const val TREE_SEGMENT = "tree"
    private const val RAW_VOLUME = "raw"

    /**
     * The real path behind [treeUri], or null when it cannot be mapped (a third-party provider, a
     * `raw:` document, or an unknown volume). All-files access is still required to *use* the path.
     */
    fun resolve(treeUri: Uri): String? {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val segments = treeUri.pathSegments
        if (segments.size < 2 || segments[0] != TREE_SEGMENT) return null
        return resolveDocumentId(segments[1])
    }

    /**
     * The workspace's real directory: the recorded [Workspace.realPath], or one derived from its tree
     * URI. Deriving on demand lets a workspace opened before the unified `/sdcard` model (whose row
     * predates the column) still resolve, without forcing the user to pick the folder again.
     */
    fun realPathOf(workspace: Workspace): String? =
        workspace.realPath ?: workspace.uriString?.let(Uri::parse)?.let(::resolve)

    /**
     * Maps a SAF document id (`primary:Kern`, `1234-ABCD:photos`) to a real path. Pure so it can be
     * asserted without an Android [Uri].
     */
    fun resolveDocumentId(documentId: String): String? {
        val separator = documentId.indexOf(':')
        if (separator <= 0) return null
        val volume = documentId.substring(0, separator)
        val relative = documentId.substring(separator + 1).trim('/')
        if (volume.isEmpty() || volume == RAW_VOLUME || volume.contains('/')) return null

        val base = if (volume == PRIMARY_VOLUME) PRIMARY_ROOT else "$VOLUME_ROOT/$volume"
        return if (relative.isEmpty()) base else "$base/$relative"
    }
}
