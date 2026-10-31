package com.aliedu.kern.feature.agent

import com.aliedu.kern.core.tools.background.BackgroundJob
import com.aliedu.kern.core.tools.background.BackgroundJobRegistry
import com.aliedu.kern.core.tools.background.BackgroundOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A job registry for ViewModel tests: it holds whatever the test publishes and starts nothing. */
class FakeBackgroundJobs : BackgroundJobRegistry {

    private val _jobs = MutableStateFlow<List<BackgroundJob>>(emptyList())
    override val jobs: StateFlow<List<BackgroundJob>> = _jobs

    fun setJobs(list: List<BackgroundJob>) {
        _jobs.value = list
    }

    override suspend fun start(
        command: String,
        workspaceId: String,
        cwd: String,
        maxOutputChars: Int
    ): Result<BackgroundJob> = Result.failure(IllegalStateException("not used in this test"))

    override fun status(id: String): BackgroundJob? = _jobs.value.firstOrNull { it.id == id }

    override fun output(id: String, cursor: Int): BackgroundOutput? = null

    override suspend fun await(id: String, timeoutMs: Long): BackgroundJob? = status(id)

    override suspend fun stop(id: String): Boolean = false

    override fun isOwned(sessionId: String): Boolean = false
}
