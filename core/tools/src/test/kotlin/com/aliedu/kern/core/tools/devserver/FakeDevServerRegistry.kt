package com.aliedu.kern.core.tools.devserver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory [DevServerRegistry] so tools and the command executor can be tested hermetically. */
class FakeDevServerRegistry : DevServerRegistry {

    private val _servers = MutableStateFlow<List<DevServer>>(emptyList())
    override val servers: StateFlow<List<DevServer>> = _servers

    /** What [start] returns; null means "not configured" and fails the call. */
    var startResult: Result<DevServer>? = null

    val started = mutableListOf<String>()
    val stopped = mutableListOf<String>()
    private val owned = mutableSetOf<String>()

    fun setServers(list: List<DevServer>) {
        _servers.value = list
    }

    /** Mark a session as belonging to a server, so the executor must not reuse it. */
    fun own(sessionId: String) {
        owned.add(sessionId)
    }

    override suspend fun start(
        command: String,
        port: Int?,
        workspaceId: String,
        cwd: String,
        onOutput: ((String) -> Unit)?
    ): Result<DevServer> {
        started.add(command)
        onOutput?.invoke("starting $command")
        return startResult ?: Result.failure(IllegalStateException("start not configured"))
    }

    override suspend fun stop(id: String): Boolean {
        stopped.add(id)
        val exists = _servers.value.any { it.id == id }
        _servers.value = _servers.value.map {
            if (it.id == id) it.copy(state = DevServerState.STOPPED) else it
        }
        return exists
    }

    override fun isOwned(sessionId: String): Boolean = sessionId in owned
}
