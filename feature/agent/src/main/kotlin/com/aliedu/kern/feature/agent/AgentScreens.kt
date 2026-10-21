package com.aliedu.kern.feature.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.aliedu.kern.core.agent.AgentState
import com.aliedu.kern.core.designsystem.components.KernButton
import com.aliedu.kern.core.designsystem.components.KernDivider
import com.aliedu.kern.core.designsystem.components.KernDropdownItem
import com.aliedu.kern.core.designsystem.components.KernDropdownMenu
import com.aliedu.kern.core.designsystem.components.KernIconBadge
import com.aliedu.kern.core.designsystem.components.KernIconButton
import com.aliedu.kern.core.designsystem.components.KernInfoNote
import com.aliedu.kern.core.designsystem.components.KernMarkdown
import com.aliedu.kern.core.designsystem.components.KernOptionCard
import com.aliedu.kern.core.designsystem.components.KernOutlinedButton
import com.aliedu.kern.core.designsystem.components.KernPullToRefresh
import com.aliedu.kern.core.designsystem.components.KernScaffold
import com.aliedu.kern.core.designsystem.components.KernStatusCard
import com.aliedu.kern.core.designsystem.components.KernTextButton
import com.aliedu.kern.core.designsystem.components.KernTextField
import com.aliedu.kern.core.designsystem.components.KernTone
import com.aliedu.kern.core.designsystem.components.KernTopBar
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.core.designsystem.theme.KernElevation
import com.aliedu.kern.core.designsystem.theme.KernRadius
import com.aliedu.kern.core.designsystem.theme.KernSizes
import com.aliedu.kern.core.designsystem.theme.KernSpacing
import com.aliedu.kern.core.designsystem.theme.LocalKernSemanticColors
import com.aliedu.kern.core.model.Message
import com.aliedu.kern.core.model.MessageRole
import com.aliedu.kern.core.model.MessageStatus
import com.aliedu.kern.core.model.PermissionMode
import com.aliedu.kern.core.model.ProviderProfile
import com.aliedu.kern.core.model.Todo
import com.aliedu.kern.core.model.TodoStatus
import com.aliedu.kern.core.model.ToolEvent
import com.aliedu.kern.core.model.ToolEventStatus
import com.aliedu.kern.core.tools.PatchOperation
import com.aliedu.kern.core.tools.PatchProposal
import com.aliedu.kern.core.tools.PlanProposal
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    state: AgentUiState,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onApprove: () -> Unit,
    onApproveSelection: (Set<String>) -> Unit,
    onReject: () -> Unit,
    onApprovePlan: () -> Unit,
    onRequestPlanChanges: (String) -> Unit,
    onRejectPlan: () -> Unit,
    onRetry: () -> Unit,
    onNewSession: () -> Unit,
    onConfigureProvider: () -> Unit,
    onSelectModel: (String, String) -> Unit,
    onLoadModels: () -> Unit,
    onPermissionModeChange: (PermissionMode) -> Unit,
    onToggleThinking: (Boolean) -> Unit,
    onClearModelTestError: () -> Unit,
    onRefresh: () -> Unit,
    onOpenPreview: () -> Unit
) {
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var showModelPicker by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()

    val composerReserve = KernSizes.composerMaxHeight + KernSizes.composerScrim
    // Interleave messages and tool activity so each tool appears inline where the agent used it.
    val timeline = remember(state.messages, state.toolEvents) {
        buildTimeline(state.messages, state.toolEvents)
    }
    // The last assistant message owns the single copy affordance for the reply.
    val lastReplyIndex = remember(timeline) {
        timeline.indexOfLast { it is TimelineItem.Msg && it.message.role == MessageRole.ASSISTANT }
    }
    // A command runs inside its run_command row. Only if that row is missing (a resumed session, or
    // an event that arrived before the tool row was persisted) does it get a standalone row.
    val hasLiveToolRow = timeline.any {
        it is TimelineItem.Tool &&
            it.event.toolName == "run_command" &&
            it.event.status == ToolEventStatus.RUNNING
    }
    val showOrphanLiveCommand = state.liveCommand != null && !hasLiveToolRow
    val showLiveReasoning = state.showReasoning && state.reasoningText.isNotEmpty()
    val showStreaming = state.streamingText.isNotEmpty()
    val showThinking = state.isBusy && !showStreaming && !showLiveReasoning
    val isEmpty = timeline.isEmpty() && !showStreaming && !showLiveReasoning && !showThinking

    // Elapsed counter for the live phase line so a slow provider still shows visible progress.
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.isBusy) {
        if (!state.isBusy) {
            elapsedSeconds = 0
        } else {
            val start = System.currentTimeMillis()
            while (true) {
                elapsedSeconds = ((System.currentTimeMillis() - start) / 1000).toInt()
                delay(1000)
            }
        }
    }

    KernScaffold(
        topBar = {
            KernTopBar(
                title = state.workspace?.displayName ?: "Agent",
                subtitle = state.providerLabel,
                actions = {
                    // Only while something is actually serving: an icon that opens an empty preview
                    // would be noise. This is the entry point the user asked for — a server is
                    // running, so the app bar offers to show it.
                    if (state.runningServers.isNotEmpty()) {
                        KernIconButton(
                            icon = KernIcons.Globe,
                            contentDescription = "Preview ${state.runningServers.first().url}",
                            onClick = onOpenPreview,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    KernIconButton(
                        icon = Icons.Outlined.Add,
                        contentDescription = "New task",
                        onClick = onNewSession
                    )
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            KernPullToRefresh(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                if (isEmpty) {
                    AgentWelcome(
                        bottomReserve = composerReserve,
                        onPick = { prompt -> field = TextFieldValue(prompt) }
                    )
                } else {
                    val liveItems = (if (showOrphanLiveCommand) 1 else 0) +
                        (if (showLiveReasoning) 1 else 0) +
                        (if (showStreaming) 1 else 0) +
                        (if (showThinking) 1 else 0)
                    val totalItems = timeline.size + liveItems
                    LaunchedEffect(totalItems) {
                        if (state.autoScroll && totalItems > 0) listState.animateScrollToItem(totalItems - 1)
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = KernSpacing.screenH,
                            end = KernSpacing.screenH,
                            top = KernSpacing.sm,
                            bottom = composerReserve
                        ),
                        verticalArrangement = Arrangement.spacedBy(KernSpacing.md)
                    ) {
                        itemsIndexed(timeline, key = { _, item -> item.key }) { index, item ->
                            when (item) {
                                is TimelineItem.Msg -> MessageBubble(
                                    message = item.message,
                                    fontSizeSp = state.chatFontSizeSp,
                                    // One copy affordance per reply, at the very end — a reply is
                                    // often split into several step messages around tool calls.
                                    showCopy = item.message.role == MessageRole.USER ||
                                        index == lastReplyIndex
                                )
                                is TimelineItem.Tool -> ToolRow(
                                    event = item.event,
                                    // A running shell command shows its live output inside its own
                                    // row, so the transcript never grows a floating panel.
                                    liveOutput = if (
                                        state.liveCommand != null &&
                                        item.event.toolName == "run_command" &&
                                        item.event.status == ToolEventStatus.RUNNING
                                    ) state.liveCommandOutput else null
                                )
                            }
                        }
                        if (showOrphanLiveCommand) {
                            item {
                                LiveCommandRow(
                                    command = state.liveCommand.orEmpty(),
                                    output = state.liveCommandOutput
                                )
                            }
                        }
                        if (showLiveReasoning) {
                            item { ReasoningRow(state.reasoningText, streaming = true) }
                        }
                        if (showStreaming) {
                            item { StreamingText(state.streamingText, state.chatFontSizeSp) }
                        }
                        if (showThinking) {
                            item { ThinkingRow(label = agentPhaseLabel(state), elapsedSeconds = elapsedSeconds) }
                        }
                    }
                }
            }

            AgentComposer(
                value = field,
                onValueChange = { field = it },
                mentionFiles = state.mentionFiles,
                isBusy = state.isBusy,
                noProvider = state.noProvider,
                error = state.error,
                canRetry = state.canRetry,
                activeProfile = state.profiles.firstOrNull { it.id == state.activeProfileId },
                fallbackModelLabel = state.providerLabel,
                permissionMode = state.permissionMode,
                thinkingEnabled = state.showReasoning,
                todos = state.todos,
                onConfigureProvider = onConfigureProvider,
                onOpenModelPicker = { showModelPicker = true },
                onPermissionModeChange = onPermissionModeChange,
                onToggleThinking = onToggleThinking,
                onSend = {
                    val text = field.text
                    field = TextFieldValue("")
                    onSend(text)
                },
                onCancel = onCancel,
                onRetry = onRetry,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            profiles = state.profiles,
            models = state.models,
            modelsLoading = state.modelsLoading,
            modelsError = state.modelsError,
            activeProfileId = state.activeProfileId,
            activeModelId = state.profiles.firstOrNull { it.id == state.activeProfileId }?.modelId,
            testingKey = state.modelTestingId,
            error = state.modelTestError,
            onLoadModels = onLoadModels,
            onSelectModel = onSelectModel,
            onClearError = onClearModelTestError,
            onDismiss = { showModelPicker = false },
            sheetState = sheetState
        )
    }

    val proposal = state.pendingApproval
    if (proposal != null) {
        ApprovalDialog {
            ApprovalSheetContent(
                proposal = proposal,
                reason = state.pendingApprovalReason,
                onApprove = onApproveSelection,
                onReject = onReject
            )
        }
    }

    val command = state.pendingCommand
    if (command != null) {
        ApprovalDialog {
            CommandApprovalSheetContent(
                request = command,
                reason = state.pendingCommandReason,
                onApprove = onApprove,
                onReject = onReject
            )
        }
    }

    // Plan mode: the agent stops before doing any work and waits for the plan to be reviewed.
    val plan = state.pendingPlan
    if (plan != null) {
        PlanApprovalDialog(
            plan = plan,
            onApprove = onApprovePlan,
            onRequestChanges = onRequestPlanChanges,
            onReject = onRejectPlan
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Welcome / empty state
// ---------------------------------------------------------------------------------------------

private data class Suggestion(val icon: ImageVector, val title: String, val prompt: String)

private val agentSuggestions = listOf(
    Suggestion(
        icon = KernIcons.Files,
        title = "Explain this project",
        prompt = "Look at the workspace and explain what this project does and how it is organised."
    ),
    Suggestion(
        icon = KernIcons.Search,
        title = "Find and fix a bug",
        prompt = "Search the workspace for TODOs and likely bugs, then propose a fix for the most important one."
    ),
    Suggestion(
        icon = KernIcons.Terminal,
        title = "Explore with the shell",
        prompt = "List the files in the workspace and summarise what you find."
    ),
    Suggestion(
        icon = KernIcons.Editor,
        title = "Write a new file",
        prompt = "Create a README.md that describes this project."
    )
)

@Composable
private fun AgentWelcome(bottomReserve: androidx.compose.ui.unit.Dp, onPick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = KernSpacing.screenH,
                end = KernSpacing.screenH,
                top = KernSpacing.lg,
                bottom = bottomReserve + KernSpacing.lg
            ),
        verticalArrangement = Arrangement.spacedBy(KernSpacing.lg)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KernSpacing.sm)
        ) {
            KernIconBadge(
                icon = KernIcons.Sparkle,
                container = MaterialTheme.colorScheme.primaryContainer,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                size = 56.dp,
                iconSize = KernSizes.iconLg,
                shape = KernRadius.lgShape
            )
            Text(
                text = "Start a task",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Describe what you want Kern to do. It reads files with tools and proposes " +
                    "edits you review before anything is written.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Text(
            text = "Try one of these",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(verticalArrangement = Arrangement.spacedBy(KernSpacing.sm)) {
            agentSuggestions.forEach { suggestion ->
                KernOptionCard(
                    icon = suggestion.icon,
                    title = suggestion.title,
                    detail = suggestion.prompt,
                    onClick = { onPick(suggestion.prompt) }
                )
            }
        }

        KernInfoNote("Type @ to reference a file from your workspace.")
    }
}

// ---------------------------------------------------------------------------------------------
// Live command output — shown while an approved command runs (PRD §4.3 bullet 8)
// ---------------------------------------------------------------------------------------------

@Composable
private fun LiveCommandRow(
    command: String,
    output: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(true) }
    val semantic = LocalKernSemanticColors.current
    val scroll = rememberScrollState()

    // Follow the tail as new output arrives, the way a terminal does.
    LaunchedEffect(output) {
        if (expanded) scroll.scrollTo(scroll.maxValue)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = KernSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
        ) {
            Icon(
                imageVector = KernIcons.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(KernSizes.iconSm)
            )
            Text(
                text = "$ $command",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            CircularProgressIndicator(
                modifier = Modifier.size(KernSizes.iconXs),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Hide output" else "Show output",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(KernSizes.iconSm)
            )
        }
        if (expanded && output.isNotBlank()) {
            Text(
                text = output.trimEnd(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp
                ),
                color = semantic.codeText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = KernSizes.iconSm + KernSpacing.sm)
                    .heightIn(max = 220.dp)
                    .verticalScroll(scroll)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Composer (floating over the transcript) — model picker, permissions, thinking
// ---------------------------------------------------------------------------------------------

@Composable
private fun AgentComposer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    mentionFiles: List<String>,
    isBusy: Boolean,
    noProvider: Boolean,
    error: String?,
    canRetry: Boolean,
    activeProfile: ProviderProfile?,
    fallbackModelLabel: String?,
    permissionMode: PermissionMode,
    thinkingEnabled: Boolean,
    todos: List<Todo>,
    onConfigureProvider: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onPermissionModeChange: (PermissionMode) -> Unit,
    onToggleThinking: (Boolean) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cursor = value.selection.end.coerceIn(0, value.text.length)

    val mentionToken: String? = remember(value.text, cursor) {
        if (cursor == 0) return@remember null
        val at = value.text.lastIndexOf('@', cursor - 1)
        if (at < 0) return@remember null
        val between = value.text.substring(at + 1, cursor)
        if (between.any { it.isWhitespace() }) null else between
    }

    val suggestions: List<String> = if (mentionToken != null && mentionFiles.isNotEmpty()) {
        mentionFiles.filter { it.contains(mentionToken, ignoreCase = true) }.take(6)
    } else {
        emptyList()
    }

    fun applyMention(path: String) {
        val at = if (cursor == 0) -1 else value.text.lastIndexOf('@', cursor - 1)
        if (at < 0) return
        val before = value.text.substring(0, at)
        val after = value.text.substring(cursor)
        val inserted = "@$path "
        onValueChange(TextFieldValue(before + inserted + after, TextRange((before + inserted).length)))
    }

    fun insertMentionTrigger() {
        val text = value.text
        val before = text.substring(0, cursor)
        val after = text.substring(cursor)
        val inserted = if (before.isEmpty() || before.last().isWhitespace()) "@" else " @"
        onValueChange(TextFieldValue(before + inserted + after, TextRange(cursor + inserted.length)))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = KernSpacing.sm, vertical = KernSpacing.sm)) {
            error?.let {
                KernInfoNote(
                    text = it,
                    tone = KernTone.ERROR,
                    modifier = Modifier.padding(horizontal = KernSpacing.xs, vertical = KernSpacing.xxs)
                )
            }
            if (canRetry) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = KernSpacing.xs, vertical = KernSpacing.xxs),
                    horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "The last turn stopped early. Your task list is kept.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    KernOutlinedButton(onClick = onRetry) { Text("Retry") }
                }
            }

            if (suggestions.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = KernSpacing.xs),
                    shape = KernRadius.mdShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        KernElevation.hairline,
                        MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Column {
                        Text(
                            text = "Files",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = KernSpacing.md,
                                top = KernSpacing.sm,
                                bottom = KernSpacing.xxs
                            )
                        )
                        suggestions.forEachIndexed { index, path ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { applyMention(path) }
                                    .padding(horizontal = KernSpacing.md, vertical = KernSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
                            ) {
                                Icon(
                                    imageVector = KernIcons.File,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(KernSizes.iconSm)
                                )
                                Text(
                                    text = path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (index < suggestions.lastIndex) KernDivider()
                        }
                    }
                }
            }

            if (noProvider) {
                KernStatusCard(
                    title = "No provider configured",
                    detail = "Connect an OpenAI-compatible endpoint to use the agent.",
                    tone = KernTone.WARNING,
                    icon = KernIcons.Credential,
                    trailing = {
                        KernTextButton(onClick = onConfigureProvider) { Text("Configure") }
                    },
                    modifier = Modifier.padding(bottom = KernSpacing.xs)
                )
                return@Column
            }

            val modelLabel = activeProfile?.modelId ?: fallbackModelLabel
            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = KernSizes.composerChipHeight),
                    shape = KernRadius.xlShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        KernElevation.hairline,
                        MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = KernSpacing.md,
                            vertical = KernSpacing.md
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = KernSpacing.xs)
                        ) {
                            if (value.text.isEmpty()) {
                                Text(
                                    text = "Message Kern",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = value,
                                onValueChange = onValueChange,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                maxLines = 6,
                                // Enter inserts a newline. The field is multi-line, so the IME's
                                // action key is Return rather than "send"; sending is the send
                                // button's job, and prompts are usually more than one line.
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = KernSizes.composerMaxHeight)
                            )
                        }

                        Spacer(Modifier.height(KernSpacing.md))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(KernSpacing.xs)
                            ) {
                                ThinkingControl(enabled = thinkingEnabled, onToggle = onToggleThinking)
                                ComposerDivider()
                                ToolsControl()
                                KernIconButton(
                                    icon = KernIcons.Attach,
                                    contentDescription = "Mention a file",
                                    onClick = { insertMentionTrigger() },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
                            ) {
                                ModelSelectorChip(
                                    label = modelLabel ?: "Select model",
                                    onClick = onOpenModelPicker
                                )
                                SendButton(
                                    isBusy = isBusy,
                                    enabled = isBusy || value.text.isNotBlank(),
                                    onClick = if (isBusy) onCancel else onSend
                                )
                            }
                        }
                    }
                }

                // Permission + Tasks tabs are notched into the composer's top border. The horizontal
                // inset must equal the composer's corner radius: that is exactly where its rounded
                // corner ends and the top border becomes straight, so each tab meets the border
                // head-on instead of cutting through the curve (which left the left tab looking
                // detached from the composer).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(KernSizes.composerChipHeight + KernElevation.hairline)
                        .padding(horizontal = KernRadius.xl),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    PermissionControl(mode = permissionMode, onSelect = onPermissionModeChange)
                    if (todos.isNotEmpty()) {
                        TasksControl(todos = todos)
                    }
                }
            }
        }
    }
}

/**
 * A tab that rises out of a surface's top border: rounded top corners, an open (unstroked) bottom,
 * and a fill that covers the border beneath it — so the outline reads as one continuous shape with a
 * notch cut out of it, exactly like the reference design.
 */
@Composable
private fun NotchTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val fill = MaterialTheme.colorScheme.surface
    val stroke = MaterialTheme.colorScheme.outlineVariant
    val corner = KernRadius.chip
    val strokePx = KernElevation.hairline
    Row(
        modifier = modifier
            .height(KernSizes.composerChipHeight + KernElevation.hairline)
            .drawBehind {
                val r = corner.toPx().coerceAtMost(size.height / 2f)
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(0f, h)
                    lineTo(0f, r)
                    arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
                    lineTo(w - r, 0f)
                    arcTo(Rect(w - 2 * r, 0f, w, 2 * r), 270f, 90f, false)
                    lineTo(w, h)
                }
                drawPath(path, fill)
                // Unclosed path: the bottom edge stays open so it merges with the composer border.
                drawPath(path, stroke, style = Stroke(width = strokePx.toPx()))
            }
            .clickable(onClick = onClick)
            .padding(horizontal = KernSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KernSpacing.xs),
        content = content
    )
}

/** Tinted rounded-square icon badge used inside the composer's notch tabs. */
@Composable
private fun ComposerNotchIcon(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(KernRadius.xsShape)
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(KernSizes.iconSm)
        )
    }
}

/** Thin vertical rule separating composer controls. */
@Composable
private fun ComposerDivider() {
    Box(
        modifier = Modifier
            .height(18.dp)
            .width(KernElevation.hairline)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun SendButton(isBusy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        modifier = Modifier.size(KernSizes.composerSendButton)
    ) {
        Icon(
            imageVector = if (isBusy) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,
            contentDescription = if (isBusy) "Stop" else "Send",
            modifier = Modifier.size(KernSizes.iconMd)
        )
    }
}

/**
 * Hosts an approval prompt in a dialog that cannot be dismissed by tapping outside or pressing back.
 *
 * An approval blocks the agent turn, so a stray tap used to strand the run: the sheet animated away
 * while the orchestrator stayed parked on the pending approval, with no way to answer it. The only
 * exits here are the Approve and Reject buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalDialog(content: @Composable () -> Unit) {
    BasicAlertDialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Surface(
            shape = KernRadius.lgShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(KernElevation.hairline, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp
        ) {
            content()
        }
    }
}

/**
 * Plan review. The agent writes a Markdown plan and stops; the user approves it (the agent then
 * executes it and the Markdown is saved under ~/.kern/PLANS/<workspace>/) or describes what should
 * change, which goes back to the model so it revises the plan instead of guessing.
 */
@Composable
private fun PlanApprovalDialog(
    plan: PlanProposal,
    onApprove: () -> Unit,
    onRequestChanges: (String) -> Unit,
    onReject: () -> Unit
) {
    var feedback by remember(plan.id) { mutableStateOf("") }
    var revising by remember(plan.id) { mutableStateOf(false) }
    ApprovalDialog {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KernSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(KernSpacing.md)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
            ) {
                KernIconBadge(
                    icon = KernIcons.Tasks,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    size = 32.dp,
                    iconSize = KernSizes.iconMd,
                    shape = KernRadius.smShape
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Review the plan",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = plan.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                KernMarkdown(plan.markdown)
            }
            if (revising) {
                KernTextField(
                    value = feedback,
                    onValueChange = { feedback = it },
                    label = "What should change?",
                    placeholder = "e.g. reuse the existing Settings screen instead of adding a new one",
                    singleLine = false,
                    minHeight = 72.dp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    KernTextButton(onClick = { revising = false }) { Text("Back") }
                    KernButton(
                        onClick = { onRequestChanges(feedback) },
                        enabled = feedback.isNotBlank()
                    ) { Text("Send changes") }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    KernTextButton(onClick = onReject) { Text("Reject") }
                    KernOutlinedButton(onClick = { revising = true }) { Text("Change") }
                    KernButton(onClick = onApprove) { Text("Approve") }
                }
            }
        }
    }
}

/** Model selector: brand mark + model id + chevron, sitting flush on the composer surface. */
@Composable
private fun ModelSelectorChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = KernRadius.chipShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(KernElevation.hairline, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = KernSpacing.sm, vertical = KernSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.xs)
        ) {
            KernIconBadge(
                icon = KernIcons.Sparkle,
                container = MaterialTheme.colorScheme.primary,
                tint = MaterialTheme.colorScheme.onPrimary,
                size = 20.dp,
                iconSize = KernSizes.iconXs,
                shape = CircleShape
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 130.dp)
            )
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(KernSizes.iconSm)
            )
        }
    }
}

/**
 * Reasoning toggle: one bare icon, no chip and no label. Colour alone carries the state — accent when
 * thinking is on, muted when it is off — which is the quietest way to expose it next to the tools.
 */
@Composable
private fun ThinkingControl(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    // A palette like Graphite has a near-neutral primary, so a tint change alone is almost
    // invisible. The disc behind the icon carries the on/off state in every scheme without
    // reintroducing a border or a label.
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(KernSizes.iconButton)
            .clip(CircleShape)
            .background(if (enabled) accent.copy(alpha = 0.18f) else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        KernIconButton(
            icon = KernIcons.Reasoning,
            contentDescription = if (enabled) "Disable thinking" else "Enable thinking",
            onClick = { onToggle(!enabled) },
            tint = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Informational menu of the tools the agent can call this turn. */
@Composable
private fun ToolsControl() {
    var expanded by remember { mutableStateOf(false) }
    Box {
        KernIconButton(
            icon = KernIcons.Globe,
            contentDescription = "Agent tools",
            onClick = { expanded = true },
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        KernDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, KernSizes.composerChipHeight)
        ) {
            Text(
                text = "Tools the agent can use",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = KernSpacing.md, vertical = KernSpacing.xxs)
            )
            composerTools.forEach { tool ->
                KernDropdownItem(
                    title = tool.label,
                    subtitle = tool.name,
                    leading = KernIcons.Tools,
                    leadingTint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PermissionControl(mode: PermissionMode, onSelect: (PermissionMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val accent = when (mode) {
        PermissionMode.ASK_CHANGES -> MaterialTheme.colorScheme.onSurfaceVariant
        PermissionMode.AUTO_EDIT -> MaterialTheme.colorScheme.primary
        PermissionMode.FULL_ACCESS -> LocalKernSemanticColors.current.warning
    }
    Box {
        NotchTab(onClick = { expanded = true }) {
            ComposerNotchIcon(icon = KernIcons.Privacy, tint = accent)
            Text(
                text = mode.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                maxLines = 1
            )
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(KernSizes.iconSm)
            )
        }
        KernDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, KernSizes.composerChipHeight)
        ) {
            PermissionMode.entries.forEach { option ->
                KernDropdownItem(
                    title = option.displayName,
                    subtitle = option.description,
                    selected = option == mode,
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** Composer chip that tracks the agent's task list (FR-018) with a live progress badge. */
@Composable
private fun TasksControl(todos: List<Todo>) {
    var expanded by remember { mutableStateOf(false) }
    val done = todos.count { it.status == TodoStatus.COMPLETED }
    val total = todos.size
    Box {
        NotchTab(onClick = { expanded = true }) {
            ComposerNotchIcon(icon = KernIcons.Tasks, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = "Tasks",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Surface(
                shape = KernRadius.xsShape,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = "$done/$total",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
        KernDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, KernSizes.composerChipHeight),
            alignment = Alignment.TopEnd
        ) {
            Text(
                text = "$done of $total completed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = KernSpacing.md, vertical = KernSpacing.xxs)
            )
            todos.forEach { todo ->
                val (icon, tint) = when (todo.status) {
                    TodoStatus.COMPLETED -> KernIcons.Done to LocalKernSemanticColors.current.success
                    TodoStatus.IN_PROGRESS -> KernIcons.InProgress to MaterialTheme.colorScheme.primary
                    TodoStatus.PENDING -> KernIcons.Pending to MaterialTheme.colorScheme.onSurfaceVariant
                }
                KernDropdownItem(
                    title = todo.content,
                    leading = icon,
                    leadingTint = tint
                )
            }
        }
    }
}

private data class ComposerTool(val name: String, val label: String)

private val composerTools = listOf(
    ComposerTool("list_files", "List folder contents"),
    ComposerTool("read_file", "Read a file"),
    ComposerTool("search_files", "Search names & contents"),
    ComposerTool("propose_edit", "Propose a file edit"),
    ComposerTool("delete_file", "Propose deleting a file"),
    ComposerTool("update_todos", "Update the task list")
)

// ---------------------------------------------------------------------------------------------
// Model picker (provider filter + model search; test connection before selecting)
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    profiles: List<ProviderProfile>,
    models: List<ModelOption>,
    modelsLoading: Boolean,
    modelsError: String?,
    activeProfileId: String?,
    activeModelId: String?,
    testingKey: String?,
    error: String?,
    onLoadModels: () -> Unit,
    onSelectModel: (String, String) -> Unit,
    onClearError: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: androidx.compose.material3.SheetState
) {
    var query by remember { mutableStateOf("") }

    // Ask every provider for its catalogue each time the sheet opens: models change often.
    LaunchedEffect(Unit) { onLoadModels() }

    val filtered = models.filter {
        query.isBlank() ||
            it.modelId.contains(query, ignoreCase = true) ||
            it.provider.contains(query, ignoreCase = true)
    }
    val grouped = filtered.groupBy { it.provider }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KernSpacing.md)
                .padding(bottom = KernSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(KernSpacing.sm)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(KernSpacing.xxs)) {
                Text(
                    "Select model",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Kern tests the connection before switching. A model that cannot be reached stays " +
                        "unselected.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            KernTextField(
                value = query,
                onValueChange = {
                    query = it
                    if (error != null) onClearError()
                },
                placeholder = "Search provider or model",
                leadingIcon = KernIcons.Search,
                modifier = Modifier.fillMaxWidth()
            )

            error?.let {
                KernStatusCard(
                    title = "Connection failed",
                    detail = it,
                    tone = KernTone.ERROR
                )
            }

            when {
                modelsLoading && models.isEmpty() -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = KernSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(KernSizes.iconSm),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Reading the model list from each provider…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                models.isEmpty() && profiles.isEmpty() -> KernInfoNote(
                    "No provider profiles yet. Add one in Settings first.",
                    modifier = Modifier.padding(vertical = KernSpacing.md)
                )

                // A provider may not expose /models; fall back to the profile's configured model so
                // switching providers still works.
                models.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(KernSpacing.sm)) {
                    KernInfoNote(
                        modelsError ?: "This provider did not return a model list.",
                        tone = KernTone.WARNING,
                        modifier = Modifier.padding(vertical = KernSpacing.xs)
                    )
                    profiles.forEach { profile ->
                        ModelRow(
                            profile = profile,
                            active = profile.id == activeProfileId,
                            testing = profile.id == activeProfileId && testingKey != null,
                            onClick = {
                                onClearError()
                                onSelectModel(profile.id, profile.modelId)
                            }
                        )
                    }
                }

                filtered.isEmpty() -> KernInfoNote(
                    "Nothing matched \"$query\".",
                    modifier = Modifier.padding(vertical = KernSpacing.md)
                )

                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(KernSpacing.xxs)
                ) {
                    grouped.forEach { (provider, list) ->
                        item(key = "provider:$provider") {
                            Text(
                                text = provider,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = KernSpacing.sm,
                                    top = KernSpacing.sm,
                                    bottom = KernSpacing.xxs
                                )
                            )
                        }
                        items(list, key = { it.key }) { option ->
                            ModelOptionRow(
                                option = option,
                                active = option.profileId == activeProfileId && option.modelId == activeModelId,
                                testing = option.key == testingKey,
                                onClick = {
                                    onClearError()
                                    onSelectModel(option.profileId, option.modelId)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Compact row for one discovered model — no card, so a provider's whole catalogue fits. */
@Composable
private fun ModelOptionRow(
    option: ModelOption,
    active: Boolean,
    testing: Boolean,
    onClick: () -> Unit
) {
    val background = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val content = if (active) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KernRadius.smShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = KernSpacing.sm, vertical = KernSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
    ) {
        Icon(
            imageVector = KernIcons.Sparkle,
            contentDescription = null,
            tint = if (active) content else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(KernSizes.iconSm)
        )
        Text(
            text = option.modelId,
            style = MaterialTheme.typography.bodySmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        when {
            testing -> CircularProgressIndicator(
                modifier = Modifier.size(KernSizes.iconSm),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
            active -> Icon(
                imageVector = KernIcons.Check,
                contentDescription = "Selected",
                tint = content,
                modifier = Modifier.size(KernSizes.iconMd)
            )
        }
    }
}

@Composable
private fun ModelRow(
    profile: ProviderProfile,
    active: Boolean,
    testing: Boolean,
    onClick: () -> Unit
) {
    KernOptionCard(
        icon = KernIcons.Provider,
        title = profile.modelId,
        detail = "${profile.displayName}  •  ${profile.protocol.displayName}",
        onClick = onClick,
        selected = active,
        trailing = {
            when {
                testing -> CircularProgressIndicator(
                    modifier = Modifier.size(KernSizes.iconSm),
                    strokeWidth = 1.5.dp,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.primary
                )
                active -> Icon(
                    imageVector = KernIcons.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(KernSizes.iconMd)
                )
            }
        }
    )
}

// ---------------------------------------------------------------------------------------------
// Transcript
// ---------------------------------------------------------------------------------------------

@Composable
private fun MessageBubble(message: Message, fontSizeSp: Float, showCopy: Boolean) {
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.82f).dp
    val isUser = message.role == MessageRole.USER
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * 1.5f).sp
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            if (isUser) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = KernRadius.lgShape,
                    modifier = Modifier.widthIn(max = maxBubbleWidth)
                ) {
                    SelectionContainer {
                        Text(
                            text = message.content,
                            style = bodyStyle,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = KernSpacing.md, vertical = KernSpacing.sm)
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .widthIn(max = maxBubbleWidth)
                        .padding(vertical = KernSpacing.xxs)
                ) {
                    // Thinking always sits at the top of the assistant message.
                    val reasoning = message.reasoning
                    if (!reasoning.isNullOrBlank()) {
                        ReasoningRow(reasoning.trim(), streaming = false)
                    }
                    if (message.content.isNotBlank()) {
                        SelectionContainer {
                            KernMarkdown(message.content, fontSizeSp = fontSizeSp)
                        }
                    }
                    if (message.status != MessageStatus.COMPLETE) {
                        Text(
                            text = message.status.name.lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = KernSpacing.xxs)
                        )
                    }
                }
            }
        }
        // A single copy affordance per bubble, only once the message is finished and has text.
        if (showCopy && message.content.isNotBlank() && message.status == MessageStatus.COMPLETE) {
            CopyRow(text = message.content, alignEnd = isUser)
        }
    }
}

/** Small, unobtrusive copy affordance shown beneath each message. */
@Composable
private fun CopyRow(text: String, alignEnd: Boolean) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = KernSpacing.xxs),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            onClick = {
                clipboard.setText(AnnotatedString(text))
                copied = true
            },
            shape = KernRadius.pillShape,
            color = if (copied) MaterialTheme.colorScheme.primaryContainer
            else Color.Transparent
        ) {
            Row(
                modifier = Modifier.padding(horizontal = KernSpacing.sm, vertical = KernSpacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KernSpacing.xxs)
            ) {
                Icon(
                    imageVector = if (copied) KernIcons.Check else KernIcons.Copy,
                    contentDescription = if (copied) "Copied" else "Copy message",
                    tint = if (copied) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(KernSizes.iconSm)
                )
                Text(
                    text = if (copied) "Copied" else "Copy",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copied) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StreamingText(text: String, fontSizeSp: Float) {
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.9f).dp
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(modifier = Modifier.widthIn(max = maxBubbleWidth)) {
            KernMarkdown(text, fontSizeSp = fontSizeSp)
            TypingCursor()
        }
    }
}

@Composable
private fun TypingCursor() {
    // Static caret: the transcript stays animation-free per the design brief.
    Text(
        text = "▍",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

/** Human label for the current agent phase, shown while waiting for the first token. */
private fun agentPhaseLabel(state: AgentUiState): String = when (state.agentState) {
    AgentState.PREPARING_CONTEXT -> "Preparing context"
    AgentState.VALIDATING_TOOL_CALL -> "Validating tool call"
    AgentState.RUNNING_TOOL -> state.runningTool?.let { "Running ${it.replace('_', ' ')}" } ?: "Running tool"
    AgentState.WAITING_FOR_APPROVAL -> "Waiting for approval"
    else -> "Thinking"
}

/** Minimal "working" indicator shown before the first token arrives, with elapsed seconds. */
@Composable
private fun ThinkingRow(label: String, elapsedSeconds: Int) {
    Surface(
        shape = KernRadius.pillShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(KernElevation.hairline, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = KernSpacing.md, vertical = KernSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(KernSizes.iconSm),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = if (elapsedSeconds > 0) "$label… ${elapsedSeconds}s" else "$label…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReasoningRow(text: String, streaming: Boolean) {
    var expanded by remember { mutableStateOf(streaming) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = !expanded },
            shape = KernRadius.mdShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(KernElevation.hairline, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = KernSpacing.sm, vertical = KernSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KernSpacing.xs)
            ) {
                Icon(
                    imageVector = KernIcons.Reasoning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(KernSizes.iconSm)
                )
                Text(
                    text = if (streaming) "Thinking…" else "Reasoning",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse reasoning" else "Expand reasoning",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(KernSizes.iconMd)
                )
            }
        }
        if (expanded) {
            Text(
                text = text.trim(),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = KernSpacing.sm, top = KernSpacing.xs)
            )
        }
    }
}

/**
 * One step in the transcript: a plain row with a bare icon, a status line and a chevron. No card,
 * no border and no icon badge — a run of tool calls should read as a list of steps, not as a stack
 * of outlined boxes.
 *
 * [liveOutput] is the still-running output of a shell command. It renders inside this row (expanded
 * by default, collapsible) so a command never floats above the composer.
 */
@Composable
private fun ToolRow(event: ToolEvent, liveOutput: String? = null) {
    val semantic = LocalKernSemanticColors.current
    val isLive = liveOutput != null
    // null = follow the default (open while a command streams), otherwise the user's choice wins.
    var manual by remember(event.id) { mutableStateOf<Boolean?>(null) }
    val expanded = manual ?: isLive
    val hasDetails = event.argsRedacted.isNotBlank() ||
        !event.resultSummary.isNullOrBlank() ||
        (liveOutput?.isNotBlank() == true)
    val accent = when (event.status) {
        ToolEventStatus.RUNNING -> MaterialTheme.colorScheme.primary
        ToolEventStatus.COMPLETED -> semantic.success
        ToolEventStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val scroll = rememberScrollState()
    LaunchedEffect(liveOutput, expanded) {
        if (expanded) scroll.scrollTo(scroll.maxValue)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hasDetails) { manual = !expanded }
                .padding(vertical = KernSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
        ) {
            Icon(
                imageVector = toolIcon(event.toolName),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(KernSizes.iconSm)
            )
            Text(
                text = toolStatusLabel(event),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // Line counts for a file edit: green for what it added, the scheme's error red for what
            // it removed. Kept monospace and tight so a run of edits scans like a diff stat column.
            if (event.linesAdded > 0 || event.linesRemoved > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(KernSpacing.xxs)) {
                    if (event.linesAdded > 0) {
                        Text(
                            text = "+${event.linesAdded}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = semantic.success
                        )
                    }
                    if (event.linesRemoved > 0) {
                        Text(
                            text = "-${event.linesRemoved}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            when (event.status) {
                ToolEventStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(KernSizes.iconXs),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ToolEventStatus.COMPLETED -> Icon(
                    imageVector = KernIcons.Done,
                    contentDescription = "Completed",
                    tint = semantic.success,
                    modifier = Modifier.size(KernSizes.iconSm)
                )
                ToolEventStatus.FAILED -> Icon(
                    imageVector = KernIcons.Error,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(KernSizes.iconSm)
                )
                else -> Unit
            }
            if (hasDetails) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Hide details" else "Show details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(KernSizes.iconSm)
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(
                    start = KernSizes.iconSm + KernSpacing.sm,
                    top = KernSpacing.xxs
                ),
                verticalArrangement = Arrangement.spacedBy(KernSpacing.xs)
            ) {
                if (event.argsRedacted.isNotBlank()) {
                    Text(
                        text = event.argsRedacted.take(300),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (liveOutput != null) {
                    if (liveOutput.isNotBlank()) {
                        Text(
                            text = liveOutput.trimEnd(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 15.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(scroll)
                        )
                    }
                } else {
                    val result = event.resultSummary
                    if (!result.isNullOrBlank()) {
                        Text(
                            text = result.take(300),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Approval
// ---------------------------------------------------------------------------------------------

@Composable
private fun ApprovalSheetContent(
    proposal: PatchProposal,
    reason: String?,
    onApprove: (Set<String>) -> Unit,
    onReject: () -> Unit
) {
    val semantic = LocalKernSemanticColors.current
    var showDiff by remember { mutableStateOf(false) }
    // A multi-file change can be accepted one file at a time; everything starts selected.
    val allPaths = remember(proposal.id) { proposal.files.map { it.path }.toSet() }
    var selected by remember(proposal.id) { mutableStateOf(allPaths) }
    val isMulti = proposal.files.size > 1
    val operations = proposal.files.map { it.operation }.toSet()
    val isDelete = operations == setOf(PatchOperation.DELETE)
    val actionLabel = when {
        isDelete -> "Delete file"
        operations == setOf(PatchOperation.WRITE) -> "Write file"
        else -> "Apply changes"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(KernSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(KernSpacing.md)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KernSpacing.md)
        ) {
            KernIconBadge(
                icon = if (isDelete) KernIcons.Delete else KernIcons.Diff,
                container = if (isDelete) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer,
                tint = if (isDelete) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onPrimaryContainer
            )
            Column(verticalArrangement = Arrangement.spacedBy(KernSpacing.xxs)) {
                Text(
                    "Review action",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "$actionLabel  •  ${proposal.files.size} file(s)  •  " +
                        "+${proposal.totalInsertions} / -${proposal.totalDeletions} lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isMulti) {
            KernInfoNote(
                text = "Untick any file you want to leave unchanged. Only the ticked files will be applied.",
                tone = KernTone.INFO
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(KernSpacing.sm)) {
            proposal.files.forEach { file ->
                val checked = file.path in selected
                Surface(
                    shape = KernRadius.smShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(KernElevation.hairline, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isMulti) {
                                Modifier.clickable {
                                    selected = if (checked) selected - file.path else selected + file.path
                                }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(KernSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)
                    ) {
                        if (isMulti) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { want ->
                                    selected = if (want) selected + file.path else selected - file.path
                                }
                            )
                        }
                        Icon(
                            imageVector = if (file.operation == PatchOperation.DELETE) KernIcons.Delete
                            else KernIcons.File,
                            contentDescription = null,
                            tint = if (file.operation == PatchOperation.DELETE)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(KernSizes.iconSm)
                        )
                        Text(
                            text = file.path,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "+${file.insertions} / -${file.deletions}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        reason?.let {
            KernStatusCard(
                title = "Why the agent wants this",
                detail = it,
                tone = KernTone.INFO,
                icon = KernIcons.Reasoning
            )
        }

        if (isDelete) {
            KernInfoNote(
                text = "These files will be removed from your workspace. This cannot be undone.",
                tone = KernTone.ERROR
            )
        }

        KernOutlinedButton(onClick = { showDiff = !showDiff }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showDiff) "Hide diff" else "View full diff")
        }
        if (showDiff) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(semantic.codeBackground, KernRadius.smShape)
                    .horizontalScroll(rememberScrollState())
                    .padding(KernSpacing.sm)
            ) {
                proposal.files.forEach { file ->
                    Text(
                        text = file.unifiedDiff,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = semantic.codeText
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(KernSpacing.sm)) {
            KernOutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("Reject") }
            KernButton(
                onClick = { onApprove(selected) },
                modifier = Modifier.weight(1f),
                enabled = selected.isNotEmpty(),
                colors = if (isDelete) {
                    androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    androidx.compose.material3.ButtonDefaults.buttonColors()
                }
            ) {
                val label = when {
                    isDelete && !isMulti -> "Delete"
                    isMulti -> "Approve ${selected.size} of ${proposal.files.size}"
                    else -> "Approve"
                }
                Text(label)
            }
        }
    }
}
