package com.example.data.github

import com.example.core.error.SecretRedactor
import com.example.core.model.GitHubBranchInfo
import com.example.core.model.GitHubContentEntry
import com.example.core.model.GitHubFileContent
import com.example.core.model.GitHubIssueSummary
import com.example.core.model.GitHubPullRequestSummary
import com.example.core.model.GitHubRepoSummary
import com.example.core.model.GitHubUserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

class GitHubApiException(
    val statusCode: Int?,
    override val message: String,
) : Exception(message)

@Singleton
class GitHubApiClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val baseApiUrl = "https://api.github.com".toHttpUrl()

    suspend fun verifyToken(token: String): Result<GitHubUserInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanToken = token.trim()
            require(cleanToken.isNotEmpty()) { "GitHub token is empty" }
            val url = baseApiUrl.newBuilder().addPathSegment("user").build()
            val req = baseRequest(url, cleanToken).get().build()
            okHttpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw GitHubApiException(resp.code, parseErrorMessage(resp.code, raw, cleanToken))
                }
                val obj = json.parseToJsonElement(raw).jsonObject
                GitHubUserInfo(
                    login = obj["login"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    avatarUrl = obj["avatar_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    scopes = resp.header("X-OAuth-Scopes").orEmpty().ifBlank { "fine-grained" },
                )
            }
        }
    }

    suspend fun listUserRepos(
        token: String,
        perPage: Int = 60,
        page: Int = 1,
    ): Result<List<GitHubRepoSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = baseApiUrl.newBuilder()
                .addPathSegments("user/repos")
                .addQueryParameter("sort", "updated")
                .addQueryParameter("direction", "desc")
                .addQueryParameter("per_page", perPage.coerceIn(1, 100).toString())
                .addQueryParameter("page", page.coerceAtLeast(1).toString())
                .addQueryParameter("affiliation", "owner,collaborator,organization_member")
                .build()
            val raw = executeGet(url, token)
            val arr = json.parseToJsonElement(raw).jsonArray
            arr.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                parseRepoSummary(obj)
            }
        }
    }

    suspend fun getRepo(token: String, owner: String, repo: String): Result<GitHubRepoSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanOwner = GitHubValidator.requireOwner(owner)
                val cleanRepo = GitHubValidator.requireRepo(repo)
                val url = baseApiUrl.newBuilder()
                    .addPathSegment("repos")
                    .addPathSegment(cleanOwner)
                    .addPathSegment(cleanRepo)
                    .build()
                val raw = executeGet(url, token)
                parseRepoSummary(json.parseToJsonElement(raw).jsonObject)
                    ?: throw GitHubApiException(null, "Malformed repository response")
            }
        }

    suspend fun createRepo(
        token: String,
        name: String,
        description: String = "",
        isPrivate: Boolean = true,
        autoInit: Boolean = true,
    ): Result<GitHubRepoSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanRepo = GitHubValidator.requireRepo(name)
            val url = baseApiUrl.newBuilder().addPathSegments("user/repos").build()
            val payload = buildJsonObject {
                put("name", cleanRepo)
                if (description.isNotBlank()) put("description", description.trim())
                put("private", isPrivate)
                put("auto_init", autoInit)
            }.toString()
            val raw = executeWrite("POST", url, token, payload)
            parseRepoSummary(json.parseToJsonElement(raw).jsonObject)
                ?: throw GitHubApiException(null, "Malformed created repository response")
        }
    }

    suspend fun listBranches(token: String, owner: String, repo: String): Result<List<GitHubBranchInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanOwner = GitHubValidator.requireOwner(owner)
                val cleanRepo = GitHubValidator.requireRepo(repo)
                val url = baseApiUrl.newBuilder()
                    .addPathSegment("repos")
                    .addPathSegment(cleanOwner)
                    .addPathSegment(cleanRepo)
                    .addPathSegment("branches")
                    .addQueryParameter("per_page", "60")
                    .build()
                val raw = executeGet(url, token)
                val arr = json.parseToJsonElement(raw).jsonArray
                arr.mapNotNull { elem ->
                    val obj = elem as? JsonObject ?: return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val protected = obj["protected"]?.jsonPrimitive?.booleanOrNull ?: false
                    val sha = obj["commit"]?.jsonObject?.get("sha")?.jsonPrimitive?.contentOrNull.orEmpty()
                    GitHubBranchInfo(name = name, isProtected = protected, sha = sha)
                }
            }
        }

    suspend fun createBranch(
        token: String,
        owner: String,
        repo: String,
        newBranch: String,
        fromBranch: String,
    ): Result<GitHubBranchInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanOwner = GitHubValidator.requireOwner(owner)
            val cleanRepo = GitHubValidator.requireRepo(repo)
            val cleanNew = GitHubValidator.requireBranchOrRef(newBranch)
            val cleanFrom = GitHubValidator.requireBranchOrRef(fromBranch)

            val refUrlBuilder = baseApiUrl.newBuilder()
                .addPathSegment("repos")
                .addPathSegment(cleanOwner)
                .addPathSegment(cleanRepo)
                .addPathSegments("git/ref/heads")
            cleanFrom.split('/').forEach { refUrlBuilder.addPathSegment(it) }
            val fromRaw = executeGet(refUrlBuilder.build(), token)
            val baseSha = json.parseToJsonElement(fromRaw).jsonObject["object"]
                ?.jsonObject?.get("sha")?.jsonPrimitive?.contentOrNull
                ?: throw GitHubApiException(null, "Could not resolve SHA for branch '$cleanFrom'")

            val createUrl = baseApiUrl.newBuilder()
                .addPathSegment("repos")
                .addPathSegment(cleanOwner)
                .addPathSegment(cleanRepo)
                .addPathSegments("git/refs")
                .build()
            val payload = buildJsonObject {
                put("ref", "refs/heads/$cleanNew")
                put("sha", baseSha)
            }.toString()
            executeWrite("POST", createUrl, token, payload)
            GitHubBranchInfo(name = cleanNew, isProtected = false, sha = baseSha)
        }
    }

    suspend fun listDirectory(
        token: String,
        owner: String,
        repo: String,
        path: String = "",
        ref: String? = null,
    ): Result<List<GitHubContentEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanOwner = GitHubValidator.requireOwner(owner)
            val cleanRepo = GitHubValidator.requireRepo(repo)
            val cleanPath = GitHubValidator.normalizePath(path, allowEmpty = true)
            val cleanRef = ref?.takeIf { it.isNotBlank() }?.let { GitHubValidator.requireBranchOrRef(it) }

            val urlBuilder = contentsUrlBuilder(cleanOwner, cleanRepo, cleanPath)
            if (cleanRef != null) {
                urlBuilder.addQueryParameter("ref", cleanRef)
            }
            val raw = executeGet(urlBuilder.build(), token)
            val parsed = json.parseToJsonElement(raw)
            val items = when (parsed) {
                is JsonArray -> parsed
                is JsonObject -> buildJsonArray { add(parsed) }
                else -> JsonArray(emptyList())
            }
            items.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val entryName = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val entryPath = obj["path"]?.jsonPrimitive?.contentOrNull ?: entryName
                val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "file"
                val size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
                val sha = obj["sha"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                GitHubContentEntry(entryName, entryPath, type, size, sha, htmlUrl)
            }.sortedWith(compareBy<GitHubContentEntry> { it.type != "dir" }.thenBy { it.name.lowercase() })
        }
    }

    suspend fun getRepoTree(
        token: String,
        owner: String,
        repo: String,
        ref: String,
        pathPrefix: String = "",
        maxEntries: Int = 200,
    ): Result<List<GitHubContentEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanOwner = GitHubValidator.requireOwner(owner)
            val cleanRepo = GitHubValidator.requireRepo(repo)
            val cleanRef = GitHubValidator.requireBranchOrRef(ref)
            val prefix = GitHubValidator.normalizePath(pathPrefix, allowEmpty = true)

            val url = baseApiUrl.newBuilder()
                .addPathSegment("repos")
                .addPathSegment(cleanOwner)
                .addPathSegment(cleanRepo)
                .addPathSegments("git/trees")
                .addPathSegment(cleanRef)
                .addQueryParameter("recursive", "1")
                .build()
            val raw = executeGet(url, token)
            val obj = json.parseToJsonElement(raw).jsonObject
            val tree = obj["tree"]?.jsonArray ?: JsonArray(emptyList())
            tree.mapNotNull { elem ->
                val item = elem as? JsonObject ?: return@mapNotNull null
                val entryPath = item["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (prefix.isNotEmpty() && entryPath != prefix && !entryPath.startsWith("$prefix/")) {
                    return@mapNotNull null
                }
                val type = item["type"]?.jsonPrimitive?.contentOrNull ?: "blob"
                val size = item["size"]?.jsonPrimitive?.longOrNull ?: 0L
                val sha = item["sha"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val entryName = entryPath.substringAfterLast('/')
                GitHubContentEntry(
                    name = entryName,
                    path = entryPath,
                    type = if (type == "tree") "dir" else "file",
                    size = size,
                    sha = sha,
                )
            }.take(maxEntries.coerceIn(1, 500))
        }
    }

    suspend fun readFile(
        token: String,
        owner: String,
        repo: String,
        path: String,
        ref: String? = null,
        maxChars: Int = 60_000,
    ): Result<GitHubFileContent> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanOwner = GitHubValidator.requireOwner(owner)
            val cleanRepo = GitHubValidator.requireRepo(repo)
            val cleanPath = GitHubValidator.normalizePath(path, allowEmpty = false)
            val cleanRef = ref?.takeIf { it.isNotBlank() }?.let { GitHubValidator.requireBranchOrRef(it) }

            val urlBuilder = contentsUrlBuilder(cleanOwner, cleanRepo, cleanPath)
            if (cleanRef != null) {
                urlBuilder.addQueryParameter("ref", cleanRef)
            }
            val raw = executeGet(urlBuilder.build(), token)
            val element = json.parseToJsonElement(raw)
            if (element is JsonArray) {
                throw GitHubApiException(null, "Path '$cleanPath' is a directory, not a file")
            }
            val obj = element.jsonObject
            val sha = obj["sha"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
            val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val encoding = obj["encoding"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val rawContent = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val decoded = when {
                size > 1_000_000L && rawContent.isBlank() -> {
                    "[File is ${size} bytes (>1MB) and requires raw blob fetch; too large for inline context]"
                }
                encoding.equals("base64", ignoreCase = true) -> {
                    runCatching { GitHubValidator.decodeBase64Utf8(rawContent) }
                        .getOrElse { "[Unable to decode file as UTF-8 text]" }
                }
                else -> rawContent
            }
            val truncated = decoded.length > maxChars
            val finalContent = if (truncated) decoded.take(maxChars) + "\n...[truncated]" else decoded
            GitHubFileContent(
                path = cleanPath,
                sha = sha,
                size = size,
                content = finalContent,
                truncated = truncated,
                htmlUrl = htmlUrl,
            )
        }
    }

    suspend fun createOrUpdateFile(
        token: String,
        owner: String,
        repo: String,
        path: String,
        content: String,
        commitMessage: String,
        branch: String,
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanOwner = GitHubValidator.requireOwner(owner)
            val cleanRepo = GitHubValidator.requireRepo(repo)
            val cleanPath = GitHubValidator.normalizePath(path, allowEmpty = false)
            val cleanBranch = GitHubValidator.requireBranchOrRef(branch)
            val cleanMessage = commitMessage.trim().ifBlank { "Update $cleanPath via AI Chat" }

            val existingSha = fetchExistingFileSha(token, cleanOwner, cleanRepo, cleanPath, cleanBranch)
            val url = contentsUrlBuilder(cleanOwner, cleanRepo, cleanPath).build()
            val payload = buildJsonObject {
                put("message", cleanMessage)
                put("content", GitHubValidator.encodeBase64Utf8(content))
                put("branch", cleanBranch)
                if (!existingSha.isNullOrBlank()) {
                    put("sha", existingSha)
                }
            }.toString()
            val raw = executeWrite("PUT", url, token, payload)
            val obj = json.parseToJsonElement(raw).jsonObject
            val commitSha = obj["commit"]?.jsonObject?.get("sha")?.jsonPrimitive?.contentOrNull.orEmpty()
            val htmlUrl = obj["commit"]?.jsonObject?.get("html_url")?.jsonPrimitive?.contentOrNull
                ?: obj["content"]?.jsonObject?.get("html_url")?.jsonPrimitive?.contentOrNull
                ?: ""
            commitSha to htmlUrl
        }
    }

    suspend fun deleteFile(
        token: String,
        owner: String,
        repo: String,
        path: String,
        commitMessage: String,
        branch: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanOwner = GitHubValidator.requireOwner(owner)
            val cleanRepo = GitHubValidator.requireRepo(repo)
            val cleanPath = GitHubValidator.normalizePath(path, allowEmpty = false)
            val cleanBranch = GitHubValidator.requireBranchOrRef(branch)
            val cleanMessage = commitMessage.trim().ifBlank { "Delete $cleanPath via AI Chat" }

            val existingSha = fetchExistingFileSha(token, cleanOwner, cleanRepo, cleanPath, cleanBranch)
                ?: throw GitHubApiException(404, "File '$cleanPath' not found on branch '$cleanBranch'")

            val url = contentsUrlBuilder(cleanOwner, cleanRepo, cleanPath).build()
            val payload = buildJsonObject {
                put("message", cleanMessage)
                put("sha", existingSha)
                put("branch", cleanBranch)
            }.toString()
            val raw = executeWrite("DELETE", url, token, payload)
            val obj = json.parseToJsonElement(raw).jsonObject
            obj["commit"]?.jsonObject?.get("sha")?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    suspend fun searchCode(
        token: String,
        owner: String,
        repo: String,
        query: String,
    ): Result<List<GitHubContentEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanOwner = GitHubValidator.requireOwner(owner)
            val cleanRepo = GitHubValidator.requireRepo(repo)
            val cleanQuery = query.trim()
            require(cleanQuery.isNotEmpty()) { "Search query cannot be empty" }

            val url = baseApiUrl.newBuilder()
                .addPathSegments("search/code")
                .addQueryParameter("q", "$cleanQuery repo:$cleanOwner/$cleanRepo")
                .addQueryParameter("per_page", "20")
                .build()
            val raw = executeGet(url, token)
            val items = json.parseToJsonElement(raw).jsonObject["items"]?.jsonArray ?: JsonArray(emptyList())
            items.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: name
                val sha = obj["sha"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                GitHubContentEntry(name = name, path = path, type = "file", sha = sha, htmlUrl = htmlUrl)
            }
        }
    }

    suspend fun listIssues(
        token: String,
        owner: String,
        repo: String,
        state: String = "open",
    ): Result<List<GitHubIssueSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanOwner = GitHubValidator.requireOwner(owner)
            val cleanRepo = GitHubValidator.requireRepo(repo)
            val cleanState = state.lowercase().takeIf { it in setOf("open", "closed", "all") } ?: "open"
            val url = baseApiUrl.newBuilder()
                .addPathSegment("repos")
                .addPathSegment(cleanOwner)
                .addPathSegment(cleanRepo)
                .addPathSegment("issues")
                .addQueryParameter("state", cleanState)
                .addQueryParameter("per_page", "30")
                .build()
            val raw = executeGet(url, token)
            json.parseToJsonElement(raw).jsonArray.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val number = obj["number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val st = obj["state"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val author = obj["user"]?.jsonObject?.get("login")?.jsonPrimitive?.contentOrNull.orEmpty()
                val created = obj["created_at"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val isPr = obj.containsKey("pull_request")
                GitHubIssueSummary(number, title, st, author, created, htmlUrl, isPr)
            }
        }
    }

    suspend fun createIssue(
        token: String,
        owner: String,
        repo: String,
        title: String,
        body: String = "",
        labels: List<String> = emptyList(),
    ): Result<GitHubIssueSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanOwner = GitHubValidator.requireOwner(owner)
            val cleanRepo = GitHubValidator.requireRepo(repo)
            require(title.isNotBlank()) { "Issue title cannot be empty" }
            val url = baseApiUrl.newBuilder()
                .addPathSegment("repos")
                .addPathSegment(cleanOwner)
                .addPathSegment(cleanRepo)
                .addPathSegment("issues")
                .build()
            val payload = buildJsonObject {
                put("title", title.trim())
                if (body.isNotBlank()) put("body", body)
                if (labels.isNotEmpty()) {
                    put("labels", buildJsonArray { labels.filter { it.isNotBlank() }.forEach { add(it.trim()) } })
                }
            }.toString()
            val raw = executeWrite("POST", url, token, payload)
            val obj = json.parseToJsonElement(raw).jsonObject
            GitHubIssueSummary(
                number = obj["number"]?.jsonPrimitive?.intOrNull ?: 0,
                title = obj["title"]?.jsonPrimitive?.contentOrNull ?: title,
                state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "open",
                author = obj["user"]?.jsonObject?.get("login")?.jsonPrimitive?.contentOrNull.orEmpty(),
                createdAt = obj["created_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    suspend fun updateIssue(
        token: String,
        owner: String,
        repo: String,
        issueNumber: Int,
        state: String? = null,
        title: String? = null,
        body: String? = null,
    ): Result<GitHubIssueSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanOwner = GitHubValidator.requireOwner(owner)
            val cleanRepo = GitHubValidator.requireRepo(repo)
            require(issueNumber > 0) { "Invalid issue number" }
            val url = baseApiUrl.newBuilder()
                .addPathSegment("repos")
                .addPathSegment(cleanOwner)
                .addPathSegment(cleanRepo)
                .addPathSegment("issues")
                .addPathSegment(issueNumber.toString())
                .build()
            val payload = buildJsonObject {
                state?.lowercase()?.takeIf { it in setOf("open", "closed") }?.let { put("state", it) }
                title?.takeIf { it.isNotBlank() }?.let { put("title", it.trim()) }
                body?.let { put("body", it) }
            }.toString()
            val raw = executeWrite("PATCH", url, token, payload)
            val obj = json.parseToJsonElement(raw).jsonObject
            GitHubIssueSummary(
                number = obj["number"]?.jsonPrimitive?.intOrNull ?: issueNumber,
                title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                state = obj["state"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    suspend fun addComment(
        token: String,
        owner: String,
        repo: String,
        issueOrPrNumber: Int,
        body: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanOwner = GitHubValidator.requireOwner(owner)
            val cleanRepo = GitHubValidator.requireRepo(repo)
            require(issueOrPrNumber > 0) { "Invalid issue/PR number" }
            require(body.isNotBlank()) { "Comment body cannot be empty" }
            val url = baseApiUrl.newBuilder()
                .addPathSegment("repos")
                .addPathSegment(cleanOwner)
                .addPathSegment(cleanRepo)
                .addPathSegment("issues")
                .addPathSegment(issueOrPrNumber.toString())
                .addPathSegment("comments")
                .build()
            val payload = buildJsonObject { put("body", body) }.toString()
            val raw = executeWrite("POST", url, token, payload)
            val obj = json.parseToJsonElement(raw).jsonObject
            obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    suspend fun listPullRequests(
        token: String,
        owner: String,
        repo: String,
        state: String = "open",
    ): Result<List<GitHubPullRequestSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanOwner = GitHubValidator.requireOwner(owner)
            val cleanRepo = GitHubValidator.requireRepo(repo)
            val cleanState = state.lowercase().takeIf { it in setOf("open", "closed", "all") } ?: "open"
            val url = baseApiUrl.newBuilder()
                .addPathSegment("repos")
                .addPathSegment(cleanOwner)
                .addPathSegment(cleanRepo)
                .addPathSegment("pulls")
                .addQueryParameter("state", cleanState)
                .addQueryParameter("per_page", "25")
                .build()
            val raw = executeGet(url, token)
            json.parseToJsonElement(raw).jsonArray.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val number = obj["number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val st = obj["state"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val head = obj["head"]?.jsonObject?.get("ref")?.jsonPrimitive?.contentOrNull.orEmpty()
                val base = obj["base"]?.jsonObject?.get("ref")?.jsonPrimitive?.contentOrNull.orEmpty()
                val author = obj["user"]?.jsonObject?.get("login")?.jsonPrimitive?.contentOrNull.orEmpty()
                val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                GitHubPullRequestSummary(number, title, st, head, base, author, htmlUrl)
            }
        }
    }

    suspend fun createPullRequest(
        token: String,
        owner: String,
        repo: String,
        title: String,
        body: String = "",
        head: String,
        base: String,
    ): Result<GitHubPullRequestSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanOwner = GitHubValidator.requireOwner(owner)
            val cleanRepo = GitHubValidator.requireRepo(repo)
            val cleanHead = GitHubValidator.requireBranchOrRef(head)
            val cleanBase = GitHubValidator.requireBranchOrRef(base)
            require(title.isNotBlank()) { "Pull Request title cannot be empty" }

            val url = baseApiUrl.newBuilder()
                .addPathSegment("repos")
                .addPathSegment(cleanOwner)
                .addPathSegment(cleanRepo)
                .addPathSegment("pulls")
                .build()
            val payload = buildJsonObject {
                put("title", title.trim())
                if (body.isNotBlank()) put("body", body)
                put("head", cleanHead)
                put("base", cleanBase)
            }.toString()
            val raw = executeWrite("POST", url, token, payload)
            val obj = json.parseToJsonElement(raw).jsonObject
            GitHubPullRequestSummary(
                number = obj["number"]?.jsonPrimitive?.intOrNull ?: 0,
                title = obj["title"]?.jsonPrimitive?.contentOrNull ?: title,
                state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "open",
                headBranch = cleanHead,
                baseBranch = cleanBase,
                author = obj["user"]?.jsonObject?.get("login")?.jsonPrimitive?.contentOrNull.orEmpty(),
                htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    suspend fun mergePullRequest(
        token: String,
        owner: String,
        repo: String,
        pullNumber: Int,
        commitTitle: String? = null,
        mergeMethod: String = "squash",
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanOwner = GitHubValidator.requireOwner(owner)
            val cleanRepo = GitHubValidator.requireRepo(repo)
            require(pullNumber > 0) { "Invalid PR number" }
            val method = mergeMethod.lowercase().takeIf { it in setOf("merge", "squash", "rebase") } ?: "squash"

            val url = baseApiUrl.newBuilder()
                .addPathSegment("repos")
                .addPathSegment(cleanOwner)
                .addPathSegment(cleanRepo)
                .addPathSegment("pulls")
                .addPathSegment(pullNumber.toString())
                .addPathSegment("merge")
                .build()
            val payload = buildJsonObject {
                put("merge_method", method)
                commitTitle?.takeIf { it.isNotBlank() }?.let { put("commit_title", it.trim()) }
            }.toString()
            val raw = executeWrite("PUT", url, token, payload)
            val obj = json.parseToJsonElement(raw).jsonObject
            obj["message"]?.jsonPrimitive?.contentOrNull ?: "Pull request #$pullNumber merged"
        }
    }

    private fun fetchExistingFileSha(
        token: String,
        owner: String,
        repo: String,
        path: String,
        branch: String,
    ): String? {
        val url = contentsUrlBuilder(owner, repo, path)
            .addQueryParameter("ref", branch)
            .build()
        val req = baseRequest(url, token).get().build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw GitHubApiException(resp.code, parseErrorMessage(resp.code, raw, token))
            }
            val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            return (parsed as? JsonObject)?.get("sha")?.jsonPrimitive?.contentOrNull
        }
    }

    private fun contentsUrlBuilder(owner: String, repo: String, path: String): HttpUrl.Builder {
        val builder = baseApiUrl.newBuilder()
            .addPathSegment("repos")
            .addPathSegment(owner)
            .addPathSegment(repo)
            .addPathSegment("contents")
        if (path.isNotBlank()) {
            path.split('/').forEach { segment -> builder.addPathSegment(segment) }
        }
        return builder
    }

    private fun executeGet(url: HttpUrl, token: String): String {
        val cleanToken = token.trim()
        require(cleanToken.isNotEmpty()) { "GitHub is not connected" }
        val req = baseRequest(url, cleanToken).get().build()
        okHttpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw GitHubApiException(resp.code, parseErrorMessage(resp.code, raw, cleanToken))
            }
            return raw
        }
    }

    private fun executeWrite(method: String, url: HttpUrl, token: String, payloadJson: String): String {
        val cleanToken = token.trim()
        require(cleanToken.isNotEmpty()) { "GitHub is not connected" }
        val reqBody = payloadJson.toRequestBody(jsonMediaType)
        val builder = baseRequest(url, cleanToken)
        val req = when (method) {
            "POST" -> builder.post(reqBody)
            "PUT" -> builder.put(reqBody)
            "PATCH" -> builder.patch(reqBody)
            "DELETE" -> builder.delete(reqBody)
            else -> throw IllegalArgumentException("Unsupported method $method")
        }.build()
        okHttpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw GitHubApiException(resp.code, parseErrorMessage(resp.code, raw, cleanToken))
            }
            return raw
        }
    }

    private fun baseRequest(url: HttpUrl, token: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("User-Agent", "AIChat-Android/1.0")
            .header("Authorization", "Bearer $token")

    private fun parseRepoSummary(obj: JsonObject): GitHubRepoSummary? {
        val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return null
        val fullName = obj["full_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: fullName.substringAfterLast('/')
        val owner = obj["owner"]?.jsonObject?.get("login")?.jsonPrimitive?.contentOrNull
            ?: fullName.substringBefore('/')
        return GitHubRepoSummary(
            id = id,
            fullName = fullName,
            owner = owner,
            name = name,
            description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            isPrivate = obj["private"]?.jsonPrimitive?.booleanOrNull ?: false,
            defaultBranch = obj["default_branch"]?.jsonPrimitive?.contentOrNull ?: "main",
            updatedAt = obj["updated_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    private fun parseErrorMessage(code: Int, rawBody: String, activeToken: String): String {
        val parsed = runCatching {
            val obj = json.parseToJsonElement(rawBody).jsonObject
            val msg = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val errs = obj["errors"]?.jsonArray?.mapNotNull { e ->
                (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            }?.joinToString("; ").orEmpty()
            listOf(msg, errs).filter { it.isNotBlank() }.joinToString(": ")
        }.getOrDefault("")
        val safeMsg = SecretRedactor.redact(parsed.ifBlank { rawBody.take(300) }, activeToken)
        return "GitHub HTTP $code: ${safeMsg.ifBlank { "Request failed" }}"
    }

    companion object {
        private const val API_VERSION = "2022-11-28"
    }
}
