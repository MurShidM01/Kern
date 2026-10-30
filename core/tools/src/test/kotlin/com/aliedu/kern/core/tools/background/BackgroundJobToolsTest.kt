package com.aliedu.kern.core.tools.background

import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.model.WorkspaceSourceType
import com.aliedu.kern.core.tools.ToolArgs
import com.aliedu.kern.core.tools.ToolContext
import com.aliedu.kern.core.workspace.AppManagedWorkspaceAdapter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The background tools only *describe* and *steer* jobs: starting one still goes through the same
 * approval gate as run_command (PRD FR-017), and the other four read the registry the executor writes.
 */
class BackgroundJobToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: ToolContext

    @Before
    fun setUp() {
        val root = temp.newFolder("ws")
        val adapter = AppManagedWorkspaceAdapter(root, "ws")
        val workspace = Workspace(
            id = "ws",
            displayName = "Fixture",
            sourceType = WorkspaceSourceType.APP_MANAGED,
            uriString = null,
            localPath = root.absolutePath,
            grantState = GrantState.NOT_APPLICABLE,
            lastOpenedAt = 0,
            createdAt = 0
        )
        context = ToolContext(sessionId = "s1", workspace = workspace, adapter = adapter)
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    private class FakeJobs(vararg initial: BackgroundJob) : BackgroundJobRegistry {
        private val _jobs = MutableStateFlow(initial.toList())
        override val jobs: StateFlow<List<BackgroundJob>> = _jobs
        val stopped = mutableListOf<String>()

        override suspend fun start(
            command: String,
            workspaceId: String,
            cwd: String,
            maxOutputChars: Int
        ): Result<BackgroundJob> = Result.failure(IllegalStateException("not used here"))

        override fun status(id: String): BackgroundJob? = _jobs.value.firstOrNull { it.id == id }

        override fun output(id: String, cursor: Int): BackgroundOutput? {
            val job = status(id) ?: return null
            val from = cursor.coerceIn(0, job.output.length)
            return BackgroundOutput(
                jobId = job.id,
                state = job.state,
                text = job.output.substring(from),
                nextCursor = job.output.length,
                truncated = job.truncated,
                totalChars = job.output.length
            )
        }

        override suspend fun await(id: String, timeoutMs: Long): BackgroundJob? = status(id)

        override suspend fun stop(id: String): Boolean {
            if (status(id) == null) return false
            stopped.add(id)
            return true
        }

        override fun isOwned(sessionId: String): Boolean = false
    }

    private fun job(
        id: String = "job-1",
        state: BackgroundJobState = BackgroundJobState.RUNNING,
        exitCode: Int? = null,
        output: String = ""
    ) = BackgroundJob(
        id = id,
        command = "apt-get install -y openjdk-17-jdk",
        workspaceId = "ws",
        cwd = "/exec/ws",
        state = state,
        startedAt = 0,
        finishedAt = if (state.isTerminal) 1 else null,
        exitCode = exitCode,
        sessionId = "sess",
        output = output,
        outputChars = output.length
    )

    @Test
    fun `starting a job proposes a command and never runs it`(): Unit = runBlocking {
        val result = BackgroundStartTool().execute(args("command" to "npm install"), context)

        assertThat(result.success).isTrue()
        assertThat(result.requiresApproval).isTrue()
        assertThat(result.commandRequest?.background).isTrue()
        assertThat(result.commandRequest?.command).isEqualTo("npm install")
    }

    @Test
    fun `a missing command fails cleanly`(): Unit = runBlocking {
        val result = BackgroundStartTool().execute(JsonObject(emptyMap()), context)
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("command")
    }

    @Test
    fun `status lists every job and describes one`(): Unit = runBlocking {
        val registry = FakeJobs(job(id = "job-1"), job(id = "job-2", state = BackgroundJobState.SUCCEEDED, exitCode = 0))
        val tool = BackgroundStatusTool(registry)

        val all = tool.execute(JsonObject(emptyMap()), context)
        assertThat(all.output).contains("job-1")
        assertThat(all.output).contains("job-2")

        val one = tool.execute(args("id" to "job-2"), context)
        assertThat(one.output).contains("succeeded")
        assertThat(one.output).contains("Exit code: 0")
    }

    @Test
    fun `status of an unknown job is an error`(): Unit = runBlocking {
        val result = BackgroundStatusTool(FakeJobs()).execute(args("id" to "nope"), context)
        assertThat(result.success).isFalse()
    }

    @Test
    fun `output returns only what arrived after the cursor`(): Unit = runBlocking {
        val registry = FakeJobs(job(output = "0123456789"))
        val tool = BackgroundOutputTool(registry)

        val all = tool.execute(args("id" to "job-1"), context)
        assertThat(all.output).contains("0123456789")

        val rest = tool.execute(args("id" to "job-1", "cursor" to "5"), context)
        assertThat(rest.output).startsWith("56789")
    }

    @Test
    fun `output with nothing new says so instead of returning an empty body`(): Unit = runBlocking {
        val registry = FakeJobs(job(output = "abc"))
        val result = BackgroundOutputTool(registry).execute(args("id" to "job-1", "cursor" to "3"), context)

        assertThat(result.success).isTrue()
        assertThat(result.output).contains("No new output")
    }

    @Test
    fun `waiting on a finished job reports its exit code and the end of its output`(): Unit = runBlocking {
        val registry = FakeJobs(
            job(state = BackgroundJobState.SUCCEEDED, exitCode = 0, output = "Building…\nBUILD SUCCESSFUL")
        )
        val result = BackgroundWaitTool(registry).execute(args("id" to "job-1"), context)

        assertThat(result.success).isTrue()
        assertThat(result.output).contains("exit code 0")
        assertThat(result.output).contains("BUILD SUCCESSFUL")
    }

    @Test
    fun `waiting on a running job returns instead of blocking the turn forever`(): Unit = runBlocking {
        val registry = FakeJobs(job())
        val result = BackgroundWaitTool(registry).execute(args("id" to "job-1"), context)

        assertThat(result.output).contains("still running")
        assertThat(result.output).contains("background_wait again")
    }

    @Test
    fun `a failed job is reported as a failure`(): Unit = runBlocking {
        val registry = FakeJobs(job(state = BackgroundJobState.FAILED, exitCode = 100, output = "E: broken"))
        val result = BackgroundWaitTool(registry).execute(args("id" to "job-1"), context)

        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("100")
        assertThat(result.output).contains("E: broken")
    }

    @Test
    fun `stopping a job delegates to the registry`(): Unit = runBlocking {
        val registry = FakeJobs(job())
        val result = BackgroundStopTool(registry).execute(args("id" to "job-1"), context)

        assertThat(result.success).isTrue()
        assertThat(registry.stopped).containsExactly("job-1")
    }

    @Test
    fun `a millisecond wait is converted to seconds, not read as seconds`(): Unit = runBlocking {
        // `timeout_ms` is aliased onto `timeout_seconds` with no unit change unless we convert:
        // 60 000 ms became 60 000 s and 500 ms became 500 s. This bounds the *wait*, not the job.
        val schema = BackgroundWaitTool(FakeJobs()).schema().parametersJsonSchema

        val sixtySeconds = ToolArgs.normalize(
            Json.parseToJsonElement("{\"id\":\"job-1\",\"timeout_ms\":60000}").jsonObject,
            schema
        )
        assertThat(sixtySeconds["timeout_seconds"]!!.jsonPrimitive.content).isEqualTo("60")

        val halfSecond = ToolArgs.normalize(
            Json.parseToJsonElement("{\"id\":\"job-1\",\"timeout_ms\":500}").jsonObject,
            schema
        )
        assertThat(halfSecond["timeout_seconds"]!!.jsonPrimitive.content).isEqualTo("1")
    }
}
