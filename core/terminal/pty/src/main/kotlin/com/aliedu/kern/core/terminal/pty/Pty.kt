package com.aliedu.kern.core.terminal.pty

/**
 * Thin JNI surface over a real pseudo-terminal (see `src/main/cpp/pty.c`).
 *
 * A session is created with [createSubprocess], which forks the guest and returns the **master** fd;
 * the guest's pid is written into `pidOut`. Output is read from the master fd ([read]) and input is
 * written to it ([write]); the guest's stdout and stderr are merged onto the same stream. [read]
 * returns [READ_TIMEOUT] when no data arrived within the internal poll window, so the caller can loop
 * and observe coroutine cancellation rather than blocking forever.
 */
object Pty {

    /** No data within the poll window; the caller should loop and re-check cancellation. */
    const val READ_TIMEOUT: Int = -2

    /** The fd is closed or errored; the session is finished. */
    const val READ_ERROR: Int = -1

    init {
        System.loadLibrary("kernpty")
    }

    /**
     * Fork [argv] under a new PTY rooted at [cwd] with [env] (`NAME=VALUE` entries) and an initial
     * window size of [rows] x [cols].
     *
     * @param pidOut a 1-element array that receives the child's pid on success.
     * @return the master fd, or -1 on failure.
     */
    external fun createSubprocess(
        argv: Array<String>,
        cwd: String,
        env: Array<String>,
        rows: Int,
        cols: Int,
        pidOut: IntArray
    ): Int

    /** Resize the terminal window; the guest receives SIGWINCH. */
    external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int)

    /** Read up to [length] bytes into [buffer]; see [READ_TIMEOUT] / [READ_ERROR] / 0 (EOF). */
    external fun read(fd: Int, buffer: ByteArray, length: Int): Int

    /** Write [length] bytes from [buffer]; returns the number written or [READ_ERROR]. */
    external fun write(fd: Int, buffer: ByteArray, length: Int): Int

    /** Block until the child exits; returns its exit code (128 + signal when signalled). */
    external fun waitFor(pid: Int): Int

    /** Send SIGHUP to the child's process group. */
    external fun hangupProcessGroup(pid: Int)

    /** Send [signal] (e.g. SIGTERM=15, SIGKILL=9) to the child's process group. */
    external fun killProcessGroup(pid: Int, signal: Int)

    /** Close the master fd. */
    external fun close(fd: Int)

    const val SIGTERM: Int = 15
    const val SIGKILL: Int = 9
}
