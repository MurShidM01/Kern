package com.aliedu.kern.agent

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aliedu.kern.core.designsystem.components.KernBottomNavBar
import com.aliedu.kern.core.designsystem.components.KernNavItem
import com.aliedu.kern.core.designsystem.components.KernNavRail
import com.aliedu.kern.core.designsystem.components.LoadingState
import com.aliedu.kern.core.designsystem.icons.KernIcons
import com.aliedu.kern.feature.agent.AgentScreen
import com.aliedu.kern.feature.agent.AgentViewModel
import com.aliedu.kern.feature.files.EditorScreen
import com.aliedu.kern.feature.files.EditorViewModel
import com.aliedu.kern.feature.files.FilesExplorerScreen
import com.aliedu.kern.feature.files.FilesViewModel
import com.aliedu.kern.feature.home.HomeScreen
import com.aliedu.kern.feature.home.HomeViewModel
import com.aliedu.kern.feature.preview.PreviewScreen
import com.aliedu.kern.feature.settings.ProviderEditScreen
import com.aliedu.kern.feature.settings.ProviderEditViewModel
import com.aliedu.kern.feature.settings.SettingsScreen
import com.aliedu.kern.feature.settings.SettingsViewModel
import com.aliedu.kern.feature.terminal.EnvironmentSetupScreen
import com.aliedu.kern.feature.terminal.PackageLogsScreen
import com.aliedu.kern.feature.terminal.PackageManagerScreen
import com.aliedu.kern.feature.terminal.TerminalScreen

private object Routes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val AGENT = "agent?sessionId={sessionId}"
    const val FILES = "files"
    const val EDITOR = "editor?path={path}"
    const val TERMINAL = "terminal"
    const val ENVIRONMENT = "environment"
    const val PACKAGES = "packages"
    const val PACKAGE_LOGS = "package-logs"
    const val SETTINGS = "settings"
    const val PROVIDER = "provider?id={id}"
    const val PREVIEW = "preview"

    fun agent(sessionId: String? = null) =
        if (sessionId == null) "agent" else "agent?sessionId=${Uri.encode(sessionId)}"

    fun editor(path: String) = "editor?path=${Uri.encode(path)}"
    fun provider(id: String?) = if (id == null) "provider" else "provider?id=${Uri.encode(id)}"
}

// The terminal is not a bottom-nav tab: it opens full-screen from the Home AppBar (its own back
// arrow returns), so the terminal keeps the whole screen like Termux.
private val navItems = listOf(
    KernNavItem(Routes.HOME, "Home", KernIcons.Home),
    KernNavItem(Routes.AGENT, "Agent", KernIcons.Agent),
    KernNavItem(Routes.FILES, "Files", KernIcons.Files),
    KernNavItem(Routes.SETTINGS, "Settings", KernIcons.Settings)
)

/**
 * Re-reads all-files access whenever the screen resumes (so a grant made in system settings is
 * picked up) and returns the action that opens the system screen, trying the per-app screen first
 * and the global list second.
 */
@Composable
private fun rememberStorageAccessRequest(homeViewModel: HomeViewModel): () -> Unit {
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { homeViewModel.refreshStorageAccess() }
    return {
        for (intent in homeViewModel.storageAccessIntents()) {
            if (runCatching { context.startActivity(intent) }.isSuccess) break
        }
    }
}

@Composable
fun KernApp(
    mainViewModel: MainViewModel = hiltViewModel(),
    openTerminal: Boolean = false,
    onTerminalOpened: () -> Unit = {},
    openAgent: Boolean = false,
    onAgentOpened: () -> Unit = {}
) {
    val mainState by mainViewModel.state.collectAsStateWithLifecycle()

    if (!mainState.ready) {
        LoadingState(label = "Loading Kern…")
        return
    }

    val navController = rememberNavController()

    // Opened from the terminal's session notification: jump straight to the terminal.
    LaunchedEffect(openTerminal) {
        if (openTerminal) {
            navController.navigate(Routes.TERMINAL) { launchSingleTop = true }
            onTerminalOpened()
        }
    }

    // Opened from the agent's "task finished" notification: show the transcript.
    LaunchedEffect(openAgent) {
        if (openAgent) {
            navController.navigate(Routes.AGENT) { launchSingleTop = true }
            onAgentOpened()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route

    // The agent asked for the preview (preview_open): take the user there rather than leaving the
    // page hidden behind a URL they would have to go find.
    val previewOpenSignal by mainViewModel.previewOpenSignal.collectAsStateWithLifecycle()
    LaunchedEffect(previewOpenSignal) {
        if (previewOpenSignal > 0 && navController.currentDestination?.route != Routes.PREVIEW) {
            navController.navigate(Routes.PREVIEW) { launchSingleTop = true }
        }
    }

    val largeWindow = LocalConfiguration.current.screenWidthDp >= 600
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val showBars = currentRoute in navItems.map { it.route } && !imeVisible
    val selectedRoute = navItems.firstOrNull { item ->
        currentDestination?.hierarchy?.any { it.route == item.route } == true
    }?.route

    val navigateToTab: (String) -> Unit = { route ->
        if (navController.currentDestination?.route != route) {
            // Prefer popping back to a destination that is already on the stack (this is what makes
            // the start destination — Home — reachable); otherwise push it with saved tab state.
            val popped = navController.popBackStack(route, inclusive = false)
            if (!popped) {
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    Scaffold(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBars && !largeWindow) {
                KernBottomNavBar(
                    items = navItems,
                    selectedRoute = selectedRoute,
                    onSelect = navigateToTab
                )
            }
        }
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (showBars && largeWindow) {
                KernNavRail(
                    items = navItems,
                    selectedRoute = selectedRoute,
                    onSelect = navigateToTab
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = if (mainState.onboardingCompleted) Routes.HOME else Routes.SETUP,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(Routes.SETUP) {
                        val homeViewModel: HomeViewModel = hiltViewModel()
                        val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
                        val requestStorageAccess = rememberStorageAccessRequest(homeViewModel)
                        val finishSetup: () -> Unit = {
                            mainViewModel.completeOnboarding()
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.SETUP) { inclusive = true }
                            }
                        }
                        SetupWizard(
                            providerConfigured = homeState.providerConfigured,
                            storageAccessGranted = homeState.storageAccessGranted,
                            hasWorkspace = homeState.workspaces.isNotEmpty(),
                            workspaceLabel = (homeState.activeWorkspace
                                ?: homeState.workspaces.firstOrNull())?.displayName,
                            onRequestStorageAccess = requestStorageAccess,
                            onOpenFolder = { uri, name -> homeViewModel.openSafWorkspace(uri, name) },
                            onCreateWorkspace = { name ->
                                homeViewModel.createAppManagedWorkspace(name)
                            },
                            onConfigureProvider = { navController.navigate(Routes.provider(null)) },
                            onFinish = finishSetup
                        )
                    }

                    composable(Routes.HOME) {
                        val homeViewModel: HomeViewModel = hiltViewModel()
                        val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
                        val requestStorageAccess = rememberStorageAccessRequest(homeViewModel)
                        HomeScreen(
                            state = homeState,
                            providerLabel = mainState.providerLabel,
                            onRequestStorageAccess = requestStorageAccess,
                            onOpenFolder = { uri, name -> homeViewModel.openSafWorkspace(uri, name) },
                            onCreateWorkspace = { name -> homeViewModel.createAppManagedWorkspace(name) },
                            onOpenWorkspace = { workspace ->
                                homeViewModel.setActive(workspace)
                                navigateToTab(Routes.FILES)
                            },
                            onNewTask = { workspace ->
                                homeViewModel.setActive(workspace)
                                navigateToTab(Routes.agent())
                            },
                            onOpenSession = { session -> navController.navigate(Routes.agent(session.id)) },
                            onConfigureProvider = { navController.navigate(Routes.provider(null)) },
                            onOpenSettings = { navigateToTab(Routes.SETTINGS) },
                            onOpenTerminal = { navController.navigate(Routes.TERMINAL) },
                            onDeleteWorkspace = { homeViewModel.deleteWorkspace(it) },
                            onDeleteSession = { homeViewModel.deleteSession(it) },
                            onClearSessions = homeViewModel::clearSessions,
                            onRefresh = homeViewModel::refresh
                        )
                    }

                    composable(
                        route = Routes.AGENT,
                        arguments = listOf(
                            navArgument("sessionId") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) { entry ->
                        val agentViewModel: AgentViewModel = hiltViewModel()
                        val agentState by agentViewModel.state.collectAsStateWithLifecycle()
                        val sessionId = entry.arguments?.getString("sessionId")
                        LaunchedEffect(sessionId) { if (sessionId != null) agentViewModel.loadSession(sessionId) }
                        AgentScreen(
                            state = agentState,
                            onSend = agentViewModel::sendMessage,
                            onCancel = agentViewModel::cancel,
                            onApprove = agentViewModel::approvePendingChange,
                            onApproveSelection = agentViewModel::approvePendingChange,
                            onReject = agentViewModel::rejectPendingChange,
                            onApprovePlan = agentViewModel::approvePlan,
                            onRequestPlanChanges = agentViewModel::requestPlanChanges,
                            onRejectPlan = agentViewModel::rejectPlan,
                            onRetry = agentViewModel::retryLastTurn,
                            onNewSession = agentViewModel::startNewSession,
                            onConfigureProvider = { navController.navigate(Routes.provider(null)) },
                            onSelectModel = { profileId, modelId ->
                                agentViewModel.selectModel(profileId, modelId)
                            },
                            onLoadModels = agentViewModel::loadModels,
                            onPermissionModeChange = agentViewModel::setPermissionMode,
                            onToggleThinking = agentViewModel::setThinking,
                            onClearModelTestError = agentViewModel::clearModelTestError,
                            onRefresh = agentViewModel::refresh,
                            onOpenPreview = { navController.navigate(Routes.PREVIEW) { launchSingleTop = true } }
                        )
                    }

                    composable(Routes.FILES) {
                        val filesViewModel: FilesViewModel = hiltViewModel()
                        val filesState by filesViewModel.state.collectAsStateWithLifecycle()
                        val homeViewModel: HomeViewModel = hiltViewModel()
                        val filesHomeState by homeViewModel.uiState.collectAsStateWithLifecycle()
                        val requestStorageAccess = rememberStorageAccessRequest(homeViewModel)
                        FilesExplorerScreen(
                            state = filesState,
                            onOpen = filesViewModel::open,
                            onUp = filesViewModel::up,
                            onRefresh = filesViewModel::refresh,
                            onSearch = filesViewModel::onSearchQueryChange,
                            onOpenFile = { path -> navController.navigate(Routes.editor(path)) },
                            onNewFile = filesViewModel::createFile,
                            onNewFolder = filesViewModel::createDirectory,
                            onOpenFolder = { uri, name -> homeViewModel.openSafWorkspace(uri, name) },
                            storageAccessGranted = filesHomeState.storageAccessGranted,
                            onRequestStorageAccess = requestStorageAccess,
                            onToggleSidebar = filesViewModel::toggleSidebar,
                            onCloseSidebar = filesViewModel::closeSidebar,
                            onToggleDirectory = filesViewModel::toggleDirectory
                        )
                    }

                    composable(
                        route = Routes.EDITOR,
                        arguments = listOf(
                            navArgument("path") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) { entry ->
                        val editorViewModel: EditorViewModel = hiltViewModel()
                        val editorState by editorViewModel.state.collectAsStateWithLifecycle()
                        // The editor's drawer needs a tree of its own: this route is a separate
                        // back-stack entry, so it cannot borrow the Files screen's view model.
                        val editorTree: FilesViewModel = hiltViewModel()
                        val editorTreeState by editorTree.state.collectAsStateWithLifecycle()
                        val path = entry.arguments?.getString("path").orEmpty()
                        val workspaceId = mainState.activeWorkspaceId
                        LaunchedEffect(path, workspaceId) {
                            if (path.isNotBlank() && workspaceId != null) {
                                editorViewModel.load(workspaceId, path)
                            }
                        }
                        EditorScreen(
                            state = editorState,
                            onBack = { navController.popBackStack() },
                            onTextChange = editorViewModel::onTextChange,
                            onSave = editorViewModel::save,
                            onReload = {
                                if (workspaceId != null) editorViewModel.load(workspaceId, path)
                            },
                            treeState = editorTreeState,
                            onToggleSidebar = editorTree::toggleSidebar,
                            onCloseSidebar = editorTree::closeSidebar,
                            onToggleDirectory = editorTree::toggleDirectory,
                            onOpenFile = { target -> navController.navigate(Routes.editor(target)) },
                            onNewFile = editorTree::createFile,
                            onNewFolder = editorTree::createDirectory,
                            onRefreshTree = editorTree::refreshTree
                        )
                    }

                    composable(Routes.TERMINAL) {
                        TerminalScreen(
                            onBack = { navController.popBackStack() },
                            onOpenEnvironmentSetup = { navController.navigate(Routes.ENVIRONMENT) },
                            onOpenPackages = { navController.navigate(Routes.PACKAGES) }
                        )
                    }

                    // The live dev-server preview. Not a bottom-nav tab: it opens from the agent's
                    // app bar, or automatically when the agent itself asks to see the page.
                    composable(Routes.PREVIEW) {
                        PreviewScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Routes.PACKAGES) {
                        PackageManagerScreen(
                            onBack = { navController.popBackStack() },
                            onOpenEnvironmentSetup = { navController.navigate(Routes.ENVIRONMENT) },
                            onOpenLogs = { navController.navigate(Routes.PACKAGE_LOGS) },
                            onOpenTerminal = { navController.navigate(Routes.TERMINAL) }
                        )
                    }

                    composable(Routes.PACKAGE_LOGS) {
                        PackageLogsScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Routes.ENVIRONMENT) {
                        EnvironmentSetupScreen(
                            onBack = { navController.popBackStack() },
                            onOpenTerminal = {
                                navController.popBackStack(Routes.TERMINAL, inclusive = false)
                            },
                            onOpenPackages = { navController.navigate(Routes.PACKAGES) }
                        )
                    }

                    composable(Routes.SETTINGS) {
                        val settingsViewModel: SettingsViewModel = hiltViewModel()
                        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                        val modelsSheet by settingsViewModel.models.collectAsStateWithLifecycle()
                        SettingsScreen(
                            state = settingsState,
                            appVersion = BuildConfig.VERSION_NAME,
                            buildType = BuildConfig.BUILD_TYPE,
                            packageName = BuildConfig.APPLICATION_ID,
                            onThemeModeChange = mainViewModel::setThemeMode,
                            onThemePaletteChange = settingsViewModel::setThemePalette,
                            onDynamicColorChange = settingsViewModel::setDynamicColor,
                            onEditorFontSizeChange = settingsViewModel::setEditorFontSize,
                            onChatFontSizeChange = settingsViewModel::setChatFontSize,
                            onTerminalFontSizeChange = settingsViewModel::setTerminalFontSize,
                            onTerminalThemeChange = settingsViewModel::setTerminalTheme,
                            onTerminalScrollbackChange = settingsViewModel::setTerminalScrollback,
                            onShowReasoningChange = settingsViewModel::setShowReasoning,
                            onAutoScrollChange = settingsViewModel::setAutoScroll,
                            onAddProvider = { navController.navigate(Routes.provider(null)) },
                            onEditProvider = { id -> navController.navigate(Routes.provider(id)) },
                            modelsSheet = modelsSheet,
                            onOpenProviderModels = settingsViewModel::openModels,
                            onDismissModels = settingsViewModel::dismissModels,
                            onSelectModel = settingsViewModel::selectModel,
                            onSetDefaultProvider = settingsViewModel::setDefaultProfile,
                            onDeleteProvider = settingsViewModel::deleteProfile,
                            onClearSessions = settingsViewModel::clearAllSessions,
                            onRefresh = settingsViewModel::refresh
                        )
                    }

                    composable(
                        route = Routes.PROVIDER,
                        arguments = listOf(
                            navArgument("id") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) { entry ->
                        val providerViewModel: ProviderEditViewModel = hiltViewModel()
                        val providerState by providerViewModel.state.collectAsStateWithLifecycle()
                        val id = entry.arguments?.getString("id")
                        LaunchedEffect(providerState.saved) {
                            if (providerState.saved) navController.popBackStack()
                        }
                        ProviderEditScreen(
                            profileId = id,
                            state = providerState,
                            onLoad = providerViewModel::load,
                            onBack = { navController.popBackStack() },
                            onDisplayNameChange = providerViewModel::onDisplayNameChange,
                            onBaseUrlChange = providerViewModel::onBaseUrlChange,
                            onModelIdChange = providerViewModel::onModelIdChange,
                            onApiKeyChange = providerViewModel::onApiKeyChange,
                            onToggleApiKeyVisibility = providerViewModel::toggleApiKeyVisibility,
                            onProtocolChange = providerViewModel::onProtocolChange,
                            onTest = providerViewModel::testConnection,
                            onSave = providerViewModel::save
                        )
                    }
                }
            }
        }
    }
}
