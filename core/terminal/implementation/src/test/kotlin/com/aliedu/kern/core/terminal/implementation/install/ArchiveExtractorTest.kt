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
