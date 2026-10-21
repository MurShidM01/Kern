package com.aliedu.kern.core.terminal.implementation.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the [AlpineProotBackend] gating without an Android [android.content.Context]: a session may
 * only start when an executable launcher and the extracted rootfs both exist (plan risk R1).
 */
class ProotLauncherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun executable(dir: File, name: String): File =
        File(dir, name).apply { parentFile?.mkdirs(); createNewFile(); setExecutable(true) }

    @Test
    fun `resolve returns null when no launcher exists anywhere`() {
        val native = tmp.newFolder("lib")
        val runtime = tmp.newFolder("runtime")
        assertThat(ProotLauncher.resolve(native, runtime)).isNull()
    }

    @Test
    fun `resolve finds the native library launcher`() {
        val native = tmp.newFolder("lib")
        val runtime = tmp.newFolder("runtime")
        val launcher = executable(native, "libproot.so")
        assertThat(ProotLauncher.resolve(native, runtime)).isEqualTo(launcher)
    }

    @Test
    fun `resolve falls back to a launcher in the runtime dir`() {
        val native = tmp.newFolder("lib")
        val runtime = tmp.newFolder("runtime")
        val launcher = executable(runtime, "proot")
        assertThat(ProotLauncher.resolve(native, runtime)).isEqualTo(launcher)
    }

    @Test
    fun `resolve prefers the native library launcher over the runtime dir`() {
        val native = tmp.newFolder("lib")
        val runtime = tmp.newFolder("runtime")
        val preferred = executable(native, "libproot.so")
        executable(runtime, "proot")
        assertThat(ProotLauncher.resolve(native, runtime)).isEqualTo(preferred)
    }

    @Test
    fun `isAvailable is false when a launcher exists but the rootfs is missing`() {
        val native = tmp.newFolder("lib")
        val runtime = tmp.newFolder("runtime")
        executable(native, "libproot.so")
        val missingRootfs = File(tmp.root, "rootfs")
        assertThat(ProotLauncher.isAvailable(native, runtime, missingRootfs)).isFalse()
    }

    @Test
    fun `isAvailable is false when the rootfs exists but no launcher does`() {
        val native = tmp.newFolder("lib")
        val runtime = tmp.newFolder("runtime")
        val rootfs = tmp.newFolder("rootfs")
        assertThat(ProotLauncher.isAvailable(native, runtime, rootfs)).isFalse()
    }

    @Test
    fun `isAvailable is true when both the launcher and rootfs exist`() {
        val native = tmp.newFolder("lib")
        val runtime = tmp.newFolder("runtime")
        executable(native, "libproot.so")
        val rootfs = tmp.newFolder("rootfs")
        assertThat(ProotLauncher.isAvailable(native, runtime, rootfs)).isTrue()
    }

    @Test
    fun `native launcher names are lib-prefixed so Android extracts them from the APK`() {
        assertThat(ProotLauncher.NATIVE_NAMES).isNotEmpty()
        assertThat(ProotLauncher.NATIVE_NAMES).contains("libproot.so")
        assertThat(ProotLauncher.NATIVE_NAMES.all { it.startsWith("lib") && it.endsWith(".so") }).isTrue()
    }

    @Test
    fun `resolveLoader finds the bundled guest loader in the native library dir`() {
        val native = tmp.newFolder("lib")
        val runtime = tmp.newFolder("runtime")
        val loader = File(native, ProotLauncher.LOADER_NAME).apply { createNewFile() }
        assertThat(ProotLauncher.resolveLoader(native, runtime)).isEqualTo(loader)
    }

    @Test
    fun `resolveLoader falls back to the runtime dir and is null when absent`() {
        val native = tmp.newFolder("lib")
        val runtime = tmp.newFolder("runtime")
        assertThat(ProotLauncher.resolveLoader(native, runtime)).isNull()
        val loader = File(runtime, ProotLauncher.LOADER_NAME).apply { createNewFile() }
        assertThat(ProotLauncher.resolveLoader(native, runtime)).isEqualTo(loader)
    }

    @Test
    fun `resolveTalloc finds the bundled talloc shared object`() {
        val native = tmp.newFolder("lib")
        assertThat(ProotLauncher.resolveTalloc(native)).isNull()
        val talloc = File(native, ProotLauncher.TALLOC_NAME).apply { createNewFile() }
        assertThat(ProotLauncher.resolveTalloc(native)).isEqualTo(talloc)
    }

    @Test
    fun `bundled names are lib-prefixed so Android extracts them from the APK`() {
        assertThat(ProotLauncher.LOADER_NAME.startsWith("lib") && ProotLauncher.LOADER_NAME.endsWith(".so")).isTrue()
        assertThat(ProotLauncher.TALLOC_NAME.startsWith("lib") && ProotLauncher.TALLOC_NAME.endsWith(".so")).isTrue()
    }
}
