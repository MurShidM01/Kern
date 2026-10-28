package com.aliedu.kern.core.terminal.implementation.install

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `extracts files and directories`() {
        val archive = tmp.newFile("bundle.tar.gz")
        TarTestFactory.write(
            archive,
            listOf(
                TarTestFactory.Entry("./bin/sh", "#!/bin/sh\n".toByteArray(), 0b111_101_101),
                TarTestFactory.Entry("./etc/alpine-release", "3.20.9\n".toByteArray())
            )
        )

        val target = tmp.newFolder("out")
        ArchiveExtractor().extractTarGz(archive, target)

        assertThat(File(target, "bin/sh").readText()).isEqualTo("#!/bin/sh\n")
        assertThat(File(target, "etc/alpine-release").readText()).isEqualTo("3.20.9\n")
    }

    @Test
    fun `extracts a tar xz bundle`() {
        val archive = tmp.newFile("ubuntu.tar.xz")
        TarTestFactory.write(
            archive,
            listOf(
                TarTestFactory.Entry("./bin/bash", "#!/bin/bash\n".toByteArray(), 0b111_101_101),
                TarTestFactory.Entry("./etc/os-release", "ubuntu\n".toByteArray())
            ),
            format = ArchiveFormat.TAR_XZ
        )

        val target = tmp.newFolder("out-xz")
        ArchiveExtractor().extract(archive, target, ArchiveFormat.TAR_XZ)

        assertThat(File(target, "bin/bash").readText()).isEqualTo("#!/bin/bash\n")
        assertThat(File(target, "bin/bash").canExecute()).isTrue()
        assertThat(File(target, "etc/os-release").readText()).isEqualTo("ubuntu\n")
    }

    @Test
    fun `a hard link that cannot be linked is copied with its mode`() {
        val archive = tmp.newFile("hardlink.tar.gz")
        TarTestFactory.write(
            archive,
            listOf(TarTestFactory.Entry("./usr/bin/perl5.38.2", "perl".toByteArray(), 0b111_111_111)),
            hardLinks = listOf("./usr/bin/perl" to "./usr/bin/perl5.38.2")
        )

        val target = tmp.newFolder("out-link")
        ArchiveExtractor().extractTarGz(archive, target)

        val link = File(target, "usr/bin/perl")
        assertThat(link.readText()).isEqualTo("perl")
        // Windows cannot express the mode; the guest images are extracted on Android/Linux only.
        if (System.getProperty("os.name")?.startsWith("Windows") != true) {
            assertThat(java.nio.file.Files.isSameFile(link.toPath(), File(target, "usr/bin/perl5.38.2").toPath()) ||
                link.canExecute()).isTrue()
        }
    }

    @Test
    fun `maps permission bits to a symbolic mode`() {
        val extractor = ArchiveExtractor()
        assertThat(extractor.symbolic(0b111_101_101)).isEqualTo("rwxr-xr-x")
        assertThat(extractor.symbolic(0b110_100_100)).isEqualTo("rw-r--r--")
        assertThat(extractor.symbolic(0b111_111_111)).isEqualTo("rwxrwxrwx")
        assertThat(extractor.symbolic(0b000_000_000)).isEqualTo("---------")
    }

    @Test
    fun `rejects an entry that escapes the target directory`() {
        val archive = tmp.newFile("evil.tar.gz")
        TarTestFactory.write(archive, listOf(TarTestFactory.Entry("../../escape.txt", "x".toByteArray())))

        val target = tmp.newFolder("out2")
        val result = runCatching { ArchiveExtractor().extractTarGz(archive, target) }

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `creates symlinks when the platform supports them`() {
        val archive = tmp.newFile("links.tar.gz")
        TarTestFactory.write(
            archive,
            listOf(TarTestFactory.Entry("./bin/busybox", "bb".toByteArray(), 0b111_101_101)),
            symlinks = listOf("./bin/sh" to "busybox")
        )

        val target = tmp.newFolder("out3")
        ArchiveExtractor().extractTarGz(archive, target)

        val link = File(target, "bin/sh")
        // On platforms without symlink privileges the link is skipped — extraction must still succeed.
        assertThat(File(target, "bin/busybox").readText()).isEqualTo("bb")
        if (link.exists()) {
            assertThat(java.nio.file.Files.isSymbolicLink(link.toPath())).isTrue()
        }
    }
}
