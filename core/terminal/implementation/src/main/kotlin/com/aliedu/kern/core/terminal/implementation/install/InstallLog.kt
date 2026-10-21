package com.aliedu.kern.core.terminal.implementation.install

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Append-only install log surfaced by the environment setup screen (PRD 12.4 precise failure reason). */
@Singleton
class InstallLog @Inject constructor(private val paths: TerminalPaths) {

    fun append(message: String) {
        paths.ensureRoot()
        runCatching { paths.logFile.appendText("${stamp()} $message\n") }
    }

    fun read(): String = runCatching { paths.logFile.readText() }.getOrDefault("")

    fun clear() {
        runCatching { paths.logFile.delete() }
    }

    private fun stamp(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
}
