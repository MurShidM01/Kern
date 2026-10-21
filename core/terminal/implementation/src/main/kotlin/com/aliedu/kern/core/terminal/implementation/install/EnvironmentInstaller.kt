package com.aliedu.kern.core.terminal.implementation.install

import com.aliedu.kern.core.terminal.api.DistroOption
import com.aliedu.kern.core.terminal.api.EnvironmentInfo
import com.aliedu.kern.core.terminal.api.InstallEvent
import com.aliedu.kern.core.terminal.api.InstallProgress
import kotlinx.coroutines.flow.Flow

/**
 * Installs / repairs / removes the Linux environment bundle (PRD 12.3). Kept behind its own
 * interface so the runtime layer does not depend on the installer's internals.
 */
interface EnvironmentInstaller {
    suspend fun inspect(): EnvironmentInfo

    /** Distributions the setup screen can offer, with per-ABI support and install state. */
    suspend fun availableDistros(): List<DistroOption> = emptyList()

    /** Installs [distroId] (null = the catalog default), replacing any other installed distro. */
    fun install(distroId: String?, onProgress: (InstallProgress) -> Unit): Flow<InstallEvent>
    suspend fun uninstall(): Result<Unit>
    suspend fun cleanIncomplete(): Result<Unit>

    /** The append-only installer log (PRD 12.4). */
    suspend fun logText(): String = ""
}
