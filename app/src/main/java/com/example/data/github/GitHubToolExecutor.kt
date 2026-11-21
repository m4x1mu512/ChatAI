package com.example.data.github

import com.example.core.model.GitHubIntegrationSettings
import com.example.core.model.GitHubPendingAction
import com.example.data.network.ToolCallRequest
import com.example.data.network.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ToolExecutionResult(
    val toolCallId: String,
    val toolName: String,
    val content: String,
    val isError: Boolean,
    val humanSummary: String,
)

@Singleton
class GitHubToolExecutor @Inject constructor(
    private val gitHubRepository: GitHubRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val toolDefinitions: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = TOOL_LIST_REPOS,
            description = "List GitHub repositories accessible to the connected user account.",
            parametersSchema = schema(
                properties = {
                    putJsonObject("page") {
                        put("type", "integer")
                        put("description", "Optional page number starting at 1")
                    }
                },
            ),
        ),
        ToolDefinition(
            name = TOOL_LIST_BRANCHES,
            description = "List branches of a GitHub repository.",
            parametersSchema = schema(
                properties = {
                    putJsonObject("owner") { put("type", "string"); put("description", "Repository owner (optional if active repo is set)") }
                    putJsonObject("repo") { put("type", "string"); put("description", "Repository name (optional if active repo is set)") }
                },
            ),
        ),
        ToolDefinition(
            name = TOOL_LIST_FILES,
            description = "List directory entries or recursive file tree in a GitHub repository.",
            parametersSchema = schema(
                properties = {
                    putJsonObject("owner") { put("type", "string"); put("description", "Repository owner (optional if active repo is set)") }
                    putJsonObject("repo") { put("type", "string"); put("description", "Repository name (optional if active repo is set)") }
                    putJsonObject("path") { put("type", "string"); put("description", "Directory path prefix (empty string for root)") }
                    putJsonObject("branch") { put("type", "string"); put("description", "Branch or ref name") }
                    putJsonObject("recursive") { put("type", "boolean"); put("description", "Set true to fetch recursive tree up to 200 entries") }
                },
            ),
        ),
        ToolDefinition(
            name = TOOL_READ_FILE,
            description = "Read the UTF-8 contents and SHA of a file in a GitHub repository.",
            parametersSchema = schema(
                required = listOf("path"),
                properties = {
                    putJsonObject("owner") { put("type", "string"); put("description", "Repository owner (optional if active repo is set)") }
                    putJsonObject("repo") { put("type", "string"); put("description", "Repository name (optional if active repo is set)") }
                    putJsonObject("path") { put("type", "string"); put("description", "File path inside the repository") }
                    putJsonObject("branch") { put("type", "string"); put("description", "Branch or ref name") }
                },
            ),
        ),
        ToolDefinition(
            name = TOOL_SEARCH_CODE,
            description = "Search for code or filenames inside a GitHub repository.",
            parametersSchema = schema(
                required = listOf("query"),
                properties = {
                    putJsonObject("owner") { put("type", "string") }
                    putJsonObject("repo") { put("type", "string") }
                    putJsonObject("query") { put("type", "string"); put("description", "Search query keywords or symbol") }
                },
            ),
        ),
        ToolDefinition(
            name = TOOL_LIST_ISSUES,
            description = "List issues in a GitHub repository.",
            parametersSchema = schema(
                properties = {
                    putJsonObject("owner") { put("type", "string") }
                    putJsonObject("repo") { put("type", "string") }
                    putJsonObject("state") { put("type", "string"); put("description", "open, closed, or all") }
                },
            ),
        ),
        ToolDefinition(
            name = TOOL_LIST_PRS,
            description = "List pull requests in a GitHub repository.",
            parametersSchema = schema(
                properties = {
                    putJsonObject("owner") { put("type", "string") }
                    putJsonObject("repo") { put("type", "string") }
                    putJsonObject("state") { put("type", "string"); put("description", "open, closed, or all") }
                },
            ),
        ),
        ToolDefinition(
            name = TOOL_CREATE_BRANCH,
            description = "Create a new Git branch in a GitHub repository from an existing branch.",
            parametersSchema = schema(
                required = listOf("new_branch"),
                properties = {
                    putJsonObject("owner") { put("type", "string") }
                    putJsonObject("repo") { put("type", "string") }
                    putJsonObject("new_branch") { put("type", "string"); put("description", "Name of the new branch to create") }
                    putJsonObject("from_branch") { put("type", "string"); put("description", "Source branch (defaults to active/default branch)") }
                },
            ),
        ),
        ToolDefinition(
            name = TOOL_CREATE_OR_UPDATE_FILE,
            description = "Create a new file or update an existing file in a GitHub repository via a commit.",
            parametersSchema = schema(
                required = listOf("path", "content", "commit_message"),
                properties = {
                    putJsonObject("owner") { put("type", "string") }
                    putJsonObject("repo") { put("type", "string") }
                    putJsonObject("path") { put("type", "string"); put("description", "Target file path") }
                    putJsonObject("content") { put("type", "string"); put("description", "Complete updated UTF-8 content of the file") }
                    putJsonObject("commit_message") { put("type", "string"); put("description", "Clear commit message") }
                    putJsonObject("branch") { put("type", "string"); put("description", "Target branch") }
                },
            ),
        ),
        ToolDefinition(
            name = TOOL_DELETE_FILE,
            description = "Delete a file from a GitHub repository branch.",
            parametersSchema = schema(
                required = listOf("path", "commit_message"),
                properties = {
                    putJsonObject("owner") { put("type", "string") }
                    putJsonObject("repo") { put("type", "string") }
                    putJsonObject("path") { put("type", "string") }
                    putJsonObject("commit_message") { put("type", "string") }
                    putJsonObject("branch") { put("type", "string") }
                },
            ),
        ),
        ToolDefinition(
            name = TOOL_CREATE_ISSUE,
            description = "Create a new issue in a GitHub repository.",
            parametersSchema = schema(
                required = listOf("title"),
                properties = {
                    putJsonObject("owner") { put("type", "string") }
                    putJsonObject("repo") { put("type", "string") }
                    putJsonObject("title") { put("type", "string") }
                    putJsonObject("body") { put("type", "string") }
                    putJsonObject("labels") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                    }
                },
            ),
        ),
        ToolDefinition(
            name = TOOL_COMMENT_ISSUE,
            description = "Add a comment to an issue or pull request.",
            parametersSchema = schema(
                required = listOf("number", "body"),
                properties = {
                    putJsonObject("owner") { put("type", "string") }
                    putJsonObject("repo") { put("type", "string") }
                    putJsonObject("number") { put("type", "integer"); put("description", "Issue or PR number") }
                    putJsonObject("body") { put("type", "string") }
                },
            ),
        ),
        ToolDefinition(
            name = TOOL_CREATE_PR,
            description = "Create a Pull Request from head branch into base branch.",
            parametersSchema = schema(
                required = listOf("title", "head"),
                properties = {
                    putJsonObject("owner") { put("type", "string") }
                    putJsonObject("repo") { put("type", "string") }
                    putJsonObject("title") { put("type", "string") }
                    putJsonObject("body") { put("type", "string") }
                    putJsonObject("head") { put("type", "string"); put("description", "Source branch with changes") }
                    putJsonObject("base") { put("type", "string"); put("description", "Target base branch") }
                },
            ),
        ),
        ToolDefinition(
            name = TOOL_MERGE_PR,
            description = "Merge an open Pull Request.",
            parametersSchema = schema(
                required = listOf("pull_number"),
                properties = {
                    putJsonObject("owner") { put("type", "string") }
                    putJsonObject("repo") { put("type", "string") }
                    putJsonObject("pull_number") { put("type", "integer") }
                    putJsonObject("commit_title") { put("type", "string") }
                    putJsonObject("merge_method") { put("type", "string"); put("description", "squash, merge, or rebase") }
                },
            ),
        ),
        ToolDefinition(
            name = TOOL_CREATE_REPO,
            description = "Create a new GitHub repository under the authenticated user's account.",
            parametersSchema = schema(
                required = listOf("name"),
                properties = {
                    putJsonObject("name") { put("type", "string") }
                    putJsonObject("description") { put("type", "string") }
                    putJsonObject("private") { put("type", "boolean") }
                },
            ),
        ),
    )

    fun isWriteTool(toolName: String): Boolean = toolName in WRITE_TOOLS

    fun buildSystemPrompt(
        settings: GitHubIntegrationSettings,
        activeRepoFullName: String?,
        activeBranch: String?,
        includeTextFallbackInstructions: Boolean = false,
    ): String {
        val repoDisplay = activeRepoFullName?.takeIf { it.isNotBlank() } ?: "Not pinned (use $TOOL_LIST_REPOS or pass owner & repo)"
        val branchDisplay = activeBranch?.takeIf { it.isNotBlank() } ?: "repository default branch"
        return buildString {
            appendLine("You have live access to the user's connected GitHub account via GitHub tools.")
            appendLine("Connected GitHub user: @${settings.username.ifBlank { "authenticated" }}")
            appendLine("Active repository for this chat: $repoDisplay")
            appendLine("Active branch for this chat: $branchDisplay")
            appendLine("Guidelines:")
            appendLine("- Always inspect files or directory structure before modifying existing files so you do not overwrite unrelated code.")
            appendLine("- When editing a file with $TOOL_CREATE_OR_UPDATE_FILE, pass the complete updated file content.")
            appendLine("- Prefer creating a feature branch and Pull Request for larger changes when appropriate.")
            appendLine("- After completing repository actions, summarize the commit SHAs, file paths, or issue/PR URLs clearly.")
            if (includeTextFallbackInstructions) {
                appendLine()
                appendLine("If native function calling is unavailable, you may invoke a tool by outputting ONLY blocks in this exact format:")
                appendLine("<github_tool>{\"name\": \"TOOL_NAME\", \"arguments\": {...}}</github_tool>")
                appendLine("Available tools: ${toolDefinitions.joinToString(", ") { it.name }}")
            }
        }
    }

    fun describePendingAction(
        call: ToolCallRequest,
        defaultRepoFullName: String?,
        defaultBranch: String?,
    ): GitHubPendingAction {
        val args = parseArgs(call.argumentsJson)
        val repoLabel = resolveRepoLabel(args, defaultRepoFullName)
        val branch = str(args, "branch") ?: str(args, "head") ?: defaultBranch ?: "default"

        return when (call.name) {
            TOOL_CREATE_OR_UPDATE_FILE -> {
                val path = str(args, "path").orEmpty()
                val msg = str(args, "commit_message").orEmpty()
                val content = str(args, "content").orEmpty()
                GitHubPendingAction(
                    callId = call.id,
                    toolName = call.name,
                    title = "Commit file: $path",
                    summary = "$repoLabel ($branch) • $msg",
                    previewContent = content.take(1200),
                    argumentsJson = call.argumentsJson,
                )
            }
            TOOL_DELETE_FILE -> {
                val path = str(args, "path").orEmpty()
                val msg = str(args, "commit_message").orEmpty()
                GitHubPendingAction(
                    callId = call.id,
                    toolName = call.name,
                    title = "Delete file: $path",
                    summary = "$repoLabel ($branch) • $msg",
                    argumentsJson = call.argumentsJson,
                )
            }
            TOOL_CREATE_BRANCH -> {
                val newBr = str(args, "new_branch").orEmpty()
                val fromBr = str(args, "from_branch") ?: defaultBranch ?: "default"
                GitHubPendingAction(
                    callId = call.id,
                    toolName = call.name,
                    title = "Create branch: $newBr",
                    summary = "$repoLabel • from $fromBr",
                    argumentsJson = call.argumentsJson,
                )
            }
            TOOL_CREATE_ISSUE -> {
                val title = str(args, "title").orEmpty()
                val body = str(args, "body").orEmpty()
                GitHubPendingAction(
                    callId = call.id,
                    toolName = call.name,
                    title = "Create Issue: $title",
                    summary = repoLabel,
                    previewContent = body.take(800),
                    argumentsJson = call.argumentsJson,
                )
            }
            TOOL_COMMENT_ISSUE -> {
                val num = int(args, "number") ?: 0
                val body = str(args, "body").orEmpty()
                GitHubPendingAction(
                    callId = call.id,
                    toolName = call.name,
                    title = "Comment on #$num",
                    summary = repoLabel,
                    previewContent = body.take(800),
                    argumentsJson = call.argumentsJson,
                )
            }
            TOOL_CREATE_PR -> {
                val title = str(args, "title").orEmpty()
                val head = str(args, "head").orEmpty()
                val base = str(args, "base") ?: defaultBranch ?: "main"
                GitHubPendingAction(
                    callId = call.id,
                    toolName = call.name,
                    title = "Create PR: $title",
                    summary = "$repoLabel • $head → $base",
                    previewContent = str(args, "body").orEmpty().take(800),
                    argumentsJson = call.argumentsJson,
                )
            }
            TOOL_MERGE_PR -> {
                val num = int(args, "pull_number") ?: 0
                val method = str(args, "merge_method") ?: "squash"
                GitHubPendingAction(
                    callId = call.id,
                    toolName = call.name,
                    title = "Merge PR #$num ($method)",
                    summary = repoLabel,
                    argumentsJson = call.argumentsJson,
                )
            }
            TOOL_CREATE_REPO -> {
                val name = str(args, "name").orEmpty()
                val priv = bool(args, "private") ?: true
                GitHubPendingAction(
                    callId = call.id,
                    toolName = call.name,
                    title = "Create repository: $name",
                    summary = if (priv) "Private" else "Public",
                    previewContent = str(args, "description").orEmpty(),
                    argumentsJson = call.argumentsJson,
                )
            }
            else -> GitHubPendingAction(
                callId = call.id,
                toolName = call.name,
                title = call.name,
                summary = repoLabel,
                argumentsJson = call.argumentsJson,
            )
        }
    }

    suspend fun execute(
        call: ToolCallRequest,
        defaultRepoFullName: String?,
        defaultBranch: String?,
    ): ToolExecutionResult {
        val args = parseArgs(call.argumentsJson)
        return try {
            when (call.name) {
                TOOL_LIST_REPOS -> {
                    val page = int(args, "page") ?: 1
                    val repos = gitHubRepository.listRepos(page).getOrThrow()
                    val payload = buildJsonObject {
                        put("count", repos.size)
                        put("repositories", buildJsonArray {
                            repos.forEach { r ->
                                add(
                                    buildJsonObject {
                                        put("full_name", r.fullName)
                                        put("private", r.isPrivate)
                                        put("default_branch", r.defaultBranch)
                                        put("description", r.description)
                                        put("url", r.htmlUrl)
                                    },
                                )
                            }
                        })
                    }.toString()
                    ToolExecutionResult(call.id, call.name, payload, false, "Listed ${repos.size} repositories")
                }

                TOOL_LIST_BRANCHES -> {
                    val (owner, repo) = resolveOwnerRepo(args, defaultRepoFullName)
                    val branches = gitHubRepository.listBranches(owner, repo).getOrThrow()
                    val payload = buildJsonObject {
                        put("repo", "$owner/$repo")
                        put("branches", buildJsonArray {
                            branches.forEach { b ->
                                add(buildJsonObject {
                                    put("name", b.name)
                                    put("protected", b.isProtected)
                                    put("sha", b.sha)
                                })
                            }
                        })
                    }.toString()
                    ToolExecutionResult(call.id, call.name, payload, false, "Listed ${branches.size} branches in $owner/$repo")
                }

                TOOL_LIST_FILES -> {
                    val (owner, repo) = resolveOwnerRepo(args, defaultRepoFullName)
                    val path = str(args, "path").orEmpty()
                    val branch = resolveBranch(owner, repo, str(args, "branch") ?: defaultBranch)
                    val recursive = bool(args, "recursive") ?: false
                    val entries = if (recursive) {
                        gitHubRepository.getRepoTree(owner, repo, branch, path).getOrThrow()
                    } else {
                        gitHubRepository.listDirectory(owner, repo, path, branch).getOrThrow()
                    }
                    val payload = buildJsonObject {
                        put("repo", "$owner/$repo")
                        put("branch", branch)
                        put("path", path)
                        put("entries", buildJsonArray {
                            entries.forEach { e ->
                                add(buildJsonObject {
                                    put("path", e.path)
                                    put("type", e.type)
                                    put("size", e.size)
                                })
                            }
                        })
                    }.toString()
                    ToolExecutionResult(call.id, call.name, payload, false, "Listed ${entries.size} files in $owner/$repo/$path")
                }

                TOOL_READ_FILE -> {
                    val (owner, repo) = resolveOwnerRepo(args, defaultRepoFullName)
                    val path = str(args, "path") ?: error("Missing required 'path'")
                    val branch = resolveBranch(owner, repo, str(args, "branch") ?: defaultBranch)
                    val file = gitHubRepository.readFile(owner, repo, path, branch).getOrThrow()
                    val payload = buildJsonObject {
                        put("repo", "$owner/$repo")
                        put("branch", branch)
                        put("path", file.path)
                        put("sha", file.sha)
                        put("size", file.size)
                        put("truncated", file.truncated)
                        put("content", file.content)
                    }.toString()
                    ToolExecutionResult(call.id, call.name, payload, false, "Read ${file.path} (${file.size} B)")
                }

                TOOL_SEARCH_CODE -> {
                    val (owner, repo) = resolveOwnerRepo(args, defaultRepoFullName)
                    val query = str(args, "query") ?: error("Missing required 'query'")
                    val matches = gitHubRepository.searchCode(owner, repo, query).getOrThrow()
                    val payload = buildJsonObject {
                        put("repo", "$owner/$repo")
                        put("query", query)
                        put("matches", buildJsonArray {
                            matches.forEach { m ->
                                add(buildJsonObject {
                                    put("path", m.path)
                                    put("url", m.htmlUrl)
                                })
                            }
                        })
                    }.toString()
                    ToolExecutionResult(call.id, call.name, payload, false, "Found ${matches.size} matches for '$query'")
                }

                TOOL_LIST_ISSUES -> {
                    val (owner, repo) = resolveOwnerRepo(args, defaultRepoFullName)
                    val state = str(args, "state") ?: "open"
                    val issues = gitHubRepository.listIssues(owner, repo, state).getOrThrow()
                        .filter { !it.isPullRequest }
                    val payload = buildJsonObject {
                        put("repo", "$owner/$repo")
                        put("issues", buildJsonArray {
                            issues.forEach { i ->
                                add(buildJsonObject {
                                    put("number", i.number)
                                    put("title", i.title)
                                    put("state", i.state)
                                    put("author", i.author)
                                    put("url", i.htmlUrl)
                                })
                            }
                        })
                    }.toString()
                    ToolExecutionResult(call.id, call.name, payload, false, "Listed ${issues.size} issues in $owner/$repo")
                }

                TOOL_LIST_PRS -> {
                    val (owner, repo) = resolveOwnerRepo(args, defaultRepoFullName)
                    val state = str(args, "state") ?: "open"
                    val prs = gitHubRepository.listPullRequests(owner = owner, repo = repo, state = state).getOrThrow()
                    val payload = buildJsonObject {
                        put("repo", "$owner/$repo")
                        put("pull_requests", buildJsonArray {
                            prs.forEach { pr ->
                                add(buildJsonObject {
                                    put("number", pr.number)
                                    put("title", pr.title)
                                    put("state", pr.state)
                                    put("head", pr.headBranch)
                                    put("base", pr.baseBranch)
                                    put("url", pr.htmlUrl)
                                })
                            }
                        })
                    }.toString()
                    ToolExecutionResult(call.id, call.name, payload, false, "Listed ${prs.size} PRs in $owner/$repo")
                }

                TOOL_CREATE_BRANCH -> {
                    val (owner, repo) = resolveOwnerRepo(args, defaultRepoFullName)
                    val newBranch = str(args, "new_branch") ?: error("Missing 'new_branch'")
                    val fromBranch = resolveBranch(owner, repo, str(args, "from_branch") ?: defaultBranch)
                    val created = gitHubRepository.createBranch(owner, repo, newBranch, fromBranch).getOrThrow()
                    val payload = buildJsonObject {
                        put("status", "created")
                        put("branch", created.name)
                        put("sha", created.sha)
                    }.toString()
                    ToolExecutionResult(call.id, call.name, payload, false, "Created branch ${created.name} from $fromBranch")
                }

                TOOL_CREATE_OR_UPDATE_FILE -> {
                    val (owner, repo) = resolveOwnerRepo(args, defaultRepoFullName)
                    val path = str(args, "path") ?: error("Missing 'path'")
                    val content = str(args, "content") ?: ""
                    val msg = str(args, "commit_message") ?: "Update $path"
                    val branch = resolveBranch(owner, repo, str(args, "branch") ?: defaultBranch)
                    val (sha, url) = gitHubRepository.createOrUpdateFile(owner, repo, path, content, msg, branch).getOrThrow()
                    val payload = buildJsonObject {
                        put("status", "committed")
                        put("repo", "$owner/$repo")
                        put("branch", branch)
                        put("path", path)
                        put("commit_sha", sha)
                        put("url", url)
                    }.toString()
                    ToolExecutionResult(call.id, call.name, payload, false, "Committed $path on $branch (${sha.take(7)})")
                }

                TOOL_DELETE_FILE -> {
                    val (owner, repo) = resolveOwnerRepo(args, defaultRepoFullName)
                    val path = str(args, "path") ?: error("Missing 'path'")
                    val msg = str(args, "commit_message") ?: "Delete $path"
                    val branch = resolveBranch(owner, repo, str(args, "branch") ?: defaultBranch)
                    val sha = gitHubRepository.deleteFile(owner, repo, path, msg, branch).getOrThrow()
                    val payload = buildJsonObject {
                        put("status", "deleted")
                        put("path", path)
                        put("branch", branch)
                        put("commit_sha", sha)
                    }.toString()
                    ToolExecutionResult(call.id, call.name, payload, false, "Deleted $path on $branch")
                }

                TOOL_CREATE_ISSUE -> {
                    val (owner, repo) = resolveOwnerRepo(args, defaultRepoFullName)
                    val title = str(args, "title") ?: error("Missing 'title'")
                    val body = str(args, "body").orEmpty()
                    val labels = (args["labels"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    val issue = gitHubRepository.createIssue(owner, repo, title, body, labels).getOrThrow()
                    val payload = buildJsonObject {
                        put("number", issue.number)
                        put("title", issue.title)
                        put("url", issue.htmlUrl)
                    }.toString()
                    ToolExecutionResult(call.id, call.name, payload, false, "Created issue #${issue.number}: ${issue.title}")
                }

                TOOL_COMMENT_ISSUE -> {
                    val (owner, repo) = resolveOwnerRepo(args, defaultRepoFullName)
                    val number = int(args, "number") ?: error("Missing 'number'")
                    val body = str(args, "body") ?: error("Missing 'body'")
                    val url = gitHubRepository.addComment(owner, repo, number, body).getOrThrow()
                    val payload = buildJsonObject { put("status", "commented"); put("url", url) }.toString()
                    ToolExecutionResult(call.id, call.name, payload, false, "Commented on #$number")
                }

                TOOL_CREATE_PR -> {
                    val (owner, repo) = resolveOwnerRepo(args, defaultRepoFullName)
                    val title = str(args, "title") ?: error("Missing 'title'")
                    val body = str(args, "body").orEmpty()
                    val head = str(args, "head") ?: error("Missing 'head'")
                    val base = resolveBranch(owner, repo, str(args, "base") ?: defaultBranch)
                    val pr = gitHubRepository.createPullRequest(owner, repo, title, body, head, base).getOrThrow()
                    val payload = buildJsonObject {
                        put("number", pr.number)
                        put("title", pr.title)
                        put("head", pr.headBranch)
                        put("base", pr.baseBranch)
                        put("url", pr.htmlUrl)
                    }.toString()
                    ToolExecutionResult(call.id, call.name, payload, false, "Created PR #${pr.number} ($head → $base)")
                }

                TOOL_MERGE_PR -> {
                    val (owner, repo) = resolveOwnerRepo(args, defaultRepoFullName)
                    val pullNumber = int(args, "pull_number") ?: error("Missing 'pull_number'")
                    val title = str(args, "commit_title")
                    val method = str(args, "merge_method") ?: "squash"
                    val msg = gitHubRepository.mergePullRequest(owner, repo, pullNumber, title, method).getOrThrow()
                    val payload = buildJsonObject { put("status", "merged"); put("message", msg) }.toString()
                    ToolExecutionResult(call.id, call.name, payload, false, "Merged PR #$pullNumber")
                }

                TOOL_CREATE_REPO -> {
                    val name = str(args, "name") ?: error("Missing 'name'")
                    val desc = str(args, "description").orEmpty()
                    val priv = bool(args, "private") ?: true
                    val repo = gitHubRepository.createRepo(name, desc, priv, autoInit = true).getOrThrow()
                    val payload = buildJsonObject {
                        put("full_name", repo.fullName)
                        put("private", repo.isPrivate)
                        put("default_branch", repo.defaultBranch)
                        put("url", repo.htmlUrl)
                    }.toString()
                    ToolExecutionResult(call.id, call.name, payload, false, "Created repository ${repo.fullName}")
                }

                else -> ToolExecutionResult(
                    toolCallId = call.id,
                    toolName = call.name,
                    content = """{"error":"Unknown tool: ${call.name}"}""",
                    isError = true,
                    humanSummary = "Unknown tool: ${call.name}",
                )
            }
        } catch (t: Throwable) {
            val errMsg = t.message.orEmpty().ifBlank { "Tool execution failed" }
            ToolExecutionResult(
                toolCallId = call.id,
                toolName = call.name,
                content = buildJsonObject { put("error", errMsg) }.toString(),
                isError = true,
                humanSummary = "Error in ${call.name}: $errMsg",
            )
        }
    }

    fun parseFallbackToolCalls(text: String): List<ToolCallRequest> {
        val matches = FALLBACK_TOOL_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapNotNull { match ->
            val raw = match.groupValues[1].trim()
            runCatching {
                val obj = json.parseToJsonElement(raw).jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (name.isEmpty()) return@mapNotNull null
                val argsObj = obj["arguments"]?.jsonObject ?: JsonObject(emptyMap())
                ToolCallRequest(
                    id = "fallback_${UUID.randomUUID().toString().take(8)}",
                    name = name,
                    argumentsJson = argsObj.toString(),
                )
            }.getOrNull()
        }
    }

    fun stripFallbackToolTags(text: String): String =
        FALLBACK_TOOL_REGEX.replace(text, "").trim()

    private suspend fun resolveBranch(owner: String, repo: String, explicit: String?): String {
        val clean = explicit?.trim()?.takeIf { it.isNotBlank() }
        if (clean != null) return clean
        return gitHubRepository.getRepo(owner, repo).getOrNull()?.defaultBranch?.ifBlank { "main" } ?: "main"
    }

    private fun resolveOwnerRepo(args: JsonObject, defaultRepoFullName: String?): Pair<String, String> {
        val explicitOwner = str(args, "owner")?.takeIf { it.isNotBlank() }
        val explicitRepo = str(args, "repo")?.takeIf { it.isNotBlank() }
        if (explicitOwner != null && explicitRepo != null) {
            return GitHubValidator.requireOwner(explicitOwner) to GitHubValidator.requireRepo(explicitRepo)
        }
        val combinedRepoArg = str(args, "repository")?.takeIf { it.contains('/') }
            ?: explicitRepo?.takeIf { it.contains('/') }
        if (combinedRepoArg != null) {
            return GitHubValidator.requireFullName(combinedRepoArg)
        }
        val fallback = defaultRepoFullName?.takeIf { it.isNotBlank() }
            ?: error("Specify owner and repo, or pin an active repository in the chat.")
        return GitHubValidator.requireFullName(fallback)
    }

    private fun resolveRepoLabel(args: JsonObject, defaultRepoFullName: String?): String {
        val o = str(args, "owner")?.takeIf { it.isNotBlank() }
        val r = str(args, "repo")?.takeIf { it.isNotBlank() }
        return if (o != null && r != null) "$o/$r" else defaultRepoFullName ?: "GitHub"
    }

    private fun parseArgs(raw: String): JsonObject =
        runCatching { json.parseToJsonElement(raw).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    private fun str(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull

    private fun int(obj: JsonObject, key: String): Int? =
        (obj[key] as? JsonPrimitive)?.intOrNull

    private fun bool(obj: JsonObject, key: String): Boolean? =
        (obj[key] as? JsonPrimitive)?.booleanOrNull

    private fun schema(
        required: List<String> = emptyList(),
        properties: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties", properties)
        if (required.isNotEmpty()) {
            put("required", buildJsonArray { required.forEach { add(it) } })
        }
    }

    companion object {
        const val TOOL_LIST_REPOS = "github_list_repos"
        const val TOOL_LIST_BRANCHES = "github_list_branches"
        const val TOOL_LIST_FILES = "github_list_files"
        const val TOOL_READ_FILE = "github_read_file"
        const val TOOL_SEARCH_CODE = "github_search_code"
        const val TOOL_LIST_ISSUES = "github_list_issues"
        const val TOOL_LIST_PRS = "github_list_pull_requests"

        const val TOOL_CREATE_BRANCH = "github_create_branch"
        const val TOOL_CREATE_OR_UPDATE_FILE = "github_create_or_update_file"
        const val TOOL_DELETE_FILE = "github_delete_file"
        const val TOOL_CREATE_ISSUE = "github_create_issue"
        const val TOOL_COMMENT_ISSUE = "github_comment_issue"
        const val TOOL_CREATE_PR = "github_create_pull_request"
        const val TOOL_MERGE_PR = "github_merge_pull_request"
        const val TOOL_CREATE_REPO = "github_create_repo"

        private val WRITE_TOOLS = setOf(
            TOOL_CREATE_BRANCH,
            TOOL_CREATE_OR_UPDATE_FILE,
            TOOL_DELETE_FILE,
            TOOL_CREATE_ISSUE,
            TOOL_COMMENT_ISSUE,
            TOOL_CREATE_PR,
            TOOL_MERGE_PR,
            TOOL_CREATE_REPO,
        )

        private val FALLBACK_TOOL_REGEX = Regex(
            """<github_tool>\s*(\{[\s\S]*?\})\s*</github_tool>""",
            setOf(RegexOption.IGNORE_CASE),
        )
    }
}
