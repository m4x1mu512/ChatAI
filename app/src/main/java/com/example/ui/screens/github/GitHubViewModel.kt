package com.example.ui.screens.github

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.GitHubBranchInfo
import com.example.core.model.GitHubContentEntry
import com.example.core.model.GitHubFileContent
import com.example.core.model.GitHubIntegrationSettings
import com.example.core.model.GitHubIssueSummary
import com.example.core.model.GitHubPullRequestSummary
import com.example.core.model.GitHubRepoSummary
import com.example.data.github.GitHubRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoInspectorState(
    val repo: GitHubRepoSummary,
    val selectedBranch: String,
    val branches: List<GitHubBranchInfo> = emptyList(),
    val currentPath: String = "",
    val entries: List<GitHubContentEntry> = emptyList(),
    val previewFile: GitHubFileContent? = null,
    val issues: List<GitHubIssueSummary> = emptyList(),
    val pullRequests: List<GitHubPullRequestSummary> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

private data class GitHubInternalState(
    val tokenInput: String = "",
    val isConnecting: Boolean = false,
    val isLoadingRepos: Boolean = false,
    val repos: List<GitHubRepoSummary> = emptyList(),
    val repoFilter: String = "",
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val inspector: RepoInspectorState? = null,
)

data class GitHubUiState(
    val settings: GitHubIntegrationSettings = GitHubIntegrationSettings(),
    val maskedToken: String = "",
    val tokenInput: String = "",
    val isConnecting: Boolean = false,
    val isLoadingRepos: Boolean = false,
    val repos: List<GitHubRepoSummary> = emptyList(),
    val repoFilter: String = "",
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val inspector: RepoInspectorState? = null,
) {
    val filteredRepos: List<GitHubRepoSummary>
        get() {
            val q = repoFilter.trim().lowercase()
            if (q.isEmpty()) return repos
            return repos.filter {
                it.fullName.lowercase().contains(q) || it.description.lowercase().contains(q)
            }
        }
}

@HiltViewModel
class GitHubViewModel @Inject constructor(
    private val gitHubRepository: GitHubRepository,
) : ViewModel() {

    private val internal = MutableStateFlow(GitHubInternalState())

    val uiState: StateFlow<GitHubUiState> = combine(
        gitHubRepository.settingsFlow,
        internal,
    ) { settings, ext ->
        GitHubUiState(
            settings = settings,
            maskedToken = if (settings.isConnected) gitHubRepository.getMaskedToken() else "",
            tokenInput = ext.tokenInput,
            isConnecting = ext.isConnecting,
            isLoadingRepos = ext.isLoadingRepos,
            repos = ext.repos,
            repoFilter = ext.repoFilter,
            errorMessage = ext.errorMessage,
            statusMessage = ext.statusMessage,
            inspector = ext.inspector,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GitHubUiState())

    init {
        viewModelScope.launch {
            val settings = gitHubRepository.getSettings()
            if (settings.isConnected && gitHubRepository.hasToken()) {
                loadRepos()
            }
        }
    }

    fun updateTokenInput(value: String) {
        internal.update { it.copy(tokenInput = value, errorMessage = null) }
    }

    fun updateRepoFilter(query: String) {
        internal.update { it.copy(repoFilter = query) }
    }

    fun clearStatus() {
        internal.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    fun connectWithToken() {
        val raw = internal.value.tokenInput.trim()
        if (raw.isEmpty()) return
        viewModelScope.launch {
            internal.update { it.copy(isConnecting = true, errorMessage = null, statusMessage = null) }
            gitHubRepository.connectWithToken(raw).fold(
                onSuccess = { user ->
                    internal.update {
                        it.copy(
                            isConnecting = false,
                            tokenInput = "",
                            statusMessage = "@${user.login}",
                            errorMessage = null,
                        )
                    }
                    loadRepos()
                },
                onFailure = { err ->
                    internal.update {
                        it.copy(
                            isConnecting = false,
                            errorMessage = err.message ?: "Failed to verify GitHub token",
                        )
                    }
                },
            )
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            gitHubRepository.disconnect()
            internal.update {
                GitHubInternalState()
            }
        }
    }

    fun setToolsEnabled(enabled: Boolean) {
        viewModelScope.launch { gitHubRepository.setToolsEnabled(enabled) }
    }

    fun setConfirmWrites(confirm: Boolean) {
        viewModelScope.launch { gitHubRepository.setConfirmWritesBeforeExecute(confirm) }
    }

    fun selectDefaultRepo(fullName: String) {
        viewModelScope.launch { gitHubRepository.setDefaultRepo(fullName) }
    }

    fun loadRepos() {
        viewModelScope.launch {
            internal.update { it.copy(isLoadingRepos = true, errorMessage = null) }
            gitHubRepository.listRepos().fold(
                onSuccess = { list ->
                    internal.update { it.copy(isLoadingRepos = false, repos = list) }
                },
                onFailure = { err ->
                    internal.update {
                        it.copy(
                            isLoadingRepos = false,
                            errorMessage = err.message ?: "Could not load repositories",
                        )
                    }
                },
            )
        }
    }

    fun createRepository(name: String, description: String, isPrivate: Boolean) {
        if (name.isBlank()) return
        viewModelScope.launch {
            internal.update { it.copy(isLoadingRepos = true, errorMessage = null) }
            gitHubRepository.createRepo(name.trim(), description.trim(), isPrivate, autoInit = true).fold(
                onSuccess = { created ->
                    gitHubRepository.setDefaultRepo(created.fullName)
                    internal.update {
                        it.copy(
                            isLoadingRepos = false,
                            repos = listOf(created) + it.repos.filterNot { r -> r.fullName == created.fullName },
                            statusMessage = created.fullName,
                        )
                    }
                },
                onFailure = { err ->
                    internal.update {
                        it.copy(isLoadingRepos = false, errorMessage = err.message ?: "Could not create repository")
                    }
                },
            )
        }
    }

    fun openInspector(repo: GitHubRepoSummary) {
        val initial = RepoInspectorState(
            repo = repo,
            selectedBranch = repo.defaultBranch.ifBlank { "main" },
            isLoading = true,
        )
        internal.update { it.copy(inspector = initial) }
        refreshInspectorData(repo.owner, repo.name, initial.selectedBranch, "")
    }

    fun closeInspector() {
        internal.update { it.copy(inspector = null) }
    }

    fun switchInspectorBranch(branch: String) {
        val curr = internal.value.inspector ?: return
        internal.update {
            it.copy(inspector = curr.copy(selectedBranch = branch, currentPath = "", previewFile = null, isLoading = true))
        }
        refreshInspectorData(curr.repo.owner, curr.repo.name, branch, "")
    }

    fun openInspectorDirectory(path: String) {
        val curr = internal.value.inspector ?: return
        internal.update {
            it.copy(inspector = curr.copy(currentPath = path, previewFile = null, isLoading = true, error = null))
        }
        viewModelScope.launch {
            val res = gitHubRepository.listDirectory(curr.repo.owner, curr.repo.name, path, curr.selectedBranch)
            internal.update { st ->
                val insp = st.inspector ?: return@update st
                res.fold(
                    onSuccess = { items -> st.copy(inspector = insp.copy(entries = items, isLoading = false)) },
                    onFailure = { e -> st.copy(inspector = insp.copy(isLoading = false, error = e.message)) },
                )
            }
        }
    }

    fun openInspectorFile(path: String) {
        val curr = internal.value.inspector ?: return
        internal.update { it.copy(inspector = curr.copy(isLoading = true, error = null)) }
        viewModelScope.launch {
            val res = gitHubRepository.readFile(curr.repo.owner, curr.repo.name, path, curr.selectedBranch)
            internal.update { st ->
                val insp = st.inspector ?: return@update st
                res.fold(
                    onSuccess = { file -> st.copy(inspector = insp.copy(previewFile = file, isLoading = false)) },
                    onFailure = { e -> st.copy(inspector = insp.copy(isLoading = false, error = e.message)) },
                )
            }
        }
    }

    fun closeFilePreview() {
        internal.update { st ->
            val insp = st.inspector ?: return@update st
            st.copy(inspector = insp.copy(previewFile = null))
        }
    }

    fun createBranchInInspector(newBranchName: String) {
        val curr = internal.value.inspector ?: return
        if (newBranchName.isBlank()) return
        internal.update { it.copy(inspector = curr.copy(isLoading = true, error = null)) }
        viewModelScope.launch {
            val res = gitHubRepository.createBranch(
                curr.repo.owner,
                curr.repo.name,
                newBranchName.trim(),
                curr.selectedBranch,
            )
            res.fold(
                onSuccess = { created ->
                    refreshInspectorData(curr.repo.owner, curr.repo.name, created.name, "")
                },
                onFailure = { e ->
                    internal.update { st ->
                        val insp = st.inspector ?: return@update st
                        st.copy(inspector = insp.copy(isLoading = false, error = e.message))
                    }
                },
            )
        }
    }

    private fun refreshInspectorData(owner: String, repo: String, branch: String, path: String) {
        viewModelScope.launch {
            val branchesRes = gitHubRepository.listBranches(owner, repo)
            val entriesRes = gitHubRepository.listDirectory(owner, repo, path, branch)
            val issuesRes = gitHubRepository.listIssues(owner, repo, "open")
            val prsRes = gitHubRepository.listPullRequests(owner = owner, repo = repo, state = "open")

            internal.update { st ->
                val insp = st.inspector ?: return@update st
                val err = entriesRes.exceptionOrNull()?.message
                    ?: branchesRes.exceptionOrNull()?.message
                st.copy(
                    inspector = insp.copy(
                        selectedBranch = branch,
                        branches = branchesRes.getOrDefault(insp.branches),
                        currentPath = path,
                        entries = entriesRes.getOrDefault(emptyList()),
                        issues = issuesRes.getOrDefault(emptyList()).filter { !it.isPullRequest },
                        pullRequests = prsRes.getOrDefault(emptyList()),
                        isLoading = false,
                        error = err,
                    ),
                )
            }
        }
    }
}
