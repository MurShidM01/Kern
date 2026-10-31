package com.aliedu.kern.core.terminal.implementation.runtime

import com.aliedu.kern.core.terminal.pty.Pty
import java.util.concurrent.atomic.AtomicInteger

/**
 * [TerminalProcess] backed by a real pseudo-terminal created through [Pty].
 *
 * @param masterFd the PTY master fd returned by [Pty.createSubprocess].
 * @param pid the guest's pid (also its process-group id, since the guest called `setsid`).
 */
class PtyProcess(
    private val masterFd: Int,
    private val pid: Int
) : TerminalProcess {

    @Volatile
    private var closed = false

    private val exitCode = AtomicInteger(EXIT_UNSET)

    override val isAlive: Boolean get() = !closed && exitCode.get() == EXIT_UNSET

    override fun read(buffer: ByteArray): Int {
        if (closed) return -1
        val n = Pty.read(masterFd, buffer, buffer.size)
        return when {
            n == Pty.READ_TIMEOUT -> 0
            n <= 0 -> -1 // 0 = EOF, -1 = error
            else -> n
        }
    }

    override fun write(bytes: ByteArray) {
        if (closed || bytes.isEmpty()) return
        Pty.write(masterFd, bytes, bytes.size)
    }

    override fun setWindowSize(rows: Int, cols: Int) {
        if (closed) return
        Pty.setPtyWindowSize(masterFd, rows, cols)
    }

    override fun waitFor(): Int {
        val code = Pty.waitFor(pid)
        exitCode.compareAndSet(EXIT_UNSET, code)
        return code
    }

    override fun terminate() {
        if (closed || !isAlive) return
        runCatching { Pty.killProcessGroup(pid, Pty.SIGTERM) }
        scheduleKill()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { Pty.hangupProcessGroup(pid) }
        runCatching { Pty.close(masterFd) }
        // The hangup normally ends the guest, but a process that ignores SIGHUP (a shell started
        // with nohup, a stuck dpkg) would outlive its session and keep the workspace locked. The
        // escalation must not depend on [isAlive]: close() sets [closed] first, so the old check
        // was always false here and the SIGKILL was never sent.
        scheduleKill()
    }

    /**
     * Sends SIGKILL to the process group unless the guest has already exited. Guarded by the exit
     * code rather than [closed], so closing a session does not cancel a kill that terminate() just
     * scheduled.
     */
    private fun scheduleKill() {
        Thread {
            runCatching { Thread.sleep(KILL_GRACE_MS) }
            if (exitCode.get() == EXIT_UNSET) {
                runCatching { Pty.killProcessGroup(pid, Pty.SIGKILL) }
            }
        }.apply {
            isDaemon = true
            name = "kern-pty-kill-$pid"
        }.start()
    }

    private companion object {
        const val EXIT_UNSET = Int.MIN_VALUE
        const val KILL_GRACE_MS = 700L
    }
}
