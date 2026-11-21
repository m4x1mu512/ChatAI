package com.example.data.github

import com.example.core.model.GitHubBranchInfo
import com.example.core.model.GitHubContentEntry
import com.example.core.model.GitHubFileContent
import com.example.core.model.GitHubIntegrationSettings
import com.example.core.model.GitHubIssueSummary
import com.example.core.model.GitHubPullRequestSummary
import com.example.core.model.GitHubRepoSummary
import com.example.core.model.GitHubUserInfo
import com.example.core.util.SecureKeyStore
import com.example.data.local.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitHubRepository @Inject constructor(
    private val apiClient: GitHubApiClient,
    private val secureKeyStore: SecureKeyStore,
    private val settingsDataStore: SettingsDataStore,
) {
    val settingsFlow: Flow<GitHubIntegrationSettings> = settingsDataStore.githubSettingsFlow

    suspend fun getSettings(): GitHubIntegrationSettings = settingsFlow.first()

    fun hasToken(): Boolean = secureKeyStore.hasGitHubToken()

    fun getMaskedToken(): String = secureKeyStore.getMaskedGitHubToken()

    suspend fun connectWithToken(rawToken: String): Result<GitHubUserInfo> {
        val trimmed = rawToken.trim()
        val result = apiClient.verifyToken(trimmed)
        result.onSuccess { user ->
            secureKeyStore.saveGitHubToken(trimmed)
            settingsDataStore.saveGitHubUser(user)
        }
        return result
    }

    suspend fun refreshConnection(): Result<GitHubUserInfo> {
        val token = secureKeyStore.getGitHubToken()
        if (token.isBlank()) {
            return Result.failure(IllegalStateException("GitHub token is not configured"))
        }
        val result = apiClient.verifyToken(token)
        result.onSuccess { user ->
            settingsDataStore.saveGitHubUser(user)
        }
        return result
    }

    suspend fun disconnect() {
        secureKeyStore.deleteGitHubToken()
        settingsDataStore.clearGitHubConnection()
    }

    suspend fun setToolsEnabled(enabled: Boolean) = settingsDataStore.setGitHubToolsEnabled(enabled)

    suspend fun setDefaultRepo(fullName: String) = settingsDataStore.setGitHubDefaultRepo(fullName)

    suspend fun setConfirmWritesBeforeExecute(confirm: Boolean) =
        settingsDataStore.setGitHubConfirmWrites(confirm)

    suspend fun listRepos(page: Int = 1): Result<List<GitHubRepoSummary>> =
        withToken { token -> apiClient.listUserRepos(token, page = page) }

    suspend fun getRepo(owner: String, repo: String): Result<GitHubRepoSummary> =
        withToken { token -> apiClient.getRepo(token, owner, repo) }

    suspend fun createRepo(
        name: String,
        description: String = "",
        isPrivate: Boolean = true,
        autoInit: Boolean = true,
    ): Result<GitHubRepoSummary> =
        withToken { token -> apiClient.createRepo(token, name, description, isPrivate, autoInit) }

    suspend fun listBranches(owner: String, repo: String): Result<List<GitHubBranchInfo>> =
        withToken { token -> apiClient.listBranches(token, owner, repo) }

    suspend fun createBranch(
        owner: String,
        repo: String,
        newBranch: String,
        fromBranch: String,
    ): Result<GitHubBranchInfo> =
        withToken { token -> apiClient.createBranch(token, owner, repo, newBranch, fromBranch) }

    suspend fun listDirectory(
        owner: String,
        repo: String,
        path: String = "",
        ref: String? = null,
    ): Result<List<GitHubContentEntry>> =
        withToken { token -> apiClient.listDirectory(token, owner, repo, path, ref) }

    suspend fun getRepoTree(
        owner: String,
        repo: String,
        ref: String,
        pathPrefix: String = "",
    ): Result<List<GitHubContentEntry>> =
        withToken { token -> apiClient.getRepoTree(token, owner, repo, ref, pathPrefix) }

    suspend fun readFile(
        owner: String,
        repo: String,
        path: String,
        ref: String? = null,
    ): Result<GitHubFileContent> =
        withToken { token -> apiClient.readFile(token, owner, repo, path, ref) }

    suspend fun createOrUpdateFile(
        owner: String,
        repo: String,
        path: String,
        content: String,
        commitMessage: String,
        branch: String,
    ): Result<Pair<String, String>> =
        withToken { token -> apiClient.createOrUpdateFile(token, owner, repo, path, content, commitMessage, branch) }

    suspend fun deleteFile(
        owner: String,
        repo: String,
        path: String,
        commitMessage: String,
        branch: String,
    ): Result<String> =
        withToken { token -> apiClient.deleteFile(token, owner, repo, path, commitMessage, branch) }

    suspend fun searchCode(
        owner: String,
        repo: String,
        query: String,
    ): Result<List<GitHubContentEntry>> =
        withToken { token -> apiClient.searchCode(token, owner, repo, query) }

    suspend fun listIssues(
        owner: String,
        repo: String,
        state: String = "open",
    ): Result<List<GitHubIssueSummary>> =
        withToken { token -> apiClient.listIssues(token, owner, repo, state) }

    suspend fun createIssue(
        owner: String,
        repo: String,
        title: String,
        body: String = "",
        labels: List<String> = emptyList(),
    ): Result<GitHubIssueSummary> =
        withToken { token -> apiClient.createIssue(token, owner, repo, title, body, labels) }

    suspend fun updateIssue(
        owner: String,
        repo: String,
        issueNumber: Int,
        state: String? = null,
        title: String? = null,
        body: String? = null,
    ): Result<GitHubIssueSummary> =
        withToken { token -> apiClient.updateIssue(token, owner, repo, issueNumber, state, title, body) }

    suspend fun addComment(
        owner: String,
        repo: String,
        issueOrPrNumber: Int,
        body: String,
    ): Result<String> =
        withToken { token -> apiClient.addComment(token, owner, repo, issueOrPrNumber, body) }

    suspend fun listPullRequests(
        tokenOverride: String? = null,
        owner: String,
        repo: String,
        state: String = "open",
    ): Result<List<GitHubPullRequestSummary>> =
        withToken(tokenOverride) { token -> apiClient.listPullRequests(token, owner, repo, state) }

    suspend fun createPullRequest(
        owner: String,
        repo: String,
        title: String,
        body: String = "",
        head: String,
        base: String,
    ): Result<GitHubPullRequestSummary> =
        withToken { token -> apiClient.createPullRequest(token, owner, repo, title, body, head, base) }

    suspend fun mergePullRequest(
        owner: String,
        repo: String,
        pullNumber: Int,
        commitTitle: String? = null,
        mergeMethod: String = "squash",
    ): Result<String> =
        withToken { token -> apiClient.mergePullRequest(token, owner, repo, pullNumber, commitTitle, mergeMethod) }

    private inline fun <T> withToken(
        tokenOverride: String? = null,
        block: (String) -> Result<T>,
    ): Result<T> {
        val token = tokenOverride?.takeIf { it.isNotBlank() } ?: secureKeyStore.getGitHubToken()
        if (token.isBlank()) {
            return Result.failure(IllegalStateException("GitHub is not connected. Add a token in GitHub Settings."))
        }
        return block(token)
    }
}
