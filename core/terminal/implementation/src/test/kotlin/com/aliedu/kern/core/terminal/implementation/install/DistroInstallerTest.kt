package com.aliedu.kern.core.terminal.implementation.install

import com.aliedu.kern.core.terminal.api.EnvironmentState
import com.aliedu.kern.core.terminal.api.InstallEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DistroInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var paths: TerminalPaths
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // No bundled proot is present in the test environment, so the runtime probe cannot launch the
        // sandbox — the behaviour these tests exercise.
        paths = TerminalPaths(tmp.newFolder("terminal"), tmp.newFolder("lib"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun buildTarGz(marker: String = "alpine"): File {
        val file = tmp.newFile("rootfs-" + System.nanoTime() + ".tar.gz")
        TarTestFactory.write(
            file,
            listOf(
                TarTestFactory.Entry("./bin/sh", "fake-sh".toByteArray(), 0b111_101_101),
                TarTestFactory.Entry("./bin/busybox", "fake-busybox".toByteArray(), 0b111_101_101),
                TarTestFactory.Entry("./etc/os-release", marker.toByteArray())
            )
        )
        return file
    }

    private fun buildTarXz(marker: String): File {
        val file = tmp.newFile("rootfs-" + System.nanoTime() + ".tar.xz")
        TarTestFactory.write(
            file,
            listOf(
                TarTestFactory.Entry("./bin/sh", "fake-sh".toByteArray(), 0b111_101_101),
                TarTestFactory.Entry("./bin/bash", "fake-bash".toByteArray(), 0b111_101_101),
                TarTestFactory.Entry("./etc/os-release", marker.toByteArray())
            ),
            format = ArchiveFormat.TAR_XZ
        )
        return file
    }

    /** Mirrors a real rootfs: /bin/sh is an absolute symlink to /bin/busybox. */
    private fun buildRealisticTarGz(): File {
        val file = tmp.newFile("rootfs-real-" + System.nanoTime() + ".tar.gz")
        TarTestFactory.write(
            file,
            entries = listOf(
                TarTestFactory.Entry("./bin/busybox", "fake-busybox".toByteArray(), 0b111_101_101),
                TarTestFactory.Entry("./etc/os-release", "alpine".toByteArray())
            ),
            symlinks = listOf("./bin/sh" to "/bin/busybox")
        )
        return file
    }

    private fun distro(
        id: String = "alpine",
        name: String = "Alpine Linux",
        version: String = "3.22.2",
        shell: String = "/bin/sh",
        sha: String,
        size: Long,
        archive: String = ArchiveFormat.TAR_GZ,
        expected: List<String> = listOf("rootfs/bin/sh", "rootfs/bin/busybox", "rootfs/etc/os-release")
    ) = DistroManifest(
        id = id,
        name = name,
        version = version,
        shell = shell,
        rootfsDir = "rootfs",
        minFreeBytesAfterInstall = 0,
        abis = mapOf(
            "arm64-v8a" to AbiBundle(
                installedSizeBytes = size,
                components = listOf(
                    BundleComponent(
                        id = "rootfs",
                        url = server.url("/rootfs").toString(),
                        sha256 = sha,
                        sizeBytes = size,
                        archive = archive,
                        target = "rootfs"
                    )
                ),
                expectedFiles = expected
            )
        )
    )

    private fun catalog(vararg distros: DistroManifest) =
        DistroCatalog(schemaVersion = 2, defaultDistroId = distros.first().id, distros = distros.toList())

    private fun installer(catalog: DistroCatalog?, freeBytes: Long = Long.MAX_VALUE) = DistroInstaller(
        catalogStore = object : DistroCatalogProvider {
            override fun catalog(): DistroCatalog? = catalog
            override fun find(id: String): DistroManifest? = catalog?.find(id)
        },
        paths = paths,
        store = TerminalInstallStore(paths, json),
        abi = AbiSupport(listOf("arm64-v8a")),
        disk = DiskSpaceChecker { freeBytes },
        downloader = BundleDownloader(OkHttpClient(), ChecksumVerifier()),
        extractor = ArchiveExtractor(),
        probe = RuntimeCapabilityProbe(paths),
        log = InstallLog(paths)
    )

    private fun serve(bundle: File) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().readFrom(bundle.inputStream())))
    }

    @Test
    fun `inspect reports not installed initially`() = runBlocking {
        val installer = installer(catalog(distro(sha = "00".repeat(32), size = 10)))
        val info = installer.inspect()
        assertThat(info.state).isEqualTo(EnvironmentState.NOT_INSTALLED)
        assertThat(info.distroId).isEqualTo("alpine")
    }

    @Test
    fun `installs the bundle and reports ready`() = runBlocking {
        val bundle = buildTarGz()
        val sha = ChecksumVerifier().sha256(bundle)
        serve(bundle)
        val installer = installer(catalog(distro(sha = sha, size = bundle.length())))

        val events = installer.install("alpine") { }.toList()

        val completed = events.filterIsInstance<InstallEvent.Completed>().last()
        assertThat(completed.info.state).isEqualTo(EnvironmentState.READY)
        assertThat(completed.info.version).isEqualTo("3.22.2")
        assertThat(completed.info.distroName).isEqualTo("Alpine Linux")
        assertThat(File(paths.rootfs, "bin/sh").exists()).isTrue()
        assertThat(File(paths.rootfs, "etc/os-release").exists()).isTrue()
        assertThat(paths.stateFile.exists()).isTrue()
        assertThat(events.filterIsInstance<InstallEvent.Progress>()).isNotEmpty()
    }

    @Test
    fun `extraction reports the bytes it unpacked instead of zero`() = runBlocking {
        // The setup screen used to sit on "0 B · rootfs" for the whole minute an extraction takes,
        // because only the download phase published byte counts.
        val bundle = buildTarGz()
        val sha = ChecksumVerifier().sha256(bundle)
        serve(bundle)
        val installer = installer(catalog(distro(sha = sha, size = bundle.length())))

        val events = installer.install("alpine") { }.toList()

        val extracting = events.filterIsInstance<InstallEvent.Progress>()
            .map { it.progress }
            .filter { it.phase.startsWith("Extracting") }
        assertThat(extracting).isNotEmpty()
        val last = extracting.last()
        assertThat(last.bytesDownloaded).isGreaterThan(0)
        assertThat(last.totalBytes).isNotNull()
        assertThat(last.fraction).isNotNull()
    }

    @Test
    fun `installs a tar xz bundle and records its login shell`() = runBlocking {
        val bundle = buildTarXz("ubuntu")
        val sha = ChecksumVerifier().sha256(bundle)
        serve(bundle)
        val installer = installer(
            catalog(
                distro(
                    id = "ubuntu",
                    name = "Ubuntu",
                    version = "24.04.1 LTS",
                    shell = "/bin/bash",
                    sha = sha,
                    size = bundle.length(),
                    archive = ArchiveFormat.TAR_XZ,
                    expected = listOf("rootfs/bin/sh", "rootfs/bin/bash", "rootfs/etc/os-release")
                )
            )
        )

        val events = installer.install("ubuntu") { }.toList()

        val completed = events.filterIsInstance<InstallEvent.Completed>().last()
        assertThat(completed.info.state).isEqualTo(EnvironmentState.READY)
        assertThat(completed.info.distroId).isEqualTo("ubuntu")
        assertThat(File(paths.rootfs, "bin/bash").exists()).isTrue()
        assertThat(TerminalInstallStore(paths, json).read()?.shell).isEqualTo("/bin/bash")
    }

    @Test
    fun `installs a bundle whose bin slash sh is an absolute symlink`() = runBlocking {
        val bundle = buildRealisticTarGz()
        val sha = ChecksumVerifier().sha256(bundle)
        serve(bundle)
        val installer = installer(catalog(distro(sha = sha, size = bundle.length())))

        val events = installer.install(null) { }.toList()

        val completed = events.filterIsInstance<InstallEvent.Completed>().last()
        assertThat(completed.info.state).isEqualTo(EnvironmentState.READY)
        // The symlink is materialised without following its (host-dangling) absolute target.
        assertThat(java.nio.file.Files.isSymbolicLink(File(paths.rootfs, "bin/sh").toPath())).isTrue()
        assertThat(installer.inspect().state).isEqualTo(EnvironmentState.READY)
    }

    @Test
    fun `switching distribution replaces the previous rootfs`() = runBlocking {
        val alpine = buildTarGz("alpine")
        val alpineSha = ChecksumVerifier().sha256(alpine)
        serve(alpine)
        val alpineDistro = distro(sha = alpineSha, size = alpine.length())
        val ubuntu = buildTarXz("ubuntu")
        val ubuntuSha = ChecksumVerifier().sha256(ubuntu)
        val ubuntuDistro = distro(
            id = "ubuntu",
            name = "Ubuntu",
            version = "24.04.1 LTS",
            shell = "/bin/bash",
            sha = ubuntuSha,
            size = ubuntu.length(),
            archive = ArchiveFormat.TAR_XZ,
            expected = listOf("rootfs/bin/sh", "rootfs/bin/bash", "rootfs/etc/os-release")
        )
        val installer = installer(catalog(alpineDistro, ubuntuDistro))
        installer.install("alpine") { }.toList()

        serve(ubuntu)
        val events = installer.install("ubuntu") { }.toList()

        assertThat(events.filterIsInstance<InstallEvent.Completed>()).isNotEmpty()
        val info = installer.inspect()
        assertThat(info.distroId).isEqualTo("ubuntu")
        // The old distribution's files are gone, not merged with the new rootfs.
        assertThat(File(paths.rootfs, "bin/busybox").exists()).isFalse()
        assertThat(File(paths.rootfs, "etc/os-release").readText()).isEqualTo("ubuntu")
    }

    @Test
    fun `an unknown distribution id fails without downloading`() = runBlocking {
        val installer = installer(catalog(distro(sha = "00".repeat(32), size = 10)))

        val events = installer.install("gentoo") { }.toList()

        assertThat(events.last()).isInstanceOf(InstallEvent.Failed::class.java)
        assertThat((events.last() as InstallEvent.Failed).message).contains("Unknown distribution")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a bundle without a checksum is refused`() = runBlocking {
        val installer = installer(catalog(distro(sha = "", size = 10)))

        val events = installer.install("alpine") { }.toList()

        assertThat(events.last()).isInstanceOf(InstallEvent.Failed::class.java)
        assertThat((events.last() as InstallEvent.Failed).message).contains("checksum")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `availableDistros reports support and the installed one`() = runBlocking {
        val bundle = buildTarGz()
        val sha = ChecksumVerifier().sha256(bundle)
        serve(bundle)
        val supported = distro(sha = sha, size = bundle.length())
        val other = distro(id = "arm-only", name = "Arm Only", sha = sha, size = bundle.length())
        val installer = installer(catalog(supported, other))
        installer.install("alpine") { }.toList()

        val options = installer.availableDistros()

        assertThat(options.map { it.id }).containsExactly("alpine", "arm-only")
        assertThat(options.first { it.id == "alpine" }.installed).isTrue()
        assertThat(options.first { it.id == "alpine" }.supported).isTrue()
        assertThat(options.first { it.id == "arm-only" }.installed).isFalse()
    }

    @Test
    fun `insufficient storage fails before any download`() = runBlocking {
        val bundle = buildTarGz()
        val sha = ChecksumVerifier().sha256(bundle)
        val installer = installer(catalog(distro(sha = sha, size = bundle.length())), freeBytes = 1)

        val events = installer.install("alpine") { }.toList()

        val failed = events.last() as InstallEvent.Failed
        assertThat(failed.message).contains("Not enough storage")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `checksum mismatch fails and leaves nothing installed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().writeUtf8("not the bundle")))
        val installer = installer(catalog(distro(sha = "00".repeat(32), size = 100)))

        val events = installer.install("alpine") { }.toList()

        assertThat(events.last()).isInstanceOf(InstallEvent.Failed::class.java)
        assertThat(installer.inspect().state).isEqualTo(EnvironmentState.NOT_INSTALLED)
        val staging = paths.root.listFiles { f -> f.name.startsWith(TerminalPaths.STAGING_PREFIX) }
        assertThat(staging?.toList()).isEmpty()
    }

    @Test
    fun `uninstall removes the environment`() = runBlocking {
        val bundle = buildTarGz()
        val sha = ChecksumVerifier().sha256(bundle)
        serve(bundle)
        val installer = installer(catalog(distro(sha = sha, size = bundle.length())))
        installer.install("alpine") { }.toList()

        installer.uninstall()

        assertThat(installer.inspect().state).isEqualTo(EnvironmentState.NOT_INSTALLED)
        assertThat(paths.rootfs.exists()).isFalse()
        assertThat(paths.stateFile.exists()).isFalse()
    }

    @Test
    fun `clean incomplete removes staging and a non-ready record`() = runBlocking {
        paths.ensureRoot()
        File(paths.root, TerminalPaths.STAGING_PREFIX + "alpine-3.22.2").mkdirs()
        TerminalInstallStore(paths, json).write(InstallRecord(state = EnvironmentState.FAILED.name))
        val installer = installer(catalog(distro(sha = "00".repeat(32), size = 10)))

        installer.cleanIncomplete()

        assertThat(paths.root.listFiles { f -> f.name.startsWith(TerminalPaths.STAGING_PREFIX) }?.toList()).isEmpty()
        assertThat(TerminalInstallStore(paths, json).read()).isNull()
    }

    @Test
    fun `missing catalog fails cleanly`() = runBlocking {
        val installer = installer(null)
        val events = installer.install("alpine") { }.toList()
        assertThat(events.last()).isInstanceOf(InstallEvent.Failed::class.java)
    }

    @Test
    fun `an installed environment whose sandbox cannot start reports the fallback`() = runBlocking {
        seedReadyInstall(
            InstallRecord(
                state = EnvironmentState.READY.name,
                version = "3.22.2",
                abi = "arm64-v8a",
                distroId = "alpine",
                distroName = "Alpine Linux",
                runtimeExecutable = false,
                runtimeDetail = RuntimeCapabilityProbe.SANDBOX_BLOCKED_DETAIL,
                probeVersion = 2
            )
        )
        val installer = installer(catalog(distro(sha = "00".repeat(32), size = 10)))

        val info = installer.inspect()

        assertThat(info.state).isEqualTo(EnvironmentState.READY)
        assertThat(info.runtimeUsable).isFalse()
        assertThat(info.detail).contains("built-in shell")
    }

    @Test
    fun `a record written by an older probe is re-checked`() = runBlocking {
        // The old probe only ran `proot --version`, which succeeds even when the sandbox cannot start.
        seedReadyInstall(
            InstallRecord(
                state = EnvironmentState.READY.name,
                version = "3.22.2",
                abi = "arm64-v8a",
                distroId = "alpine",
                distroName = "Alpine Linux",
                runtimeExecutable = true,
                probeVersion = 0
            )
        )
        val installer = installer(catalog(distro(sha = "00".repeat(32), size = 10)))

        val info = installer.inspect()

        // No launcher is bundled in the test environment, so the launch probe must overturn the stale
        // "executable" claim rather than trust it.
        assertThat(info.runtimeUsable).isFalse()
        assertThat(TerminalInstallStore(paths, json).read()?.runtimeExecutable).isFalse()
    }

    /** Writes a READY record plus the files `inspect` expects an installed rootfs to contain. */
    private fun seedReadyInstall(record: InstallRecord) {
        paths.ensureRoot()
        File(paths.rootfs, "bin").mkdirs()
        File(paths.rootfs, "etc").mkdirs()
        File(paths.rootfs, "bin/sh").writeText("fake-sh")
        File(paths.rootfs, "bin/busybox").writeText("fake-busybox")
        File(paths.rootfs, "etc/os-release").writeText("alpine")
        TerminalInstallStore(paths, json).write(record)
    }
}
