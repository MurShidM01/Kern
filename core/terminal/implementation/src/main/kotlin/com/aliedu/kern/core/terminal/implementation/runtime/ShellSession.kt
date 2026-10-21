package com.aliedu.kern.core.terminal.implementation.runtime

import com.aliedu.kern.core.terminal.api.SessionKind
import com.aliedu.kern.core.terminal.api.TerminalOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A live shell process attached to a pseudo-terminal. Output is streamed to subscribers and mirrored
 * into a bounded ring buffer so the UI can reconstruct the screen after navigating away and back
 * (PRD 12.4). The PTY merges stdout and stderr, so a single pump feeds both.
 */
class ShellSession(
    val id: String,
    displayName: String,
    val cwd: String,
    val kind: SessionKind,
    private val process: TerminalProcess,
    scope: CoroutineScope,
    private val ringLimit: Int = 256 * 1024
) {
    @Volatile
    var displayName: String = displayName

    private val _output = MutableSharedFlow<TerminalOutput>(extraBufferCapacity = 1024)
    val output: SharedFlow<TerminalOutput> = _output.asSharedFlow()

    private val ring = StringBuilder()
    private val ringLock = Any()

    @Volatile
    var exitCode: Int? = null
        private set

    val isAlive: Boolean get() = process.isAlive

    init {
        scope.launch(Dispatchers.IO) { pump() }
        scope.launch(Dispatchers.IO) {
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            exitCode = code
            _output.emit(TerminalOutput.Exited(code))
        }
    }

    private suspend fun pump() {
        val decoder = StreamingUtf8()
        val buf = ByteArray(8192)
        try {
            while (currentCoroutineContext().isActive) {
                val n = process.read(buf)
                if (n < 0) break
                if (n == 0) continue // no data yet; the poll window keeps this from spinning
                val text = decoder.decode(buf, n)
                if (text.isNotEmpty()) {
                    appendRing(text)
                    // `emit`, never `tryEmit`: a bounded SharedFlow *drops* values once its buffer is
                    // full, and a terminal must never lose output. A fast producer such as
                    // `apt-cache pkgnames` (~60k lines in ~10s) easily outruns its collector, which silently
                    // truncated the package catalog. Suspending here applies backpressure through the
                    // PTY instead, so the guest is throttled rather than the output corrupted.
                    _output.emit(TerminalOutput.Stdout(text))
                }
            }
        } finally {
            runCatching { process.close() }
        }
    }

    private fun appendRing(text: String) {
        synchronized(ringLock) {
            ring.append(text)
            if (ring.length > ringLimit) ring.delete(0, ring.length - ringLimit)
        }
    }

    fun replay(): String = synchronized(ringLock) { ring.toString() }

    fun write(bytes: ByteArray) {
        runCatching { process.write(bytes) }
    }

    /** Tell the guest the terminal was resized (SIGWINCH). */
    fun resize(columns: Int, rows: Int) {
        runCatching { process.setWindowSize(rows, columns) }
    }

    /** Interrupt the session: polite SIGTERM, then a forced kill if it does not exit promptly. */
    fun requestStop() {
        process.terminate()
    }

    fun close() {
        process.terminate()
        process.close()
    }
}
