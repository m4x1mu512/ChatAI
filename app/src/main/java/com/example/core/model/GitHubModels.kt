package com.example.core.model

import kotlinx.serialization.Serializable

@Serializable
data class GitHubUserInfo(
    val login: String = "",
    val name: String = "",
    val avatarUrl: String = "",
    val scopes: String = "",
)

@Serializable
data class GitHubRepoSummary(
    val id: Long,
    val fullName: String,
    val owner: String,
    val name: String,
    val description: String = "",
    val isPrivate: Boolean = false,
    val defaultBranch: String = "main",
    val updatedAt: String = "",
    val htmlUrl: String = "",
)

@Serializable
data class GitHubBranchInfo(
    val name: String,
    val isProtected: Boolean = false,
    val sha: String = "",
)

@Serializable
data class GitHubContentEntry(
    val name: String,
    val path: String,
    val type: String, // "file", "dir", "symlink", "tree", "blob"
    val size: Long = 0L,
    val sha: String = "",
    val htmlUrl: String = "",
)

@Serializable
data class GitHubFileContent(
    val path: String,
    val sha: String,
    val size: Long,
    val content: String,
    val truncated: Boolean = false,
    val htmlUrl: String = "",
)

@Serializable
data class GitHubIssueSummary(
    val number: Int,
    val title: String,
    val state: String,
    val author: String = "",
    val createdAt: String = "",
    val htmlUrl: String = "",
    val isPullRequest: Boolean = false,
)

@Serializable
data class GitHubPullRequestSummary(
    val number: Int,
    val title: String,
    val state: String,
    val headBranch: String = "",
    val baseBranch: String = "",
    val author: String = "",
    val htmlUrl: String = "",
)

data class GitHubIntegrationSettings(
    val isConnected: Boolean = false,
    val username: String = "",
    val avatarUrl: String = "",
    val scopes: String = "",
    val toolsEnabled: Boolean = true,
    val defaultRepoFullName: String = "",
    val confirmWritesBeforeExecute: Boolean = true,
)

data class GitHubPendingAction(
    val callId: String,
    val toolName: String,
    val title: String,
    val summary: String,
    val previewContent: String = "",
    val argumentsJson: String = "",
)

data class GitHubPendingApproval(
    val chatId: String,
    val repoFullName: String,
    val actions: List<GitHubPendingAction>,
)
