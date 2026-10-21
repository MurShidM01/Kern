package com.aliedu.kern.feature.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliedu.kern.core.data.preferences.SettingsRepository
import com.aliedu.kern.core.data.repository.WorkspaceRepository
import com.aliedu.kern.core.model.FileKind
import com.aliedu.kern.core.model.FileNode
import com.aliedu.kern.core.model.GrantState
import com.aliedu.kern.core.model.SearchMatch
import com.aliedu.kern.core.model.Workspace
import com.aliedu.kern.core.workspace.WorkspaceAccessException
import com.aliedu.kern.core.workspace.WorkspaceAdapter
import com.aliedu.kern.core.workspace.WorkspaceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FilesUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val workspace: Workspace? = null,
    val currentPath: String = "",
    val nodes: List<FileNode> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<SearchMatch> = emptyList(),
    val grantRevoked: Boolean = false,
    val error: String? = null,
    /** Folder-tree sidebar (shadcn-style) — open state, expanded directories, and cached children. */
    val sidebarOpen: Boolean = false,
    val expandedDirs: Set<String> = emptySet(),
    val dirChildren: Map<String, List<FileNode>> = emptyMap(),
    val loadingDirs: Set<String> = emptySet()
) {
    val breadcrumb: String get() = if (currentPath.isEmpty()) "/" else "/$currentPath"
}

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val settingsRepository: SettingsRepository,
    private val workspaceManager: WorkspaceManager
) : ViewModel() {

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    private var adapter: WorkspaceAdapter? = null

    init {
        viewModelScope.launch {
            settingsRepository.lastWorkspaceId.collect { id ->
                val workspace = id?.let { workspaceRepository.getWorkspace(it) }
                if (workspace == null) {
                    _state.value = FilesUiState(loading = false, error = "No workspace selected.")
                    adapter = null
                } else if (workspace.id != _state.value.workspace?.id) {
                    loadWorkspace(workspace)
                }
            }
        }
    }

    private suspend fun loadWorkspace(workspace: Workspace) {
        val grant = workspaceManager.refreshGrantState(workspace)
        if (grant == GrantState.REVOKED && workspace.sourceType.name == "SELECTED_FOLDER") {
            _state.value = FilesUiState(
                loading = false,
                workspace = workspace,
                grantRevoked = true,
                error = "Folder access was revoked. Reconnect the folder to continue."
            )
            adapter = null
            return
        }
        val newAdapter = runCatching { workspaceManager.adapterFor(workspace) }.getOrNull()
        adapter = newAdapter
        _state.value = FilesUiState(loading = true, workspace = workspace)
        list("")
    }

    fun open(path: String) {
        viewModelScope.launch { list(path) }
    }

    fun up() {
        val current = _state.value.currentPath
        val parent = if (current.contains('/')) current.substringBeforeLast('/') else ""
        viewModelScope.launch { list(parent) }
    }

    /** Re-list the current directory. Drives pull-to-refresh, so the list stays visible meanwhile. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true, dirChildren = emptyMap())
            list(_state.value.currentPath, showLoading = false)
            _state.value = _state.value.copy(refreshing = false)
        }
    }

    private suspend fun list(path: String, showLoading: Boolean = true) {
        val a = adapter ?: return
        _state.value = _state.value.copy(
            loading = showLoading,
            error = null,
            searchQuery = "",
            searchResults = emptyList()
        )
        try {
            val nodes = a.list(path)
            _state.value = _state.value.copy(
                loading = false,
                currentPath = path,
                nodes = nodes,
                // Keep the sidebar tree in sync with what we just listed.
                dirChildren = _state.value.dirChildren + (path to nodes)
            )
        } catch (e: WorkspaceAccessException) {
            _state.value = _state.value.copy(loading = false, error = e.message ?: "Could not list files.")
        }
    }

    fun toggleSidebar() {
        val opening = !_state.value.sidebarOpen
        _state.value = _state.value.copy(sidebarOpen = opening)
        if (opening && !_state.value.dirChildren.containsKey("")) {
            viewModelScope.launch { loadChildren("") }
        }
    }

    fun closeSidebar() {
        if (_state.value.sidebarOpen) _state.value = _state.value.copy(sidebarOpen = false)
    }

    /** Expands a directory in the sidebar tree, lazily loading its children the first time. */
    fun toggleDirectory(path: String) {
        val current = _state.value
        if (current.expandedDirs.contains(path)) {
            _state.value = current.copy(expandedDirs = current.expandedDirs - path)
            return
        }
        _state.value = current.copy(expandedDirs = current.expandedDirs + path)
        if (!current.dirChildren.containsKey(path)) {
            viewModelScope.launch { loadChildren(path) }
        }
    }

    private suspend fun loadChildren(path: String) {
        val a = adapter ?: return
        _state.value = _state.value.copy(loadingDirs = _state.value.loadingDirs + path)
        val children = runCatching { a.list(path) }.getOrNull()
        _state.value = _state.value.copy(
            dirChildren = if (children != null) _state.value.dirChildren + (path to children)
            else _state.value.dirChildren,
            loadingDirs = _state.value.loadingDirs - path
        )
    }

    fun onSearchQueryChange(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        if (query.isBlank()) {
            _state.value = _state.value.copy(searchResults = emptyList())
            return
        }
        viewModelScope.launch {
            val a = adapter ?: return@launch
            runCatching { a.search(query, includeContent = true) }
                .onSuccess { results -> _state.value = _state.value.copy(searchResults = results, error = null) }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }

    fun createFile(path: String) {
        viewModelScope.launch {
            val a = adapter ?: return@launch
            runCatching { a.createFile(path, "") }
                .onSuccess { list(_state.value.currentPath) }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }

    /** Creates a folder relative to the directory being browsed. */
    fun createDirectory(path: String) {
        viewModelScope.launch {
            val a = adapter ?: return@launch
            runCatching { a.createDirectory(path) }
                .onSuccess { list(_state.value.currentPath) }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }

    /**
     * Reloads the whole folder tree: the root, every expanded directory, and the current listing.
     * Backs the drawer's refresh action, so a file created elsewhere (terminal, agent, another app)
     * appears without closing the drawer.
     */
    fun refreshTree() {
        viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true, dirChildren = emptyMap())
            val expanded = _state.value.expandedDirs
            loadChildren("")
            expanded.forEach { loadChildren(it) }
            list(_state.value.currentPath, showLoading = false)
            _state.value = _state.value.copy(refreshing = false)
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun isDirectory(node: FileNode): Boolean = node.kind == FileKind.DIRECTORY
}

data class EditorUiState(
    val loading: Boolean = true,
    val path: String = "",
    val text: String = "",
    val originalText: String = "",
    val truncated: Boolean = false,
    val readOnly: Boolean = false,
    val saving: Boolean = false,
    val savedAt: Long? = null,
    val error: String? = null,
    val fontSizeSp: Float = 13f
) {
    val isDirty: Boolean get() = text != originalText
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val settingsRepository: SettingsRepository,
    private val workspaceManager: WorkspaceManager
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var adapter: WorkspaceAdapter? = null
    private var workspaceId: String? = null

    init {
        viewModelScope.launch {
            settingsRepository.editorFontSizeSp.collect { size ->
                _state.value = _state.value.copy(fontSizeSp = size)
            }
        }
    }

    fun load(workspaceId: String, path: String) {
        if (this.workspaceId == workspaceId && _state.value.path == path && !_state.value.loading) return
        this.workspaceId = workspaceId
        viewModelScope.launch {
            _state.value = EditorUiState(loading = true, path = path, fontSizeSp = _state.value.fontSizeSp)
            val workspace = workspaceRepository.getWorkspace(workspaceId)
            if (workspace == null) {
                _state.value = _state.value.copy(loading = false, error = "Workspace not found.")
                return@launch
            }
            val a = runCatching { workspaceManager.adapterFor(workspace) }.getOrNull()
            adapter = a
            if (a == null) {
                _state.value = _state.value.copy(loading = false, error = "Workspace is not accessible.")
                return@launch
            }
            try {
                // The editor numbers every line, so it reads the whole file rather than the
                // 1 MB cap the agent's file tools use (which cut long files off at a few hundred
                // lines and made the gutter look like it stopped).
                val content = a.readText(path, maxBytes = EDITOR_MAX_READ_BYTES)
                _state.value = _state.value.copy(
                    loading = false,
                    text = content.text,
                    originalText = content.text,
                    truncated = content.truncated,
                    error = null
                )
            } catch (e: WorkspaceAccessException) {
                _state.value = _state.value.copy(
                    loading = false,
                    readOnly = true,
                    error = e.message ?: "This file cannot be opened as text."
                )
            }
        }
    }

    fun onTextChange(value: String) {
        _state.value = _state.value.copy(text = value)
    }

    fun save() {
        val a = adapter ?: return
        val current = _state.value
        if (!current.isDirty || current.readOnly) return
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, error = null)
            runCatching { a.writeText(current.path, current.text) }
                .onSuccess { result ->
                    _state.value = _state.value.copy(
                        saving = false,
                        originalText = current.text,
                        savedAt = result.savedAt
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        saving = false,
                        error = "Could not save: ${it.message}"
                    )
                }
        }
    }
}

/**
 * How much of a file the editor loads. Far above any real source file, so the gutter numbers every
 * line; the cap only exists so opening a multi-gigabyte log cannot exhaust the heap.
 */
private const val EDITOR_MAX_READ_BYTES: Long = 16L * 1024 * 1024
