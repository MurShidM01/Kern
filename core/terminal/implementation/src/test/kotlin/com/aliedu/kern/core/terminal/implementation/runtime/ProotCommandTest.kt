package com.aliedu.kern.core.terminal.implementation.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Asserts the proot argv/env shape without needing a real proot binary. These are the exact arguments
 * the launcher must receive (PRD 12.2): fake root, the Android pseudo-filesystems and primary
 * external storage bound in, run inside the rootfs, start in the workspace, launch a login shell.
 */
class ProotCommandTest {

    private val launcher = "/data/app/lib/arm64/libproot.so"
    private val rootfs = "/data/data/app/files/terminal/rootfs"

    @Test
    fun `build produces the expected proot argv`() {
        val argv = ProotCommand.build(
            launcher = launcher,
            rootfs = rootfs,
            guestCwd = "/sdcard/Kern",
            bindings = ProotCommand.bindings("/storage/emulated/0/Kern")
        )

        assertThat(argv).containsExactly(
            launcher,
            "--kill-on-exit",
            "--link2symlink",
            "-r", rootfs,
            "-0",
            "-w", "/sdcard/Kern",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/system",
            "-b", "/storage/emulated/0:/sdcard",
            "/bin/sh", "-l"
        ).inOrder()
    }

    @Test
    fun `launcher is the first argument and rootfs follows -r`() {
        val argv = ProotCommand.build(launcher, "/r", "/w", emptyList())
        assertThat(argv.first()).isEqualTo(launcher)
        assertThat(argv[argv.indexOf("-r") + 1]).isEqualTo("/r")
    }

    @Test
    fun `guest cwd follows the -w flag`() {
        val argv = ProotCommand.build(launcher, "/r", "/sdcard/some/dir", emptyList())
        assertThat(argv[argv.indexOf("-w") + 1]).isEqualTo("/sdcard/some/dir")
    }

    @Test
    fun `shell defaults to bin sh as a login shell`() {
        val argv = ProotCommand.build(launcher, "/r", "/w", emptyList())
        assertThat(argv.takeLast(2)).containsExactly("/bin/sh", "-l").inOrder()
    }

    @Test
    fun `a custom shell is honoured`() {
        val argv = ProotCommand.build(launcher, "/r", "/w", emptyList(), shell = "/bin/ash")
        assertThat(argv.takeLast(2)).containsExactly("/bin/ash", "-l").inOrder()
    }

    @Test
    fun `guestPathFor maps a workspace under primary external storage`() {
        assertThat(ProotCommand.guestPathFor("/storage/emulated/0/Kern/src")).isEqualTo("/sdcard/Kern/src")
        assertThat(ProotCommand.guestPathFor("/storage/emulated/0")).isEqualTo("/sdcard")
        assertThat(ProotCommand.guestPathFor("/storage/emulated/0/")).isEqualTo("/sdcard")
    }

    @Test
    fun `guestPathFor falls back for a workspace outside external storage`() {
        assertThat(ProotCommand.guestPathFor("/data/data/app/files/execution/abc")).isEqualTo("/workspace")
    }


    @Test
    fun `a project inside the projects root is mapped below workspace`() {
        // The whole projects root is bound at /workspace, so every project the user created is a
        // sibling there: /workspace/first_app, /workspace/java_apps, and so on.
        val root = "/data/data/app/files/workspaces"
        assertThat(ProotCommand.guestPathFor("$root/first_app", projectsRoot = root))
            .isEqualTo("/workspace/first_app")
        assertThat(ProotCommand.guestPathFor(root, projectsRoot = root)).isEqualTo("/workspace")
        // A path outside the root keeps the old single-workspace fallback.
        assertThat(ProotCommand.guestPathFor("/data/data/app/files/execution/abc", projectsRoot = root))
            .isEqualTo("/workspace")
    }

    @Test
    fun `bindings mount the projects root, not the project`() {
        val root = "/data/data/app/files/workspaces"
        val bindings = ProotCommand.bindings("$root/first_app", projectsRoot = root)
        assertThat(bindings).contains("$root:/workspace")
        assertThat(bindings).doesNotContain("$root/first_app:/workspace")
    }

    @Test
    fun `bindings expose primary external storage at sdcard`() {
        val bindings = ProotCommand.bindings("/storage/emulated/0/Kern")
        assertThat(bindings).contains("/storage/emulated/0:/sdcard")
        assertThat(bindings).containsAtLeast("/dev", "/proc", "/sys", "/system")
        // A workspace already under /sdcard needs no extra binding.
        assertThat(bindings).doesNotContain("/storage/emulated/0/Kern:/workspace")
    }

    @Test
    fun `bindings add a fallback mount for a workspace outside external storage`() {
        val bindings = ProotCommand.bindings("/data/data/app/files/execution/abc")
        assertThat(bindings).contains("/data/data/app/files/execution/abc:/workspace")
    }

    @Test
    fun `bindings include the resolver file when provided`() {
        val bindings = ProotCommand.bindings("/storage/emulated/0/Kern", resolvConf = "/data/r.conf")
        assertThat(bindings).contains("/data/r.conf:/etc/resolv.conf")
    }

    @Test
    fun `environment sets the proot runtime variables`() {
        val env = ProotCommand.environment(
            home = "/root",
            tmpDir = "/data/tmp",
            nativeLibraryDir = "/data/app/lib/arm64",
            guestCwd = "/sdcard/Kern",
            loaderPath = "/data/app/lib/arm64/libproot-loader.so"
        )

        assertThat(env["HOME"]).isEqualTo("/root")
        assertThat(env["PWD"]).isEqualTo("/sdcard/Kern")
        assertThat(env["TERM"]).isEqualTo("xterm-256color")
        assertThat(env["PROOT_TMP_DIR"]).isEqualTo("/data/tmp")
        assertThat(env["PROOT_NO_SECCOMP"]).isEqualTo("1")
        assertThat(env["LD_LIBRARY_PATH"]).isEqualTo("/data/app/lib/arm64")
        assertThat(env["PROOT_LOADER"]).isEqualTo("/data/app/lib/arm64/libproot-loader.so")
        assertThat(env["PATH"]).contains("/usr/bin")
        assertThat(env["PS1"]).isEqualTo("$ ")
    }

    @Test
    fun `environment omits the loader when it is not bundled`() {
        val env = ProotCommand.environment(
            home = "/root",
            tmpDir = "/t",
            nativeLibraryDir = "/lib",
            guestCwd = "/sdcard"
        )
        assertThat(env).doesNotContainKey("PROOT_LOADER")
    }

    @Test
    fun `resolvConf lists the given nameservers`() {
        val text = ProotCommand.resolvConf(listOf("8.8.8.8", "1.1.1.1"))
        assertThat(text).contains("nameserver 8.8.8.8")
        assertThat(text).contains("nameserver 1.1.1.1")
    }

    @Test
    fun `probe enters the guest with a trivial command`() {
        assertThat(ProotCommand.probe(launcher, rootfs)).containsExactly(
            launcher,
            "--kill-on-exit",
            "--link2symlink",
            "-r", rootfs,
            "-0",
            "-w", "/",
            "/bin/true"
        ).inOrder()
    }

    @Test
    fun `probe never starts a login shell`() {
        // A login shell would wait for input, so the probe would time out instead of exiting — which
        // is exactly the case it must be able to detect.
        assertThat(ProotCommand.probe(launcher, rootfs)).doesNotContain("-l")
    }
}
