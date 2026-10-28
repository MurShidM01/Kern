package com.aliedu.kern.core.terminal.implementation.runtime

import com.aliedu.kern.core.terminal.api.ExecutionWorkspace
import com.aliedu.kern.core.terminal.api.SessionKind
import com.aliedu.kern.core.terminal.api.TerminalOutput
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class TerminalSessionManagerTest {

    private fun manager(backend: FakeBackend) = TerminalSessionManager(setOf(backend))

    @Test
    fun `create returns a session and lists it`(): Unit = runBlocking {
        val backend = FakeBackend()
        val m = manager(backend)
        val s = m.create(ExecutionWorkspace("w", "/workspace"), SessionKind.DEVICE_SHELL)

        assertThat(s.kind).isEqualTo(SessionKind.DEVICE_SHELL)
        assertThat(s.cwd).isEqualTo("/workspace")
        assertThat(m.list().map { it.id }).containsExactly(s.id)
    }

    @Test
    fun `multiple sessions are independent`(): Unit = runBlocking {
        val backend = FakeBackend()
        val m = manager(backend)
        val a = m.create(ExecutionWorkspace("a", "/a"), SessionKind.DEVICE_SHELL)
        val b = m.create(ExecutionWorkspace("b", "/b"), SessionKind.DEVICE_SHELL)

        assertThat(a.id).isNotEqualTo(b.id)
        assertThat(m.list()).hasSize(2)
    }

    @Test
    fun `send writes to the process stdin`(): Unit = runBlocking {
        val backend = FakeBackend()
        val m = manager(backend)
        val s = m.create(ExecutionWorkspace("w", "/w"), SessionKind.DEVICE_SHELL)

        m.send(s.id, "ls -la\n".toByteArray())

        assertThat(backend.processes.first().stdin.toString()).isEqualTo("ls -la\n")
    }

    @Test
    fun `replay returns accumulated output`(): Unit = runBlocking {
        val backend = FakeBackend()
        val m = manager(backend)
        val s = m.create(ExecutionWorkspace("w", "/w"), SessionKind.DEVICE_SHELL)

        backend.processes.first().emit("hello world")
        assertThat(waitUntil { m.replay(s.id).contains("hello world") }).isTrue()
        assertThat(m.replay(s.id)).isEqualTo("hello world")
    }

    @Test
    fun `observe emits live output`(): Unit = runBlocking {
        val backend = FakeBackend()
        val m = manager(backend)
        val s = m.create(ExecutionWorkspace("w", "/w"), SessionKind.DEVICE_SHELL)

        val received = CopyOnWriteArrayList<String>()
        // UNDISPATCHED subscribes before returning, so the emit below cannot race the collector.
        val job = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            m.observe(s.id).collect { if (it is TerminalOutput.Stdout) received.add(it.text) }
        }
        backend.processes.first().emit("world")

        assertThat(waitUntil { received.joinToString("").contains("world") }).isTrue()
        job.cancel()
    }

    @Test
    fun `exited output is emitted when the process ends`(): Unit = runBlocking {
        val backend = FakeBackend()
        val m = manager(backend)
        val s = m.create(ExecutionWorkspace("w", "/w"), SessionKind.DEVICE_SHELL)

        val received = CopyOnWriteArrayList<TerminalOutput>()
        val job = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            m.observe(s.id).collect { received.add(it) }
        }
        backend.processes.first().finish(0)

        assertThat(waitUntil { received.any { it is TerminalOutput.Exited } }).isTrue()
        assertThat(received.filterIsInstance<TerminalOutput.Exited>().first().exitCode).isEqualTo(0)
        job.cancel()
    }

    @Test
    fun `close removes the session`(): Unit = runBlocking {
        val backend = FakeBackend()
        val m = manager(backend)
        val s = m.create(ExecutionWorkspace("w", "/w"), SessionKind.DEVICE_SHELL)

        m.close(s.id)

        assertThat(m.list()).isEmpty()
    }

    @Test
    fun `rename updates the display name`(): Unit = runBlocking {
        val backend = FakeBackend()
        val m = manager(backend)
        val s = m.create(ExecutionWorkspace("w", "/w"), SessionKind.DEVICE_SHELL)

        m.rename(s.id, "Build shell")

        assertThat(m.list().first().displayName).isEqualTo("Build shell")
    }

    @Test
    fun `unavailable backend falls back to the device shell`(): Unit = runBlocking {
        val device = FakeBackend(SessionKind.DEVICE_SHELL)
        val alpine = FakeBackend(SessionKind.LINUX_PROOT, available = false)
        val m = TerminalSessionManager(setOf(device, alpine))

        val s = m.create(ExecutionWorkspace("w", "/w"), SessionKind.LINUX_PROOT)

        assertThat(s.kind).isEqualTo(SessionKind.DEVICE_SHELL)
    }

    @Test
    fun `live sessions reflect creation and closure`(): Unit = runBlocking {
        val backend = FakeBackend()
        val m = manager(backend)
        assertThat(m.liveSessions.value).isEmpty()

        val a = m.create(ExecutionWorkspace("a", "/a"), SessionKind.DEVICE_SHELL)
        val b = m.create(ExecutionWorkspace("b", "/b"), SessionKind.DEVICE_SHELL)
        assertThat(m.liveSessions.value.map { it.id }).containsExactly(a.id, b.id).inOrder()

        m.close(a.id)
        assertThat(m.liveSessions.value.map { it.id }).containsExactly(b.id)

        m.shutdownAll()
        assertThat(m.liveSessions.value).isEmpty()
    }

    @Test
    fun `renaming republishes the live session list`(): Unit = runBlocking {
        val backend = FakeBackend()
        val m = manager(backend)
        val s = m.create(ExecutionWorkspace("w", "/w"), SessionKind.DEVICE_SHELL)

        m.rename(s.id, "Build shell")

        assertThat(m.liveSessions.value.single().displayName).isEqualTo("Build shell")
    }
}
