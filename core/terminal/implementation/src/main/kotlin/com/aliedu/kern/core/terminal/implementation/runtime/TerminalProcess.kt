package com.aliedu.kern.core.terminal.implementation.runtime

/**
 * A live guest process attached to a pseudo-terminal. Abstracts [PtyProcess] so session logic can be
 * unit-tested on the JVM with a fake, without the native PTY library.
 *
 * The guest's stdout and stderr are merged onto one stream; there is no separate error channel.
 */
interface TerminalProcess {

    /** False once the process has exited or [close] has been called. */
    val isAlive: Boolean

    /**
     * Blocking read into [buffer]. Returns the number of bytes read (> 0), `0` when no data was
     * available within the poll window (the caller should loop), or `-1` at EOF / on error.
     */
    fun read(buffer: ByteArray): Int

    fun write(bytes: ByteArray)

    /** Resize the terminal window; the guest receives SIGWINCH. */
    fun setWindowSize(rows: Int, cols: Int)

    /** Block until the guest exits; returns its exit code. */
    fun waitFor(): Int

    /** Polite SIGTERM, escalating to SIGKILL if the guest does not exit promptly. */
    fun terminate()

    /** Release the PTY fd and hang up the guest. Idempotent. */
    fun close()
}
