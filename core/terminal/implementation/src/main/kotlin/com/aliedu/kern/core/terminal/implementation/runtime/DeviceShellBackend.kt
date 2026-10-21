package com.aliedu.kern.core.terminal.implementation.runtime

import android.content.Context
import com.aliedu.kern.core.terminal.api.ExecutionWorkspace
import com.aliedu.kern.core.terminal.api.SessionKind
import com.aliedu.kern.core.terminal.pty.Pty
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Always-available backend: Android's own `/system/bin/sh` (mksh), started on a real pseudo-terminal
 * with the workspace as its working directory. The PTY gives the shell a controlling terminal, so it
 * runs interactively (prompt, line editing, job control) instead of the old pipe-based shell that
 * complained "can't find tty fd". The PRD permits native shell tools where the app runtime and
 * Android permissions allow them (PRD 4.2). Used until Alpine is installed, and as the fallback if
 * Alpine cannot run on the device.
 */
@Singleton
class DeviceShellBackend @Inject constructor(
    @ApplicationContext private val context: Context
) : SessionBackend {

    override val kind: SessionKind = SessionKind.DEVICE_SHELL

    override suspend fun start(
        workspace: ExecutionWorkspace,
        displayName: String,
        scope: CoroutineScope
    ): ShellSession = withContext(Dispatchers.IO) {
        val dir = File(workspace.workingDirectory)
        if (!dir.exists()) dir.mkdirs()

        val rc = shellRcFile()
        val env = linkedMapOf(
            "HOME" to dir.absolutePath,
            "PWD" to dir.absolutePath,
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            "PATH" to DEFAULT_PATH,
            "PS1" to PROMPT,
            "ENV" to rc.absolutePath
        )

        val pidOut = IntArray(1)
        val fd = Pty.createSubprocess(
            argv = arrayOf(SHELL, "-i"),
            cwd = dir.absolutePath,
            env = env.map { (key, value) -> "$key=$value" }.toTypedArray(),
            rows = DEFAULT_ROWS,
            cols = DEFAULT_COLS,
            pidOut = pidOut
        )
        if (fd < 0) error("Could not start the device shell.")

        ShellSession(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            cwd = dir.absolutePath,
            kind = kind,
            process = PtyProcess(fd, pidOut[0]),
            scope = scope
        )
    }

    /**
     * A tiny mksh rc that pins the prompt to `$ `. Android's mksh otherwise prints the working
     * directory in the prompt, which is exactly the `/data/data/...` noise we want to avoid.
     */
    private fun shellRcFile(): File {
        val file = File(context.cacheDir, RC_NAME)
        if (!file.exists()) file.writeText("PS1='$ '\n")
        return file
    }

    private companion object {
        const val SHELL = "/system/bin/sh"
        const val RC_NAME = "kern-sh.rc"
        const val PROMPT = "$ "
        const val DEFAULT_ROWS = 24
        const val DEFAULT_COLS = 80
        const val DEFAULT_PATH =
            "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system/bin:/system/xbin"
    }
}
