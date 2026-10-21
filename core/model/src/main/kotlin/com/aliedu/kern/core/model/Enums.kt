package com.aliedu.kern.core.model

/** PRD 6.2 Theme modes. SYSTEM is the default on first launch. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Named colour schemes (PRD 6.3). The design system maps each entry to an accent family + neutral
 * scale, so the app can offer many professional looks while every scheme stays flat and high-contrast.
 */
enum class ThemePalette {
    INDIGO,
    DESERT,
    OCEAN,
    FOREST,
    SUNSET,
    ROSE,
    SAND,
    EMERALD,
    CORAL,
    GRAPHITE,
    TIDE;

    val displayName: String
        get() = when (this) {
            INDIGO -> "Indigo"
            DESERT -> "Desert"
            OCEAN -> "Ocean"
            FOREST -> "Forest"
            SUNSET -> "Sunset"
            ROSE -> "Rose"
            SAND -> "Sand"
            EMERALD -> "Emerald"
            CORAL -> "Coral"
            GRAPHITE -> "Graphite"
            TIDE -> "Tide"
        }
}

/**
 * Terminal colour scheme (PRD 12.4). [FOLLOW_APP] derives the terminal background, foreground and
 * ANSI colours from the active [ThemePalette] so the shell matches the app by default.
 */
enum class TerminalTheme {
    FOLLOW_APP,
    TANGO_DARK,
    TANGO_LIGHT,
    DRACULA,
    NORD,
    SOLARIZED_DARK,
    GRUVBOX;

    val displayName: String
        get() = when (this) {
            FOLLOW_APP -> "Follow app theme"
            TANGO_DARK -> "Tango Dark"
            TANGO_LIGHT -> "Tango Light"
            DRACULA -> "Dracula"
            NORD -> "Nord"
            SOLARIZED_DARK -> "Solarized Dark"
            GRUVBOX -> "Gruvbox"
        }
}

/** PRD 11.1 Workspace modes. */
enum class WorkspaceSourceType { SELECTED_FOLDER, APP_MANAGED }

/** PRD 10.1 provider protocols. */
enum class ProviderProtocol {
    OPENAI_COMPATIBLE,
    ANTHROPIC_MESSAGES,
    GEMINI_STYLE,
    CUSTOM;

    val displayName: String
        get() = when (this) {
            OPENAI_COMPATIBLE -> "OpenAI-compatible"
            ANTHROPIC_MESSAGES -> "Anthropic Messages"
            GEMINI_STYLE -> "Gemini-style"
            CUSTOM -> "Custom adapter"
        }
}

/** PRD 10.3 authentication capability. Part 1 ships API key only. */
enum class AuthMethod { API_KEY, BROWSER_OAUTH, DEVICE_CODE }

/** Conversation roles persisted with a message. */
enum class MessageRole { SYSTEM, USER, ASSISTANT, TOOL }

/** Streaming lifecycle of a message (PRD 14.1 Message.status). */
enum class MessageStatus { STREAMING, COMPLETE, CANCELLED, FAILED }

/** Session lifecycle (PRD 9.1 terminal states persist a session event). */
enum class SessionStatus { IDLE, ACTIVE, COMPLETED, INTERRUPTED, FAILED }

/** Tool/approval lifecycle for a single tool invocation (PRD 9.1, 9.2). */
enum class ToolEventStatus {
    PENDING,
    RUNNING,
    WAITING_APPROVAL,
    APPROVED,
    REJECTED,
    COMPLETED,
    FAILED
}

/** Patch lifecycle (PRD 14.1 PendingPatch.status). */
enum class PatchStatus { PROPOSED, APPROVED, APPLIED, REJECTED, STALE }

/** Lifecycle of one agent task-list item (FR-018 structured multi-step plan). */
enum class TodoStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED;

    val displayName: String
        get() = when (this) {
            PENDING -> "Pending"
            IN_PROGRESS -> "In progress"
            COMPLETED -> "Completed"
        }
}

/** Classification of a workspace entry for read/edit affordances (PRD 7.5). */
enum class FileKind { DIRECTORY, TEXT, BINARY, UNKNOWN }

/** PRD 9.4 command safety classification (used from Part 2, modelled now for stability). */
enum class CommandSafetyLevel { READ_ONLY, WORKSPACE_MUTATION, HIGH_IMPACT }

/**
 * How much the agent may do without asking. Selected in the composer, persisted, and enforced by the
 * agent approval gate.
 */
enum class PermissionMode {
    /** Every file change waits for an explicit approve/reject. */
    ASK_CHANGES,

    /** File edits are applied automatically; other risky actions still ask. */
    AUTO_EDIT,

    /** Everything the agent proposes is applied without asking. */
    FULL_ACCESS;

    val displayName: String
        get() = when (this) {
            ASK_CHANGES -> "Ask changes"
            AUTO_EDIT -> "Auto edit"
            FULL_ACCESS -> "Full access"
        }

    val description: String
        get() = when (this) {
            ASK_CHANGES -> "Review every file change before it is written."
            AUTO_EDIT -> "Apply file edits automatically; ask for anything else."
            FULL_ACCESS -> "Apply everything the agent proposes without asking."
        }
}
