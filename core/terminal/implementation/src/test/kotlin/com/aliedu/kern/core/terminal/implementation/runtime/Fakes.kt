package com.aliedu.kern.core.terminal.implementation.runtime

import com.aliedu.kern.core.terminal.api.ExecutionWorkspace
import com.aliedu.kern.core.terminal.api.SessionKind
import kotlinx.coroutines.CoroutineScope
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** An in-memory [TerminalProcess] so session logic can be tested without spawning a real shell. */
class FakeTerminalProcess : TerminalProcess {

    private val lock = Object()
    private val data = ArrayDeque<Byte>()

    @Volatile
    private var alive = true

    @Volatile
    private var code = 0

    val stdin = ByteArrayOutputStream()

    @Volatile
    var lastRows: Int = 0
        private set

    @Volatile
    var lastCols: Int = 0
        private set

    override val isAlive: Boolean get() = alive

    override fun read(buffer: ByteArray): Int = synchronized(lock) {
        if (data.isEmpty()) {
            if (!alive) return -1
            lock.wait(POLL_MS)
            if (data.isEmpty()) return if (alive) 0 else -1
        }
        var n = 0
        while (n < buffer.size && data.isNotEmpty()) {
            buffer[n++] = data.removeFirst()
        }
        n
    }

    override fun write(bytes: ByteArray) {
        stdin.write(bytes)
    }

    override fun setWindowSize(rows: Int, cols: Int) {
        lastRows = rows
        lastCols = cols
    }

    override fun waitFor(): Int = synchronized(lock) {
        while (alive) lock.wait(POLL_MS)
        code
    }

    override fun terminate() = finish(143)

    override fun close() = finish(code)

    fun emit(text: String) {
        synchronized(lock) {
            text.toByteArray(Charsets.UTF_8).forEach { data.addLast(it) }
            lock.notifyAll()
        }
    }

    fun finish(exitCode: Int) {
        synchronized(lock) {
            alive = false
            code = exitCode
            lock.notifyAll()
        }
    }

    private companion object {
        const val POLL_MS = 20L
    }
}

/** A [SessionBackend] that hands out [FakeTerminalProcess]-backed sessions. */
class FakeBackend(
    override val kind: SessionKind = SessionKind.DEVICE_SHELL,
    private val available: Boolean = true
) : SessionBackend {
    val processes = CopyOnWriteArrayList<FakeTerminalProcess>()

    override fun isAvailable(): Boolean = available

    override suspend fun start(
        workspace: ExecutionWorkspace,
        displayName: String,
        scope: CoroutineScope
    ): ShellSession {
        val process = FakeTerminalProcess()
        processes.add(process)
        return ShellSession(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            cwd = workspace.workingDirectory,
            kind = kind,
            process = process,
            scope = scope
        )
    }
}

fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (condition()) return true
        Thread.sleep(10)
    }
    return condition()
}
