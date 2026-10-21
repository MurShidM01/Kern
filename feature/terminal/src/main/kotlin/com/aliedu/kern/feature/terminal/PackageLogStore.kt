package com.aliedu.kern.feature.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Prefix of the executor's exit sentinel; its line must never be read as real output. */
private const val SENTINEL = "__KERN_DONE_"

/**
 * Process-wide buffer of the `apk` output that [PackageManagerViewModel] streams.
 *
 * The package list and the raw log live on two separate screens, so the log is kept here rather than
 * in the list screen's ViewModel: it survives navigating between them, and the log screen keeps
 * receiving lines while an install is still running.
 *
 * Lines are assembled whole (a PTY emits many tiny chunks) and the shell's own echo of the script is
 * dropped, so the panel shows only what the command actually printed.
 */
@Singleton
class PackageLogStore @Inject constructor() {

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val buffer = StringBuilder()
    private var command = ""
    private var done = false

    /** Start a run: clear the panel and show the command that is about to execute. */
    @Synchronized
    fun begin(command: String) {
        this.command = command
        done = false
        buffer.setLength(0)
        _lines.value = listOf("$ $command")
    }

    /** Append a raw PTY chunk, publishing every line that is now complete. */
    @Synchronized
    fun append(chunk: String) {
        if (chunk.isEmpty()) return
        buffer.append(chunk)
        val text = buffer.toString()
        val lastNewline = text.lastIndexOf('\n')
        if (lastNewline >= 0) {
            buffer.delete(0, lastNewline + 1)
            val fresh = text.substring(0, lastNewline).split('\n')
                .map { it.trimEnd('\r') }
                .filter { it.isNotBlank() && !isShellNoise(it) }
            if (fresh.isNotEmpty()) {
                _lines.value = (_lines.value + fresh).takeLast(LOG_LINES)
            }
        }
        // A single line longer than this is not useful in the panel; drop it rather than grow forever.
        if (buffer.length > MAX_PENDING_LINE) buffer.setLength(0)
    }

    @Synchronized
    fun clear() {
        buffer.setLength(0)
        command = ""
        done = false
        _lines.value = emptyList()
    }

    /**
     * The PTY echoes the script we typed, and the executor appends an exit sentinel after it. Real
     * output is interleaved between the script's two lines, so the executor cannot always strip the
     * echo from the live stream — drop those lines here so only the command's output remains.
     */
    private fun isShellNoise(line: String): Boolean {
        if (done) return true
        if (line.contains(SENTINEL)) {
            done = true
            return true
        }
        if (line.startsWith('$')) return true
        if (line == command || line.startsWith("printf ")) return true
        return false
    }

    private companion object {
        const val LOG_LINES = 400

        /** A partial line longer than this is dropped instead of buffered indefinitely. */
        const val MAX_PENDING_LINE = 8 * 1024
    }
}
