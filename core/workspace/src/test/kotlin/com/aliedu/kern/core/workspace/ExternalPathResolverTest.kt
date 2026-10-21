package com.aliedu.kern.core.workspace

import android.net.Uri
import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Backs the unified `/sdcard` workspace model: a folder picked from primary external storage must
 * map to its real filesystem path so Files, Editor, Agent and the terminal share one tree. Anything
 * that cannot be mapped (third-party providers, `raw:` documents, unknown volumes) returns null and
 * keeps the SAF-backed fallback.
 */
@RunWith(RobolectricTestRunner::class)
class ExternalPathResolverTest {

    @Test
    fun `primary volume tree maps to the real storage path`() {
        val uri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3AKern"
        )
        assertThat(ExternalPathResolver.resolve(uri)).isEqualTo("/storage/emulated/0/Kern")
    }

    @Test
    fun `nested folder under primary keeps its relative segments`() {
        val uri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3AKern%2Fsrc%2Fmain"
        )
        assertThat(ExternalPathResolver.resolve(uri))
            .isEqualTo("/storage/emulated/0/Kern/src/main")
    }

    @Test
    fun `the primary root itself maps to the volume root`() {
        assertThat(ExternalPathResolver.resolveDocumentId("primary:"))
            .isEqualTo("/storage/emulated/0")
    }

    @Test
    fun `a secondary volume maps under storage by its id`() {
        assertThat(ExternalPathResolver.resolveDocumentId("1234-ABCD:photos"))
            .isEqualTo("/storage/1234-ABCD/photos")
    }

    @Test
    fun `a third-party provider is not mapped`() {
        val uri = Uri.parse("content://com.example.drive/tree/primary%3AKern")
        assertThat(ExternalPathResolver.resolve(uri)).isNull()
    }

    @Test
    fun `a raw document id is not mapped`() {
        assertThat(ExternalPathResolver.resolveDocumentId("raw:/storage/emulated/0/Kern")).isNull()
    }

    @Test
    fun `a document id without a separator is not mapped`() {
        assertThat(ExternalPathResolver.resolveDocumentId("primary")).isNull()
        assertThat(ExternalPathResolver.resolveDocumentId("")).isNull()
    }

    @Test
    fun `a non-tree uri is not mapped`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AKern")
        assertThat(ExternalPathResolver.resolve(uri)).isNull()
    }

    @Test
    fun `a recorded real path is preferred over the tree uri`() {
        val workspace = workspace(
            uri = "content://com.android.externalstorage.documents/tree/primary%3AOther",
            realPath = "/storage/emulated/0/Kern"
        )
        assertThat(ExternalPathResolver.realPathOf(workspace)).isEqualTo("/storage/emulated/0/Kern")
    }

    @Test
    fun `a workspace without a recorded path derives it from the tree uri`() {
        val workspace = workspace(
            uri = "content://com.android.externalstorage.documents/tree/primary%3AKern",
            realPath = null
        )
        assertThat(ExternalPathResolver.realPathOf(workspace)).isEqualTo("/storage/emulated/0/Kern")
    }

    @Test
    fun `an unmappable workspace has no real path`() {
        val workspace = workspace(uri = "content://com.example.drive/tree/primary%3AKern", realPath = null)
        assertThat(ExternalPathResolver.realPathOf(workspace)).isNull()
    }

    private fun workspace(uri: String?, realPath: String?) = Workspace(
        id = "w1",
        displayName = "Kern",
        sourceType = WorkspaceSourceType.SELECTED_FOLDER,
        uriString = uri,
        localPath = null,
        grantState = GrantState.VALID,
        lastOpenedAt = 0,
        createdAt = 0,
        realPath = realPath
    )
}
