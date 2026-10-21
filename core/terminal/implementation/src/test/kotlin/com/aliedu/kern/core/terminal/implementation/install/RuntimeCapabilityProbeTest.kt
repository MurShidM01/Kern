package com.aliedu.kern.core.terminal.implementation.install

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeCapabilityProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun probe() = RuntimeCapabilityProbe(
        TerminalPaths(tmp.newFolder("terminal"), tmp.newFolder("lib"))
    )

    @Test
    fun `missing binary is reported as not executable`() = runBlocking {
        val result = probe().probe(listOf("definitely-missing-binary"))
        assertThat(result.executable).isFalse()
        assertThat(result.detail).contains("missing")
    }

    @Test
    fun `a non-executable file does not crash the probe`() = runBlocking {
        val file = tmp.newFile("not-a-binary.txt")
        file.writeText("hello")
        val result = probe().probe(listOf(file.absolutePath, "--version"))
        // On any platform this cannot run successfully; the probe must report a reason, not throw.
        assertThat(result.executable).isFalse()
        assertThat(result.detail).isNotEmpty()
    }

    @Test
    fun `a missing rootfs is reported rather than launched`() = runBlocking {
        val result = probe().probeLaunch(java.io.File(tmp.root, "missing-rootfs"))
        assertThat(result.executable).isFalse()
        assertThat(result.detail).contains("rootfs")
    }

    @Test
    fun `an unbundled launcher is reported rather than launched`() = runBlocking {
        val result = probe().probeLaunch(tmp.newFolder("rootfs"))
        assertThat(result.executable).isFalse()
        assertThat(result.detail).contains("launcher")
    }

    @Test
    fun `the launch argv names the launcher exactly once`() {
        // proot reads a second copy of the launcher as the guest program and dies with SIGSEGV,
        // which is indistinguishable from a device that cannot run the runtime at all.
        val launcher = java.io.File("/lib/libproot.so")
        val rootfs = java.io.File("/data/terminal/rootfs")
        val argv = probe().launchArgv(launcher, rootfs)

        assertThat(argv.count { it == launcher.absolutePath }).isEqualTo(1)
        assertThat(argv.first()).isEqualTo(launcher.absolutePath)
        assertThat(argv).containsAtLeast("-r", rootfs.absolutePath, "-0").inOrder()
    }

    @Test
    fun `a ptrace denial is reported as a blocked sandbox`() {
        val detail = describeProbeFailure(
            "proot error: ptrace(TRACEME): Permission denied\nproot error: execve(\"/bin/sh\")",
            "The runtime exited with code 1"
        )
        assertThat(detail).contains("ptrace is blocked for apps")
    }

    @Test
    fun `an exec denial is reported as an exec restriction`() {
        val detail = describeProbeFailure("sh: Permission denied", "The runtime exited with code 126")
        assertThat(detail).contains("exec restriction")
    }

    @Test
    fun `an unrecognised failure keeps the exit reason and the output`() {
        val detail = describeProbeFailure("some other problem", "The runtime exited with code 7")
        assertThat(detail).contains("code 7")
        assertThat(detail).contains("some other problem")
    }
}
