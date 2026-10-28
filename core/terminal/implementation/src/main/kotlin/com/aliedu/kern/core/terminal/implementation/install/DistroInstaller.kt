package com.aliedu.kern.core.terminal.implementation.install

import com.aliedu.kern.core.terminal.api.DistroOption
import com.aliedu.kern.core.terminal.api.EnvironmentInfo
import com.aliedu.kern.core.terminal.api.EnvironmentState
import com.aliedu.kern.core.terminal.api.InstallEvent
import com.aliedu.kern.core.terminal.api.InstallProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs a Linux distribution bundle (PRD 12.3 flow): ABI check -> existing-install check ->
 * storage check -> download (progress/resume/retry/checksum) -> extract -> self-test -> atomic
 * promote. Every failure cleans staging and leaves a truthful state; nothing outside
 * `filesDir/terminal` is ever touched, so Part 1 data cannot be corrupted.
 *
 * The distribution comes from [DistroCatalogProvider]; installing one replaces whichever
 * distribution is currently present, so the caller confirms that with the user first.
 *
 * The runtime self-test probes the **bundled proot launcher** rather than a guest binary: proot is
 * shipped as a `lib*.so` in the native library dir (the one location Android still lets an app
 * targeting API 29+ `execve`) and loads the guest itself, so a rootfs file would never be executed
 * on the host anyway.
 */
@Singleton
class DistroInstaller @Inject constructor(
    private val catalogStore: DistroCatalogProvider,
    private val paths: TerminalPaths,
    private val store: TerminalInstallStore,
    private val abi: AbiSupport,
    private val disk: DiskSpaceChecker,
    private val downloader: BundleDownloader,
    private val extractor: ArchiveExtractor,
    private val probe: RuntimeCapabilityProbe,
    private val log: InstallLog
) : EnvironmentInstaller {

    /** The catalog's default distribution id, used when the caller does not choose one. */
    suspend fun defaultDistroId(): String? = withContext(Dispatchers.IO) {
        catalogStore.catalog()?.defaultDistroId
    }

    override suspend fun availableDistros(): List<DistroOption> = withContext(Dispatchers.IO) {
        val catalog = catalogStore.catalog() ?: return@withContext emptyList()
        val record = store.read()?.takeIf { it.state == EnvironmentState.READY.name }
        val abiName = abi.primaryAbi
        catalog.distros.map { distro ->
            val bundle = distro.abis[abiName]
            DistroOption(
                id = distro.id,
                name = distro.name,
                version = distro.version,
                summary = distro.summary,
                downloadBytes = bundle?.downloadBytes,
                installedBytes = bundle?.installedSizeBytes,
                supported = bundle != null,
                installed = record != null && record.distroId == distro.id
            )
        }
    }

    override suspend fun inspect(): EnvironmentInfo = withContext(Dispatchers.IO) {
        val catalog = catalogStore.catalog()
        val free = runCatching { disk.freeBytes() }.getOrNull()
        val record = store.read()
        val installedManifest = record?.distroId?.let { catalog?.find(it) }
        val target = installedManifest
            ?: catalog?.let { it.find(it.defaultDistroId) ?: it.distros.firstOrNull() }
        val targetAbi = record?.abi ?: abi.primaryAbi
        val requiredFor = { abiName: String? ->
            target?.abis?.get(abiName)?.let { it.installedSizeBytes + target.minFreeBytesAfterInstall }
        }
        if (!abi.deviceSupported()) {
            return@withContext EnvironmentInfo(
                state = EnvironmentState.UNSUPPORTED_ABI,
                distroId = target?.id,
                distroName = target?.name,
                abi = abi.primaryAbi,
                freeBytes = free,
                requiredBytes = requiredFor(abi.primaryAbi),
                detail = "This device ABI (" + abi.primaryAbi + ") is not supported for the Linux environment."
            )
        }
        if (record == null || record.state != EnvironmentState.READY.name) {
            return@withContext EnvironmentInfo(
                state = EnvironmentState.NOT_INSTALLED,
                distroId = target?.id,
                distroName = target?.name,
                version = target?.version,
                abi = abi.primaryAbi,
                freeBytes = free,
                requiredBytes = requiredFor(abi.primaryAbi),
                detail = "No Linux environment is installed. The built-in shell is available."
            )
        }
        val name = record.distroName ?: installedManifest?.name ?: "Linux"
        val bundle = installedManifest?.abis?.get(record.abi ?: abi.primaryAbi)
        val missing = bundle?.expectedFiles?.filterNot { existsWithoutFollowing(File(paths.root, it)) }.orEmpty()
        if (missing.isNotEmpty()) {
            return@withContext EnvironmentInfo(
                state = EnvironmentState.NOT_INSTALLED,
                distroId = record.distroId,
                distroName = name,
                version = record.version,
                abi = record.abi,
                freeBytes = free,
                requiredBytes = requiredFor(record.abi ?: abi.primaryAbi),
                detail = "The previous install is incomplete (missing: " + missing.joinToString() + ")."
            )
        }
        val usable = runtimeUsable(record)
        EnvironmentInfo(
            state = EnvironmentState.READY,
            distroId = record.distroId ?: "linux",
            distroName = name,
            version = record.version,
            installPath = paths.rootfs.absolutePath,
            abi = record.abi,
            installedSizeBytes = record.installedSizeBytes,
            freeBytes = free,
            requiredBytes = requiredFor(record.abi ?: abi.primaryAbi),
            runtimeUsable = usable,
            detail = if (usable) {
                name + " " + record.version.orEmpty() + " is installed and ready."
            } else {
                name + " " + record.version.orEmpty() +
                    " is installed, but the terminal uses the built-in shell. " +
                    record.runtimeDetail.orEmpty()
            }.trim()
        )
    }

    /**
     * Whether the installed runtime can actually launch on this device.
     *
     * A record written by an older probe only ran `proot --version`, which exits 0 even when the
     * sandbox cannot start, so such records are re-checked once and the outcome is persisted —
     * otherwise a device that denies `ptrace` to apps would keep claiming a working environment.
     */
    private suspend fun runtimeUsable(record: InstallRecord): Boolean {
        if (record.probeVersion >= PROBE_VERSION && record.runtimeExecutable != null) {
            return record.runtimeExecutable
        }
        val result = probe.probeLaunch(paths.rootfs)
        log.append("runtime probe v" + PROBE_VERSION + " executable=" + result.executable + " detail=" + result.detail)
        store.write(
            record.copy(
                runtimeExecutable = result.executable,
                runtimeDetail = result.detail,
                probeVersion = PROBE_VERSION
            )
        )
        return result.executable
    }

    /**
     * [channelFlow] runs its block on the collector's dispatcher, which is the main thread for a
     * ViewModel. Downloading and extracting a rootfs there is an ANR waiting to happen — a 336 MB
     * Ubuntu image takes minutes, and Android kills the process long before it finishes. The whole
     * install therefore runs on [Dispatchers.IO]; [send] is safe from that context because the
     * producer scope is still the flow's own.
     */
    override fun install(
        distroId: String?,
        onProgress: (InstallProgress) -> Unit
    ): Flow<InstallEvent> = channelFlow {
        withContext(Dispatchers.IO) { runInstall(distroId, onProgress) }
    }

    private suspend fun ProducerScope<InstallEvent>.runInstall(
        distroId: String?,
        onProgress: (InstallProgress) -> Unit
    ) {
        val catalog = catalogStore.catalog()
        if (catalog == null) {
            send(InstallEvent.Failed("The environment catalog is missing."))
            return@runInstall
        }
        val distro = catalog.find(distroId ?: catalog.defaultDistroId)
        if (distro == null) {
            send(InstallEvent.Failed("Unknown distribution '" + (distroId ?: catalog.defaultDistroId) + "'."))
            return@runInstall
        }
        val abiName = abi.primaryAbi
        if (!abi.deviceSupported()) {
            send(InstallEvent.Failed("This device ABI (" + abiName + ") is not supported."))
            return@runInstall
        }
        val bundle = distro.abis[abiName]
        if (bundle == null) {
            send(InstallEvent.Failed("No " + distro.name + " bundle is available for " + abiName + "."))
            return@runInstall
        }
        val unpinned = bundle.components.filter { it.sha256.isBlank() }
        if (unpinned.isNotEmpty()) {
            send(InstallEvent.Failed("The " + distro.name + " bundle has no checksum and will not be installed."))
            return@runInstall
        }

        val already = inspect()
        if (already.state == EnvironmentState.READY && already.distroId == distro.id) {
            send(InstallEvent.Completed(already))
            return@runInstall
        }

        val free = disk.freeBytes()
        if (!disk.hasRoomFor(bundle.installedSizeBytes, distro.minFreeBytesAfterInstall)) {
            send(
                InstallEvent.Failed(
                    "Not enough storage: need " + formatBytes(bundle.installedSizeBytes + distro.minFreeBytesAfterInstall) +
                        " but only " + formatBytes(free) + " is free."
                )
            )
            return@runInstall
        }

        paths.ensureRoot()
        paths.cleanStaging()
        log.clear()
        val staging = paths.staging(distro.id + "-" + distro.version)
        staging.mkdirs()
        store.write(
            InstallRecord(
                state = EnvironmentState.INSTALLING.name,
                version = distro.version,
                abi = abiName,
                distroId = distro.id,
                distroName = distro.name,
                shell = distro.shell
            )
        )
        log.append("install start distro=" + distro.id + " version=" + distro.version + " abi=" + abiName)

        try {
            bundle.components.forEach { component ->
                val archive = File(staging, archiveName(component))
                val phase = "Downloading " + component.id
                send(InstallEvent.Progress(InstallProgress(phase = phase, currentComponent = component.id, totalBytes = component.sizeBytes, fraction = 0f)))

                val started = System.currentTimeMillis()
                val result = downloader.download(component, archive) { downloaded, total ->
                    val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
                    val progress = InstallProgress(
                        phase = phase,
                        bytesDownloaded = downloaded,
                        totalBytes = total,
                        fraction = total?.takeIf { it > 0 }?.let { (downloaded.toFloat() / it).coerceIn(0f, 1f) },
                        bytesPerSecond = downloaded * 1000 / elapsed,
                        currentComponent = component.id
                    )
                    onProgress(progress)
                    trySend(InstallEvent.Progress(progress))
                }
                if (result.isFailure) throw IOException(result.exceptionOrNull()?.message ?: "Download failed.")

                when (component.archive) {
                    ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ -> {
                        // Extraction is the longest silent stretch of the install (a 358 MB rootfs
                        // takes about a minute), so report the bytes actually unpacked instead of
                        // leaving the screen on "0 B".
                        val phase = "Extracting " + component.id
                        val expected = bundle.installedSizeBytes.takeIf { it > 0 }
                        fun publish(unpacked: Long) {
                            val progress = InstallProgress(
                                phase = phase,
                                bytesDownloaded = unpacked,
                                totalBytes = expected,
                                fraction = expected?.let { (unpacked.toFloat() / it).coerceIn(0f, 1f) },
                                currentComponent = component.id
                            )
                            onProgress(progress)
                            trySend(InstallEvent.Progress(progress))
                        }
                        publish(0)
                        log.append("extracting " + component.id + " -> " + component.target)
                        val unpacked = extractor.extract(
                            archive = archive,
                            targetDir = File(staging, component.target),
                            format = component.archive,
                            onProgress = ::publish
                        )
                        publish(unpacked)
                        archive.delete()
                    }
                    else -> {
                        val destination = File(staging, component.target)
                        destination.parentFile?.mkdirs()
                        archive.copyTo(destination, overwrite = true)
                        destination.setExecutable(true, false)
                        archive.delete()
                    }
                }
            }

            // Self-test: expected files present, then the runtime capability probe.
            send(InstallEvent.Progress(InstallProgress(phase = "Verifying installation")))
            val missing = bundle.expectedFiles.filterNot { existsWithoutFollowing(File(staging, it)) }
            if (missing.isNotEmpty()) throw IOException("Self-test failed; missing: " + missing.joinToString())

            val probeResult = probe.probeLaunch(File(staging, distro.rootfsDir))
            log.append("runtime probe v" + PROBE_VERSION + " executable=" + probeResult.executable + " detail=" + probeResult.detail)

            // Atomic promote. The previous distribution (if any) is replaced here, after the new one
            // has downloaded, extracted and self-tested — a failed download leaves it untouched.
            paths.rootfs.deleteRecursively()
            paths.runtime.deleteRecursively()
            File(staging, distro.rootfsDir).renameTo(paths.rootfs)
            File(staging, "runtime").takeIf { it.exists() }?.renameTo(paths.runtime)
            staging.deleteRecursively()

            val installedSize = directorySize(paths.rootfs)
            store.write(
                InstallRecord(
                    state = EnvironmentState.READY.name,
                    version = distro.version,
                    abi = abiName,
                    distroId = distro.id,
                    distroName = distro.name,
                    shell = distro.shell,
                    installedAt = System.currentTimeMillis(),
                    installedSizeBytes = installedSize,
                    runtimeExecutable = probeResult.executable,
                    runtimeDetail = probeResult.detail,
                    probeVersion = PROBE_VERSION
                )
            )
            log.append("install complete distro=" + distro.id + " size=" + installedSize)
            send(InstallEvent.Completed(inspect()))
        } catch (cancel: CancellationException) {
            staging.deleteRecursively()
            store.write(
                InstallRecord(
                    state = EnvironmentState.NOT_INSTALLED.name,
                    version = distro.version,
                    abi = abiName,
                    distroId = distro.id,
                    distroName = distro.name,
                    lastError = "Install cancelled."
                )
            )
            log.append("install cancelled")
            throw cancel
        } catch (t: Throwable) {
            staging.deleteRecursively()
            val message = t.message ?: "Install failed."
            store.write(
                InstallRecord(
                    state = EnvironmentState.FAILED.name,
                    version = distro.version,
                    abi = abiName,
                    distroId = distro.id,
                    distroName = distro.name,
                    lastError = message
                )
            )
            log.append("install failed: " + message)
            send(InstallEvent.Failed(message))
        }
    }

    override suspend fun uninstall(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            paths.rootfs.deleteRecursively()
            paths.runtime.deleteRecursively()
            paths.cleanStaging()
            store.clear()
            log.clear()
        }
    }

    override suspend fun cleanIncomplete(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val removed = paths.cleanStaging()
            val record = store.read()
            if (record != null && record.state != EnvironmentState.READY.name) store.clear()
            if (removed.isNotEmpty()) log.append("cleaned staging: " + removed.joinToString())
        }
    }

    override suspend fun logText(): String = withContext(Dispatchers.IO) { log.read() }

    private fun archiveName(component: BundleComponent): String = when (component.archive) {
        ArchiveFormat.TAR_GZ -> component.id + ".tar.gz"
        ArchiveFormat.TAR_XZ -> component.id + ".tar.xz"
        else -> component.id + ".bin"
    }

    /**
     * Whether an extracted entry exists without following the final symlink. Rootfs images contain
     * absolute symlinks (e.g. `/bin/sh -> /bin/busybox`) whose targets resolve only inside the proot
     * rootfs — never on the Android host — so a following check would always report them missing.
     */
    private fun existsWithoutFollowing(file: File): Boolean =
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    /**
     * Bytes the extracted rootfs actually occupies, **without following symlinks**.
     *
     * A merged-/usr rootfs (Ubuntu, Debian) is full of links like `bin -> usr/bin`. Walking with
     * `File.length()` follows them, so every symlinked tree is counted twice — a 358 MB Ubuntu image
     * reported 777 MB. [Files.walkFileTree] does not follow links by default, and the attribute
     * check skips the links themselves.
     */
    private fun directorySize(root: File): Long {
        if (!root.exists()) return 0
        var total = 0L
        Files.walkFileTree(
            root.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            }
        )
        return total
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
        else -> bytes.toString() + " B"
    }

    private companion object {
        /** Bumped whenever [RuntimeCapabilityProbe] starts checking something a stored record lacks. */
        const val PROBE_VERSION = 3
    }
}
