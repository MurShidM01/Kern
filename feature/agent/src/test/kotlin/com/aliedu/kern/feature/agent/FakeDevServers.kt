package com.aliedu.kern.feature.agent

import com.aliedu.kern.core.tools.devserver.DevServer
import com.aliedu.kern.core.tools.devserver.DevServerRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory [DevServerRegistry] so the agent view model can be tested without a real terminal. */
class FakeDevServers : DevServerRegistry {

    private val _servers = MutableStateFlow<List<DevServer>>(emptyList())
    override val servers: StateFlow<List<DevServer>> = _servers

    val stopped = mutableListOf<String>()

    fun setServers(list: List<DevServer>) {
        _servers.value = list
    }

    override suspend fun start(
        command: String,
        port: Int?,
        workspaceId: String,
        cwd: String,
        onOutput: ((String) -> Unit)?
    ): Result<DevServer> = Result.failure(IllegalStateException("not used in this test"))

    override suspend fun stop(id: String): Boolean {
        stopped.add(id)
        return true
    }

    override fun isOwned(sessionId: String): Boolean = false
}
