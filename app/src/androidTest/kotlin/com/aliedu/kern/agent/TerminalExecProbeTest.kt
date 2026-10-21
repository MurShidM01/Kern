package com.aliedu.kern.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Diagnostic for the Part 2 Linux runtime (plan risk R1). Android 10+ restricts `execve` of files
 * in an app's data directory for apps targeting API 29+. This probe runs in the real app process
 * (untrusted_app domain) and reports whether a binary copied into the app's files dir can be executed.
 *
 * It deliberately records the outcome rather than asserting, so it can be run on any device to
 * decide the runtime strategy (download proot vs ship it as a native lib).
 */
@RunWith(AndroidJUnit4::class)
class TerminalExecProbeTest {

    @Test
    fun probeExecFromAppData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val src = File("/system/bin/sh")
        val dst = File(context.filesDir, "exec-probe")

        val setup = runCatching {
            src.inputStream().use { input -> dst.outputStream().use { output -> input.copyTo(output) } }
            dst.setExecutable(true, false)
        }
        if (setup.isFailure) {
            println("EXEC_PROBE setup_failed=${setup.exceptionOrNull()}")
            return
        }

        val result = runCatching {
            val process = ProcessBuilder(dst.absolutePath, "-c", "echo PROBE_OK")
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText().trim()
            val code = process.waitFor()
            "exit=$code output=$out"
        }
        println("EXEC_PROBE filesDir exec=${dst.absolutePath} result=$result")
        println("EXEC_PROBE verdict=" + if (result.getOrNull()?.contains("PROBE_OK") == true) "ALLOWED" else "BLOCKED")
    }
}
