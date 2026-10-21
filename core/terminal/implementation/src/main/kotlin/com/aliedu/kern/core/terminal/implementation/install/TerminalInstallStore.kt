package com.aliedu.kern.core.terminal.implementation.install

import com.aliedu.kern.core.terminal.implementation.di.TerminalJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Persisted install state, written atomically so a crash cannot leave a half-written record. */
@Serializable
data class InstallRecord(
    val state: String,
    val version: String? = null,
    val abi: String? = null,
    /** Catalog id of the installed distribution (e.g. "alpine", "ubuntu"). */
    val distroId: String? = null,
    /** Human-readable distribution name, kept so the UI can name it without the catalog. */
    val distroName: String? = null,
    /** Login shell inside the guest, resolved from the catalog at install time. */
    val shell: String? = null,
    val installedAt: Long = 0,
    val installedSizeBytes: Long? = null,
    /** Result of the runtime capability probe (null = not probed). */
    val runtimeExecutable: Boolean? = null,
    val runtimeDetail: String? = null,
    /**
     * Version of the probe that produced [runtimeExecutable]. Bumped when the probe starts checking
     * something new, so a record written by an older probe is re-checked instead of trusted.
     */
    val probeVersion: Int = 0,
    val lastError: String? = null
)

@Singleton
class TerminalInstallStore @Inject constructor(
    private val paths: TerminalPaths,
    @TerminalJson private val json: Json
) {
    fun read(): InstallRecord? {
        val file = paths.stateFile
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(InstallRecord.serializer(), file.readText())
        }.getOrNull()
    }

    fun write(record: InstallRecord) {
        paths.ensureRoot()
        val tmp = java.io.File(paths.root, "install.json.tmp")
        tmp.writeText(json.encodeToString(InstallRecord.serializer(), record))
        if (paths.stateFile.exists()) paths.stateFile.delete()
        if (!tmp.renameTo(paths.stateFile)) {
            // Fallback: copy then remove (rename can fail across some filesystems).
            paths.stateFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun clear() {
        paths.stateFile.delete()
    }
}
