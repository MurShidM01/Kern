package com.aliedu.kern.core.tools

import com.aliedu.kern.core.providers.api.ToolSchema
import com.aliedu.kern.core.workspace.WorkspaceAdapter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** The build system a workspace looks like, inferred from the marker files at its root. */
enum class ProjectType(val displayName: String) {
    GRADLE("Gradle"),
    FLUTTER("Flutter"),
    CARGO("Cargo"),
    GO("Go"),
    NPM("Node/npm"),
    PYTHON("Python"),
    MAKE("Make"),
    UNKNOWN("unknown")
}

/**
 * Recognises the project type from well-known root files so the execution tools can pick a command
 * the user would have typed themselves. Detection is deliberately shallow: it reads the workspace
 * root only, and an unrecognised tree yields [ProjectType.UNKNOWN] rather than a guess.
 */
@Singleton
class ProjectDetector @Inject constructor() {

    suspend fun detect(adapter: WorkspaceAdapter): ProjectType {
        return when {
            has(adapter, "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts") ->
                ProjectType.GRADLE

            has(adapter, "pubspec.yaml") -> ProjectType.FLUTTER
            has(adapter, "Cargo.toml") -> ProjectType.CARGO
            has(adapter, "go.mod") -> ProjectType.GO
            has(adapter, "package.json") -> ProjectType.NPM
            has(adapter, "pyproject.toml", "requirements.txt", "setup.py") -> ProjectType.PYTHON
            has(adapter, "Makefile", "makefile") -> ProjectType.MAKE
            else -> ProjectType.UNKNOWN
        }
    }

    /** True when a Gradle wrapper is present, so we invoke `./gradlew` rather than a system Gradle. */
    suspend fun hasGradleWrapper(adapter: WorkspaceAdapter): Boolean = has(adapter, "gradlew")

    private suspend fun has(adapter: WorkspaceAdapter, vararg names: String): Boolean =
        names.any { runCatching { adapter.exists(it) }.getOrDefault(false) }
}

/** The three verbs the execution tools understand. */
enum class TaskKind(val toolName: String) {
    BUILD("run_build"),
    TEST("run_tests"),
    LINT("run_lint")
}

/**
 * Proposes the conventional build/test/lint command for the detected project type (PRD §4.3 bullet
 * 4). Like [RunCommandTool] it never runs anything: it returns a [CommandRequest] the orchestrator
 * gates behind approval. The output of these tools is summarised rather than dumped, so a wall of
 * compiler noise becomes a short, actionable list of failures.
 */
abstract class TaskCommandTool(
    private val projectDetector: ProjectDetector,
    private val kind: TaskKind
) : Tool {

    override val name: String = kind.toolName
    override val isMutating = false

    protected abstract fun commandFor(type: ProjectType, hasWrapper: Boolean): String?

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = """
            {"type":"object","properties":{"reason":{"type":"string","description":"A short explanation shown to the user."},"timeout_seconds":{"type":"integer","description":"Stop after this many seconds (default 120, max 120)."}},"required":[]}
        """.trimIndent()
    )

    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult {
        val type = projectDetector.detect(context.adapter)
        if (type == ProjectType.UNKNOWN) {
            return ToolResult(
                false, "",
                "Could not detect the project type from the workspace root, so there is no obvious " +
                    "${kind.name.lowercase()} command. Use run_command with the exact command instead."
            )
        }
        val hasWrapper = type == ProjectType.GRADLE && projectDetector.hasGradleWrapper(context.adapter)
        val command = commandFor(type, hasWrapper)
            ?: return ToolResult(
                false, "",
                "A ${type.displayName} project has no conventional ${kind.name.lowercase()} command. " +
                    "Use run_command with the exact command instead."
            )

        val reason = (args["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val timeoutMs = ((args["timeout_seconds"] as? JsonPrimitive)?.intOrNull?.toLong()?.times(1000))
            ?.coerceIn(5_000, CommandRequest.MAX_TIMEOUT_MS)
            ?: CommandRequest.MAX_TIMEOUT_MS

        val request = CommandRequest(
            workspaceId = context.workspace.id,
            command = command,
            cwd = context.workspace.localPath.orEmpty(),
            impact = CommandClassifier.classify(command),
            timeoutMs = timeoutMs,
            outputPolicy = OutputPolicy.CAPTURE,
            reason = reason ?: "Run ${kind.name.lowercase()} for this ${type.displayName} project.",
            maxOutputBytes = TASK_MAX_OUTPUT_BYTES,
            summarize = true
        )
        return ToolResult(
            success = true,
            output = "Proposed ${kind.name.lowercase()} command for approval: $command",
            commandRequest = request,
            requiresApproval = true
        )
    }

    private companion object {
        const val TASK_MAX_OUTPUT_BYTES = 256 * 1024
    }
}

@Singleton
class RunBuildTool @Inject constructor(
    projectDetector: ProjectDetector
) : TaskCommandTool(projectDetector, TaskKind.BUILD) {
    override val description =
        "Build the project using its conventional build command (Gradle, Flutter, Cargo, Go, npm, " +
            "Make). Requires approval before it runs."

    override fun commandFor(type: ProjectType, hasWrapper: Boolean): String? = when (type) {
        ProjectType.GRADLE -> if (hasWrapper) "./gradlew assembleDebug" else "gradle assembleDebug"
        ProjectType.FLUTTER -> "flutter build apk --debug"
        ProjectType.CARGO -> "cargo build"
        ProjectType.GO -> "go build ./..."
        ProjectType.NPM -> "npm run build"
        ProjectType.MAKE -> "make"
        ProjectType.PYTHON, ProjectType.UNKNOWN -> null
    }
}

@Singleton
class RunTestsTool @Inject constructor(
    projectDetector: ProjectDetector
) : TaskCommandTool(projectDetector, TaskKind.TEST) {
    override val description =
        "Run the project's test suite using its conventional command (Gradle, Flutter, Cargo, Go, " +
            "npm, pytest). Requires approval before it runs."

    override fun commandFor(type: ProjectType, hasWrapper: Boolean): String? = when (type) {
        ProjectType.GRADLE -> if (hasWrapper) "./gradlew test" else "gradle test"
        ProjectType.FLUTTER -> "flutter test"
        ProjectType.CARGO -> "cargo test"
        ProjectType.GO -> "go test ./..."
        ProjectType.NPM -> "npm test"
        ProjectType.PYTHON -> "python -m pytest"
        ProjectType.MAKE -> "make test"
        ProjectType.UNKNOWN -> null
    }
}

@Singleton
class RunLintTool @Inject constructor(
    projectDetector: ProjectDetector
) : TaskCommandTool(projectDetector, TaskKind.LINT) {
    override val description =
        "Run the project's linter using its conventional command (Gradle lint, Flutter analyze, " +
            "Cargo clippy, go vet, npm lint). Requires approval before it runs."

    override fun commandFor(type: ProjectType, hasWrapper: Boolean): String? = when (type) {
        ProjectType.GRADLE -> if (hasWrapper) "./gradlew lint" else "gradle lint"
        ProjectType.FLUTTER -> "flutter analyze"
        ProjectType.CARGO -> "cargo clippy"
        ProjectType.GO -> "go vet ./..."
        ProjectType.NPM -> "npm run lint"
        ProjectType.PYTHON, ProjectType.MAKE, ProjectType.UNKNOWN -> null
    }
}
