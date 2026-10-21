package com.aliedu.kern.core.terminal.implementation.install

import android.content.Context
import com.aliedu.kern.core.terminal.implementation.di.TerminalJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Linux distribution catalog (PRD 12.4: "URLs and expected hashes are versioned as
 * configuration, not scattered constants"). Ships as an asset so a release can add a distribution,
 * bump a version or re-point a URL without touching the installer.
 *
 * Every component carries a pinned SHA-256; a bundle without one is never installed, because a
 * rootfs is executed as the user's shell.
 */
@Serializable
data class DistroCatalog(
    val schemaVersion: Int,
    /** Distribution installed when the caller does not pick one. */
    val defaultDistroId: String,
    val distros: List<DistroManifest> = emptyList()
) {
    fun find(id: String): DistroManifest? = distros.firstOrNull { it.id == id }
}

/** One distribution: identity, launch details, and a bundle per ABI. */
@Serializable
data class DistroManifest(
    val id: String,
    val name: String,
    val version: String,
    val summary: String = "",
    /** Login shell inside the guest. Debian-family images ship bash; Alpine/Void use /bin/sh. */
    val shell: String = "/bin/sh",
    val rootfsDir: String = "rootfs",
    val minFreeBytesAfterInstall: Long = 256L * 1024 * 1024,
    val abis: Map<String, AbiBundle> = emptyMap()
)

@Serializable
data class AbiBundle(
    /** Space the extracted rootfs occupies; drives the free-space check. */
    val installedSizeBytes: Long,
    val components: List<BundleComponent> = emptyList(),
    val expectedFiles: List<String> = emptyList()
) {
    /** Total bytes the download will transfer, when every component declares a size. */
    val downloadBytes: Long?
        get() = if (components.all { it.sizeBytes != null }) components.sumOf { it.sizeBytes ?: 0L } else null
}

@Serializable
data class BundleComponent(
    val id: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long? = null,
    /** "tar.gz" or "tar.xz" to extract, "none" to place the file as-is. */
    val archive: String = "none",
    /** Path relative to the environment root. */
    val target: String
)

/** Source of the catalog — an interface so the installer is testable without a Context. */
interface DistroCatalogProvider {
    fun catalog(): DistroCatalog?

    fun find(id: String): DistroManifest?
}

@Singleton
class DistroCatalogStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @TerminalJson private val json: Json
) : DistroCatalogProvider {

    override fun catalog(): DistroCatalog? = runCatching {
        context.assets.open(CATALOG_PATH).bufferedReader().use { it.readText() }
    }.mapCatching { json.decodeFromString(DistroCatalog.serializer(), it) }.getOrNull()

    override fun find(id: String): DistroManifest? = catalog()?.find(id)

    fun bundleFor(distroId: String, abi: String): AbiBundle? = find(distroId)?.abis?.get(abi)

    companion object {
        const val CATALOG_PATH = "terminal/distros.json"
    }
}
