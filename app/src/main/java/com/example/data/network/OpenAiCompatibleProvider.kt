package com.example.data.network

import com.example.core.error.AiErrorCategory
import com.example.core.error.AiException
import com.example.core.error.ErrorParser
import com.example.core.model.AiModelInfo
import com.example.core.model.ApiProtocol
import com.example.core.model.AttachmentType
import com.example.core.model.CapabilityInferencer
import com.example.core.model.ChatMessage
import com.example.core.model.GeneratedMedia
import com.example.core.model.GeneratedMediaType
import com.example.core.model.MessageRole
import com.example.core.model.ProviderConfig
import com.example.core.model.ProviderType
import com.example.core.util.AttachmentExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenAiCompatibleProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val attachmentExtractor: AttachmentExtractor,
    private val mediaCacheManager: MediaCacheManager,
) : AiProvider {

    override val protocol: ApiProtocol = ApiProtocol.OPENAI_COMPATIBLE
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun testConnection(config: ProviderConfig, apiKey: String): ConnectionCheckResult =
        withContext(Dispatchers.IO) {
            val base = normalizeBaseUrl(config.baseUrl)
            if (base.isBlank() || base.contains("YOUR_ACCOUNT_ID", ignoreCase = true)) {
                val msg = if (base.contains("YOUR_ACCOUNT_ID", ignoreCase = true)) {
                    "Replace YOUR_ACCOUNT_ID in Base URL with your Cloudflare Account ID"
                } else {
                    "Invalid Base URL"
                }
                return@withContext ConnectionCheckResult(
                    success = false,
                    resolvedProtocol = protocol,
                    message = msg,
                    error = ErrorParser.fromThrowable(
                        IllegalArgumentException(msg),
                        config.displayName,
                        endpointUrl = config.baseUrl,
                        activeApiKey = apiKey,
                    ).copy(category = AiErrorCategory.INVALID_BASE_URL),
                )
            }
            val modelsResult = fetchModels(config, apiKey)
            modelsResult.fold(
                onSuccess = { models ->
                    ConnectionCheckResult(
                        success = true,
                        resolvedProtocol = protocol,
                        message = "Connected (${models.size} models)",
                        modelsCount = models.size,
                        models = models,
                    )
                },
                onFailure = { throwable ->
                    val aiError = ErrorParser.fromThrowable(
                        throwable,
                        providerName = config.displayName,
                        endpointUrl = resolveModelsEndpointUrl(config, base),
                        activeApiKey = apiKey,
                    )
                    // Some endpoints (e.g. Perplexity, or Cloudflare tokens without Read scope) may fail /models -> probe chat
                    val shouldProbeChat = aiError.httpStatus == 404 ||
                        aiError.httpStatus == 405 ||
                        (config.type == ProviderType.CLOUDFLARE && aiError.httpStatus == 403)
                    if (shouldProbeChat) {
                        probeChatEndpoint(config, base, apiKey)
                    } else {
                        ConnectionCheckResult(
                            success = false,
                            resolvedProtocol = protocol,
                            message = aiError.providerMessage,
                            error = aiError,
                        )
                    }
                },
            )
        }

    private fun probeChatEndpoint(config: ProviderConfig, base: String, apiKey: String): ConnectionCheckResult {
        val chatBase = resolveOpenAiBaseUrl(config, base)
        val url = "$chatBase/chat/completions"
        val probeModel = normalizeModelIdForRequest(config, chatBase, config.selectedModelId ?: "probe")
        val payload = buildJsonObject {
            put("model", probeModel)
            put("max_tokens", 1)
            put("messages", buildJsonArray {
                addJsonObject {
                    put("role", "user")
                    put("content", "hi")
                }
            })
        }.toString()

        val req = buildBaseRequest(url, config, apiKey)
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        return runCatching {
            okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful || resp.code == 400 || resp.code == 404) {
                    // 400/404 with valid auth means endpoint reachable and key accepted; 401/403 is key error
                    ConnectionCheckResult(
                        success = true,
                        resolvedProtocol = protocol,
                        message = "Endpoint reachable (manual model ID may be needed)",
                    )
                } else {
                    val err = ErrorParser.fromHttpResponse(
                        resp.code,
                        body,
                        config.displayName,
                        config.selectedModelId.orEmpty(),
                        url,
                        resp.header("x-request-id").orEmpty(),
                        apiKey,
                    )
                    ConnectionCheckResult(false, protocol, err.providerMessage, error = err)
                }
            }
        }.getOrElse { t ->
            val err = ErrorParser.fromThrowable(t, config.displayName, endpointUrl = url, activeApiKey = apiKey)
            ConnectionCheckResult(false, protocol, err.providerMessage, error = err)
        }
    }

    override suspend fun fetchModels(config: ProviderConfig, apiKey: String): Result<List<AiModelInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = validateBaseUrl(config.baseUrl, config.displayName, apiKey)
                val candidates = buildCandidateModelsUrls(config, base)
                var lastFailure: Throwable? = null
                for ((index, url) in candidates.withIndex()) {
                    val attempt = runCatching {
                        val bodyStr = executeModelsGet(url, config, apiKey)
                        val isGemini = isGeminiOpenAiEndpoint(config, url)
                        validateAndParseModels(
                            providerId = config.id,
                            providerName = config.displayName,
                            url = url,
                            apiKey = apiKey,
                            bodyStr = bodyStr,
                            stripModelsPrefix = isGemini,
                        )
                    }
                    if (attempt.isSuccess) {
                        return@runCatching attempt.getOrThrow()
                    }
                    val err = attempt.exceptionOrNull()
                    if (lastFailure == null) {
                        lastFailure = err
                    }
                    val aiErr = (err as? AiException)?.aiError
                    val hasNext = index < candidates.lastIndex
                    val shouldRetryNext = hasNext && (
                        aiErr?.httpStatus == 404 ||
                            aiErr?.httpStatus == 405 ||
                            aiErr?.category == AiErrorCategory.INVALID_BASE_URL
                        )
                    if (!shouldRetryNext) {
                        throw err!!
                    }
                }
                throw lastFailure ?: IllegalStateException("Failed to load models")
            }
        }

    private fun validateAndParseModels(
        providerId: String,
        providerName: String,
        url: String,
        apiKey: String,
        bodyStr: String,
        stripModelsPrefix: Boolean = false,
    ): List<AiModelInfo> {
        val trimmed = bodyStr.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parsedJson = runCatching { modelsJsonParser.parseToJsonElement(trimmed) }.getOrNull()
        if (parsedJson == null) {
            val isHtml = trimmed.startsWith("<!doctype", ignoreCase = true) ||
                trimmed.startsWith("<html", ignoreCase = true) ||
                trimmed.contains("cf_chl_opt")
            val message = if (isHtml) {
                "Server returned HTML page instead of JSON models list. Check Base URL."
            } else {
                "Server returned non-JSON response when fetching models."
            }
            throw AiException(
                ErrorParser.fromHttpResponse(
                    statusCode = 200,
                    body = trimmed,
                    providerName = providerName,
                    modelId = "",
                    endpointUrl = url,
                    activeApiKey = apiKey,
                ).copy(
                    errorCode = if (isHtml) "HTML_RESPONSE" else "INVALID_JSON",
                    providerMessage = message,
                    category = AiErrorCategory.INVALID_BASE_URL,
                ),
            )
        }
        return parseModelsJson(providerId, trimmed, stripModelsPrefix = stripModelsPrefix)
    }

    private fun executeModelsGet(url: String, config: ProviderConfig, apiKey: String): String {
        val request = buildBaseRequest(url, config, apiKey)
            .header("Accept", "application/json")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw AiException(
                    ErrorParser.fromHttpResponse(
                        statusCode = response.code,
                        body = bodyStr,
                        providerName = config.displayName,
                        modelId = "",
                        endpointUrl = url,
                        requestId = response.header("x-request-id").orEmpty(),
                        activeApiKey = apiKey,
                    ),
                )
            }
            return bodyStr
        }
    }

    internal fun parseModelsResponse(providerId: String, bodyStr: String): List<AiModelInfo> =
        parseModelsJson(providerId, bodyStr)

    companion object {
        private val modelsJsonParser = Json { ignoreUnknownKeys = true }
        private val CLOUDFLARE_AI_V1_REGEX = Regex("""(?i)^(https?://api\.cloudflare\.com/client/v4/accounts/[^/]+/ai)/v1$""")

        internal fun isGeminiOpenAiEndpoint(config: ProviderConfig, baseOrUrl: String): Boolean =
            config.type == ProviderType.GOOGLE_GEMINI ||
                baseOrUrl.contains("generativelanguage.googleapis.com", ignoreCase = true)

        internal fun normalizeModelIdForRequest(
            config: ProviderConfig,
            baseOrUrl: String,
            modelId: String,
        ): String {
            val trimmed = modelId.trim()
            return if (isGeminiOpenAiEndpoint(config, baseOrUrl)) {
                trimmed.removePrefix("models/")
            } else {
                trimmed
            }
        }

        internal fun resolveOpenAiBaseUrl(config: ProviderConfig, normalizedBase: String): String {
            if (isGeminiOpenAiEndpoint(config, normalizedBase) &&
                normalizedBase.contains("generativelanguage.googleapis.com", ignoreCase = true) &&
                !normalizedBase.endsWith("/openai", ignoreCase = true)
            ) {
                return if (normalizedBase.endsWith("/v1beta", ignoreCase = true) ||
                    normalizedBase.endsWith("/v1", ignoreCase = true)
                ) {
                    "$normalizedBase/openai"
                } else {
                    "$normalizedBase/v1beta/openai"
                }
            }
            return normalizedBase
        }

        internal fun resolveModelsEndpointUrl(config: ProviderConfig, normalizedBase: String): String {
            val cfMatch = CLOUDFLARE_AI_V1_REGEX.matchEntire(normalizedBase)
            if (cfMatch != null) {
                return "${cfMatch.groupValues[1]}/models/search?per_page=100"
            }
            if (config.type == ProviderType.CLOUDFLARE && normalizedBase.endsWith("/ai/v1", ignoreCase = true)) {
                return "${normalizedBase.dropLast(3)}/models/search?per_page=100"
            }
            return "$normalizedBase/models"
        }

        internal fun buildCandidateModelsUrls(config: ProviderConfig, normalizedBase: String): List<String> {
            val candidates = mutableListOf<String>()
            candidates += resolveModelsEndpointUrl(config, normalizedBase)
            cloudflareFallbackUrl(normalizedBase)?.let { candidates += it }

            val isCloudflare = config.type == ProviderType.CLOUDFLARE ||
                normalizedBase.contains("api.cloudflare.com", ignoreCase = true)
            if (!isCloudflare) {
                if (normalizedBase.contains("generativelanguage.googleapis.com", ignoreCase = true) &&
                    !normalizedBase.endsWith("/openai", ignoreCase = true)
                ) {
                    if (normalizedBase.endsWith("/v1beta", ignoreCase = true) ||
                        normalizedBase.endsWith("/v1", ignoreCase = true)
                    ) {
                        candidates += "$normalizedBase/openai/models"
                    } else {
                        candidates += "$normalizedBase/v1beta/openai/models"
                    }
                } else if (!normalizedBase.endsWith("/v1", ignoreCase = true) &&
                    !normalizedBase.endsWith("/v1beta", ignoreCase = true) &&
                    !normalizedBase.endsWith("/openai", ignoreCase = true) &&
                    !normalizedBase.endsWith("/v2", ignoreCase = true)
                ) {
                    candidates += "$normalizedBase/v1/models"
                }

                if (config.type == ProviderType.CUSTOM_OPENAI ||
                    normalizedBase.contains(":11434") ||
                    normalizedBase.contains("ollama", ignoreCase = true)
                ) {
                    val rootWithoutV1 = normalizedBase.replace(Regex("(?i)/v1$"), "")
                    candidates += "$rootWithoutV1/api/tags"
                }
            }
            return candidates.distinct()
        }

        internal fun cloudflareFallbackUrl(normalizedBase: String): String? {
            if (normalizedBase.endsWith("/ai/v1", ignoreCase = true)) {
                return "${normalizedBase.dropLast(3)}/models/search?per_page=100"
            }
            return null
        }

        private val nonChatCloudflareTasks = setOf(
            "text embeddings",
            "text classification",
            "automatic speech recognition",
            "object detection",
            "image classification",
            "sentence similarity",
            "text-to-speech",
        )

        internal fun parseModelsJson(
            providerId: String,
            bodyStr: String,
            stripModelsPrefix: Boolean = false,
        ): List<AiModelInfo> {
            val root = runCatching { modelsJsonParser.parseToJsonElement(bodyStr) }.getOrNull() ?: return emptyList()
            val array: JsonArray = when (root) {
                is JsonArray -> root
                is JsonObject -> {
                    val dataField = root["data"]
                    val resultField = root["result"]
                    when {
                        dataField is JsonArray -> dataField
                        dataField is JsonObject && dataField["models"] is JsonArray -> dataField["models"] as JsonArray
                        dataField is JsonObject && dataField["items"] is JsonArray -> dataField["items"] as JsonArray
                        resultField is JsonArray -> resultField
                        resultField is JsonObject && resultField["models"] is JsonArray -> resultField["models"] as JsonArray
                        resultField is JsonObject && resultField["data"] is JsonArray -> resultField["data"] as JsonArray
                        resultField is JsonObject && resultField["items"] is JsonArray -> resultField["items"] as JsonArray
                        root["models"] is JsonArray -> root["models"] as JsonArray
                        root["results"] is JsonArray -> root["results"] as JsonArray
                        root["items"] is JsonArray -> root["items"] as JsonArray
                        root["value"] is JsonArray -> root["value"] as JsonArray
                        root["list"] is JsonArray -> root["list"] as JsonArray
                        else -> JsonArray(emptyList())
                    }
                }
                else -> JsonArray(emptyList())
            }
            return array.mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> {
                        val raw = element.contentOrNull?.trim().orEmpty()
                        val id = if (stripModelsPrefix) raw.removePrefix("models/") else raw
                        if (id.isEmpty()) return@mapNotNull null
                        if (stripModelsPrefix && isNonGenerativeGeminiModel(id)) return@mapNotNull null
                        AiModelInfo(
                            providerId = providerId,
                            modelId = id,
                            displayName = id,
                            capabilities = CapabilityInferencer.inferCapabilities(id, id),
                            isManual = false,
                            ownedBy = "",
                        )
                    }
                    is JsonObject -> {
                        val rawName = (element["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                        val rawId = (element["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                        val rawModel = (element["model"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                        val rawSlug = (element["slug"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                        val taskName = ((element["task"] as? JsonObject)?.get("name") as? JsonPrimitive)
                            ?.contentOrNull?.trim().orEmpty()
                        val isCloudflareSchema = (element["task"] is JsonObject) ||
                            (rawName != null && (rawName.startsWith("@cf/") || rawName.startsWith("@hf/")))

                        if (isCloudflareSchema && taskName.lowercase() in nonChatCloudflareTasks) {
                            return@mapNotNull null
                        }

                        val baseId = if (isCloudflareSchema && rawName != null) {
                            rawName
                        } else {
                            rawId
                                ?: rawModel
                                ?: rawName
                                ?: rawSlug
                                ?: return@mapNotNull null
                        }
                        val id = if (stripModelsPrefix) baseId.removePrefix("models/") else baseId
                        if (id.isEmpty()) return@mapNotNull null
                        if (stripModelsPrefix && isNonGenerativeGeminiModel(id)) return@mapNotNull null

                        val rawDisplay = (element["display_name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                            ?: (element["displayName"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                            ?: rawName
                            ?: id
                        val display = if (stripModelsPrefix) rawDisplay.removePrefix("models/") else rawDisplay
                        val ownedBy = (element["owned_by"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                            ?: (element["provider"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                            ?: if (isCloudflareSchema) "cloudflare" else ""
                        val ctx = (element["context_length"] as? JsonPrimitive)?.intOrNull
                            ?: (element["context_window"] as? JsonPrimitive)?.intOrNull
                            ?: (element["max_context_length"] as? JsonPrimitive)?.intOrNull
                        val explicitCaps = buildSet {
                            val taskLower = taskName.lowercase()
                            if (taskLower.contains("text-to-image") || taskLower.contains("image generation")) {
                                add("image")
                            }
                            if (taskLower.contains("image-to-text") || taskLower.contains("visual")) {
                                add("vision")
                            }
                        }
                        val caps = CapabilityInferencer.inferCapabilities(id, display, explicitCaps)
                        AiModelInfo(
                            providerId = providerId,
                            modelId = id,
                            displayName = display,
                            capabilities = caps,
                            contextWindow = ctx,
                            isManual = false,
                            ownedBy = ownedBy,
                        )
                    }
                    else -> null
                }
            }.distinctBy { it.modelId }.sortedBy { it.displayName.lowercase() }
        }

        private fun isNonGenerativeGeminiModel(modelId: String): Boolean {
            val lower = modelId.lowercase().removePrefix("models/")
            return lower.contains("embedding") || lower == "aqa"
        }

        internal data class ExtractedImagePayload(
            val base64: String? = null,
            val remoteUrl: String = "",
            val isExplicitFailure: Boolean = false,
        )

        internal fun isCloudflareEndpoint(config: ProviderConfig, baseOrUrl: String): Boolean =
            config.type == ProviderType.CLOUDFLARE ||
                baseOrUrl.contains("api.cloudflare.com", ignoreCase = true) ||
                CLOUDFLARE_AI_V1_REGEX.containsMatchIn(baseOrUrl)

        internal fun resolveImageEndpointUrl(
            config: ProviderConfig,
            normalizedBase: String,
            modelId: String,
        ): Pair<String, Boolean> {
            val cleanModelPath = modelId.trim().trimStart('/')
            val cfMatch = CLOUDFLARE_AI_V1_REGEX.matchEntire(normalizedBase)
            if (cfMatch != null) {
                return "${cfMatch.groupValues[1]}/run/$cleanModelPath" to true
            }
            if (isCloudflareEndpoint(config, normalizedBase)) {
                val aiRoot = when {
                    normalizedBase.endsWith("/ai/v1", ignoreCase = true) -> normalizedBase.dropLast(3)
                    normalizedBase.endsWith("/workers-ai/v1", ignoreCase = true) -> normalizedBase.dropLast(3)
                    normalizedBase.endsWith("/ai", ignoreCase = true) -> normalizedBase
                    normalizedBase.endsWith("/workers-ai", ignoreCase = true) -> normalizedBase
                    else -> null
                }
                if (aiRoot != null) {
                    return "$aiRoot/run/$cleanModelPath" to true
                }
            }
            val chatBase = resolveOpenAiBaseUrl(config, normalizedBase)
            return "$chatBase/images/generations" to false
        }

        internal fun requiresCloudflareMultipartImageRequest(modelId: String): Boolean =
            modelId.contains("flux-2", ignoreCase = true)

        internal fun isJsonResponse(bytes: ByteArray, contentType: String): Boolean {
            val ct = contentType.lowercase()
            if (ct.contains("json")) return true
            if (ct.startsWith("image/")) return false
            for (b in bytes) {
                val c = b.toInt().toChar()
                if (!c.isWhitespace()) {
                    return c == '{' || c == '['
                }
            }
            return false
        }

        internal fun extractImagePayloadFromJson(rawJson: String): ExtractedImagePayload {
            val root = modelsJsonParser.parseToJsonElement(rawJson).jsonObject
            if ((root["success"] as? JsonPrimitive)?.booleanOrNull == false) {
                return ExtractedImagePayload(isExplicitFailure = true)
            }
            val dataItem = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
            if (dataItem != null) {
                val b64 = (dataItem["b64_json"] as? JsonPrimitive)?.contentOrNull
                    ?: (dataItem["image"] as? JsonPrimitive)?.contentOrNull
                val url = (dataItem["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                return ExtractedImagePayload(base64 = b64, remoteUrl = url)
            }
            when (val resultElem = root["result"]) {
                is JsonObject -> {
                    val b64 = (resultElem["image"] as? JsonPrimitive)?.contentOrNull
                        ?: (resultElem["b64_json"] as? JsonPrimitive)?.contentOrNull
                        ?: (resultElem["base64"] as? JsonPrimitive)?.contentOrNull
                    val url = (resultElem["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    return ExtractedImagePayload(base64 = b64, remoteUrl = url)
                }
                is JsonPrimitive -> {
                    val str = resultElem.contentOrNull?.trim().orEmpty()
                    if (str.isNotEmpty()) {
                        return if (str.startsWith("http://", ignoreCase = true) || str.startsWith("https://", ignoreCase = true)) {
                            ExtractedImagePayload(remoteUrl = str)
                        } else {
                            ExtractedImagePayload(base64 = str)
                        }
                    }
                }
                else -> Unit
            }
            val topB64 = (root["b64_json"] as? JsonPrimitive)?.contentOrNull
                ?: (root["image"] as? JsonPrimitive)?.contentOrNull
            val topUrl = (root["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            return ExtractedImagePayload(base64 = topB64, remoteUrl = topUrl)
        }

        internal fun decodeBase64ImageBytes(rawBase64: String): ByteArray {
            val cleaned = if (rawBase64.startsWith("data:", ignoreCase = true) && rawBase64.contains(";base64,")) {
                rawBase64.substringAfter(";base64,")
            } else {
                rawBase64
            }
            return Base64.getMimeDecoder().decode(cleaned.trim())
        }

        internal fun detectImageFormat(bytes: ByteArray, contentType: String = ""): Pair<String, String> {
            if (bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
            ) {
                return "jpg" to "image/jpeg"
            }
            if (bytes.size >= 4 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte()
            ) {
                return "png" to "image/png"
            }
            if (bytes.size >= 12 &&
                bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() &&
                bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() &&
                bytes[11] == 'P'.code.toByte()
            ) {
                return "webp" to "image/webp"
            }
            if (bytes.size >= 3 &&
                bytes[0] == 'G'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte()
            ) {
                return "gif" to "image/gif"
            }
            val ct = contentType.lowercase()
            return when {
                ct.contains("jpeg") || ct.contains("jpg") -> "jpg" to "image/jpeg"
                ct.contains("webp") -> "webp" to "image/webp"
                ct.contains("gif") -> "gif" to "image/gif"
                else -> "png" to "image/png"
            }
        }
    }

    override fun streamChat(
        config: ProviderConfig,
        apiKey: String,
        request: ChatCompletionRequest,
    ): Flow<StreamEvent> = flow {
        val base = try {
            validateBaseUrl(config.baseUrl, config.displayName, apiKey)
        } catch (e: AiException) {
            emit(StreamEvent.Failure(e.aiError))
            return@flow
        }
        val chatBase = resolveOpenAiBaseUrl(config, base)
        val endpointUrl = "$chatBase/chat/completions"
        val isGemini = isGeminiOpenAiEndpoint(config, chatBase)
        val effectiveModelId = normalizeModelIdForRequest(config, chatBase, request.modelId)

        val messagesJson = buildJsonArray {
            if (request.systemPrompt.isNotBlank()) {
                addJsonObject {
                    put("role", "system")
                    put("content", request.systemPrompt)
                }
            }
            if (request.toolConversationHistory.isNotEmpty()) {
                for (item in request.toolConversationHistory) {
                    buildConversationItemJson(item, includeToolName = isGemini)?.let { add(it) }
                }
            } else {
                for (msg in request.messages) {
                    buildMessageJson(msg)?.let { add(it) }
                }
            }
        }

        val primaryPayload = buildChatPayload(
            modelId = effectiveModelId,
            messagesJson = messagesJson,
            stream = request.stream,
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            tools = request.tools,
            includeOptionalSampling = true,
        )

        var httpRequest = buildBaseRequest(endpointUrl, config, apiKey)
            .header("Accept", if (request.stream) "text/event-stream" else "application/json")
            .post(primaryPayload.toRequestBody(jsonMediaType))
            .build()

        var usedStreaming = request.stream
        var call = okHttpClient.newCall(httpRequest)
        try {
            var response = call.execute()
            if (!response.isSuccessful && response.code == 400 && isGemini && request.tools.isEmpty()) {
                val firstErrBody = response.body?.string().orEmpty()
                response.close()
                // Fallback once with minimal Gemini OpenAI payload (no stream/temperature/max_tokens)
                usedStreaming = false
                val fallbackPayload = buildChatPayload(
                    modelId = effectiveModelId,
                    messagesJson = messagesJson,
                    stream = false,
                    temperature = request.temperature,
                    maxTokens = request.maxTokens,
                    tools = emptyList(),
                    includeOptionalSampling = false,
                )
                httpRequest = buildBaseRequest(endpointUrl, config, apiKey)
                    .header("Accept", "application/json")
                    .post(fallbackPayload.toRequestBody(jsonMediaType))
                    .build()
                call = okHttpClient.newCall(httpRequest)
                response = call.execute()
                if (!response.isSuccessful) {
                    val secondErrBody = response.body?.string().orEmpty().ifBlank { firstErrBody }
                    val reqId = response.header("x-request-id") ?: response.header("cf-ray") ?: ""
                    val code = response.code
                    response.close()
                    emit(
                        StreamEvent.Failure(
                            ErrorParser.fromHttpResponse(
                                statusCode = code,
                                body = secondErrBody,
                                providerName = config.displayName,
                                modelId = effectiveModelId,
                                endpointUrl = endpointUrl,
                                requestId = reqId,
                                activeApiKey = apiKey,
                            ),
                        ),
                    )
                    return@flow
                }
            }

            response.use { activeResponse ->
                val requestId = activeResponse.header("x-request-id")
                    ?: activeResponse.header("cf-ray")
                    ?: ""
                if (!activeResponse.isSuccessful) {
                    val errBody = activeResponse.body?.string().orEmpty()
                    emit(
                        StreamEvent.Failure(
                            ErrorParser.fromHttpResponse(
                                statusCode = activeResponse.code,
                                body = errBody,
                                providerName = config.displayName,
                                modelId = effectiveModelId,
                                endpointUrl = endpointUrl,
                                requestId = requestId,
                                activeApiKey = apiKey,
                            ),
                        ),
                    )
                    return@flow
                }

                val body = activeResponse.body ?: run {
                    emit(
                        StreamEvent.Failure(
                            ErrorParser.fromHttpResponse(
                                statusCode = activeResponse.code,
                                body = "Empty response body",
                                providerName = config.displayName,
                                modelId = effectiveModelId,
                                endpointUrl = endpointUrl,
                                requestId = requestId,
                                activeApiKey = apiKey,
                            ),
                        ),
                    )
                    return@flow
                }

                val contentType = activeResponse.header("Content-Type").orEmpty()
                if (!usedStreaming || contentType.contains("application/json", ignoreCase = true)) {
                    val raw = body.string()
                    val parsed = parseNonStreamingChoice(raw)
                    if (parsed.toolCalls.isNotEmpty()) {
                        emit(StreamEvent.ToolCallsRequested(parsed.toolCalls, parsed.text, parsed.reasoning))
                    } else {
                        emit(StreamEvent.Completed(parsed.text, parsed.reasoning))
                    }
                } else {
                    val parsed = readSseStream(body.source()) { deltaText, deltaReasoning ->
                        emit(StreamEvent.ContentDelta(deltaText, deltaReasoning))
                    }
                    if (parsed.toolCalls.isNotEmpty()) {
                        emit(StreamEvent.ToolCallsRequested(parsed.toolCalls, parsed.text, parsed.reasoning))
                    } else {
                        emit(StreamEvent.Completed(parsed.text, parsed.reasoning))
                    }
                }
            }
        } catch (ce: CancellationException) {
            call.cancel()
            throw ce
        } catch (t: Throwable) {
            emit(
                StreamEvent.Failure(
                    ErrorParser.fromThrowable(
                        throwable = t,
                        providerName = config.displayName,
                        modelId = effectiveModelId,
                        endpointUrl = endpointUrl,
                        activeApiKey = apiKey,
                    ),
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    internal fun buildChatPayload(
        modelId: String,
        messagesJson: JsonArray,
        stream: Boolean,
        temperature: Float,
        maxTokens: Int,
        tools: List<ToolDefinition>,
        includeOptionalSampling: Boolean,
    ): String {
        val cleanTemp = kotlin.math.round(temperature.toDouble() * 100.0) / 100.0
        return buildJsonObject {
            put("model", modelId)
            put("messages", messagesJson)
            put("stream", stream)
            if (includeOptionalSampling) {
                put("temperature", cleanTemp)
                if (maxTokens > 0) {
                    put("max_tokens", maxTokens)
                }
            }
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        for (tool in tools) {
                            addJsonObject {
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.parametersSchema)
                                }
                            }
                        }
                    },
                )
                put("tool_choice", "auto")
            }
        }.toString()
    }

    internal suspend fun buildConversationItemJson(
        item: ConversationItem,
        includeToolName: Boolean = false,
    ): JsonObject? {
        return when (item) {
            is ConversationItem.StandardMessage -> buildMessageJson(item.message)
            is ConversationItem.AssistantToolCallTurn -> buildJsonObject {
                put("role", "assistant")
                if (item.text.isNotBlank()) {
                    put("content", item.text)
                }
                put(
                    "tool_calls",
                    buildJsonArray {
                        for (tc in item.toolCalls) {
                            addJsonObject {
                                put("id", tc.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", tc.name)
                                    put("arguments", tc.argumentsJson.ifBlank { "{}" })
                                }
                            }
                        }
                    },
                )
            }
            is ConversationItem.ToolResultTurn -> buildJsonObject {
                put("role", "tool")
                put("tool_call_id", item.toolCallId)
                if (includeToolName && item.toolName.isNotBlank()) {
                    put("name", item.toolName)
                }
                put("content", item.content.ifBlank { "{}" })
            }
        }
    }

    private suspend fun buildMessageJson(msg: ChatMessage): JsonObject? {
        val role = when (msg.role) {
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.SYSTEM -> "system"
        }
        val textParts = mutableListOf<String>()
        if (msg.content.isNotBlank()) {
            textParts += msg.content
        }

        val imageDataUris = mutableListOf<String>()
        for (att in msg.attachments) {
            when (att.type) {
                AttachmentType.IMAGE -> {
                    val encoded = attachmentExtractor.encodeImageAsBase64(att.localUri)
                    if (encoded != null) {
                        imageDataUris += "data:${encoded.first};base64,${encoded.second}"
                    }
                }
                AttachmentType.DOCUMENT, AttachmentType.TEXT_FILE -> {
                    val docHeader = "[Attachment: ${att.displayName} (${att.mimeType}, ${att.sizeBytes} bytes)]"
                    if (att.extractedText.isNotBlank()) {
                        textParts += "$docHeader\n${att.extractedText}"
                    } else {
                        textParts += "$docHeader (binary file attached)"
                    }
                }
                AttachmentType.WEB_LINK -> {
                    val linkHeader = buildString {
                        append("[Web Link: ${att.url}]")
                        if (att.pageTitle.isNotBlank()) append("\nTitle: ${att.pageTitle}")
                        if (att.extractedText.isNotBlank()) append("\nExtracted Content:\n${att.extractedText}")
                    }
                    textParts += linkHeader
                }
            }
        }

        val combinedText = textParts.joinToString("\n\n")
        if (combinedText.isBlank() && imageDataUris.isEmpty()) {
            return null
        }
        return if (imageDataUris.isEmpty()) {
            buildJsonObject {
                put("role", role)
                put("content", combinedText)
            }
        } else {
            buildJsonObject {
                put("role", role)
                put("content", buildJsonArray {
                    if (combinedText.isNotBlank()) {
                        addJsonObject {
                            put("type", "text")
                            put("text", combinedText)
                        }
                    }
                    for (dataUri in imageDataUris) {
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", dataUri)
                            }
                        }
                    }
                })
            }
        }
    }

    private data class ParsedOpenAiResponse(
        val text: String,
        val reasoning: String,
        val toolCalls: List<ToolCallRequest> = emptyList(),
    )

    private class ToolCallAccumulator(
        var id: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder(),
    )

    private suspend fun readSseStream(
        source: BufferedSource,
        onDelta: suspend (String, String) -> Unit,
    ): ParsedOpenAiResponse {
        val fullText = StringBuilder()
        val fullReasoning = StringBuilder()
        val toolCallMap = sortedMapOf<Int, ToolCallAccumulator>()

        while (currentCoroutineContext().isActive && !source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            if (data.isBlank()) continue

            runCatching {
                val obj = json.parseToJsonElement(data).jsonObject
                val choices = obj["choices"]?.jsonArray ?: return@runCatching
                val first = choices.firstOrNull()?.jsonObject ?: return@runCatching
                val delta = first["delta"]?.jsonObject ?: first["message"]?.jsonObject ?: return@runCatching
                val content = delta["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val reasoning = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
                    ?: delta["reasoning"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                if (content.isNotEmpty() || reasoning.isNotEmpty()) {
                    fullText.append(content)
                    fullReasoning.append(reasoning)
                    onDelta(content, reasoning)
                }

                val tcArray = delta["tool_calls"]?.jsonArray
                if (tcArray != null) {
                    tcArray.forEachIndexed { pos, tcElem ->
                        val tcObj = tcElem as? JsonObject ?: return@forEachIndexed
                        val idx = (tcObj["index"] as? JsonPrimitive)?.intOrNull ?: pos
                        val acc = toolCallMap.getOrPut(idx) { ToolCallAccumulator() }
                        (tcObj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                            acc.id = it
                        }
                        val fnObj = tcObj["function"] as? JsonObject
                        if (fnObj != null) {
                            (fnObj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                                acc.name = it
                            }
                            (fnObj["arguments"] as? JsonPrimitive)?.contentOrNull?.let { argChunk ->
                                acc.args.append(argChunk)
                            }
                        }
                    }
                }
            }
        }
        val finalCalls = toolCallMap.values
            .filter { it.name.isNotBlank() }
            .mapIndexed { idx, acc ->
                ToolCallRequest(
                    id = acc.id.ifBlank { "call_${idx}_${UUID.randomUUID().toString().take(6)}" },
                    name = acc.name,
                    argumentsJson = acc.args.toString().ifBlank { "{}" },
                )
            }
        return ParsedOpenAiResponse(fullText.toString(), fullReasoning.toString(), finalCalls)
    }

    private fun parseNonStreamingChoice(rawJson: String): ParsedOpenAiResponse {
        val obj = json.parseToJsonElement(rawJson).jsonObject
        val first = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return ParsedOpenAiResponse("", "")
        val msg = first["message"]?.jsonObject ?: return ParsedOpenAiResponse("", "")
        val content = msg["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val reasoning = msg["reasoning_content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val toolCalls = msg["tool_calls"]?.jsonArray?.mapIndexedNotNull { idx, elem ->
            val tcObj = elem as? JsonObject ?: return@mapIndexedNotNull null
            val fn = tcObj["function"] as? JsonObject ?: return@mapIndexedNotNull null
            val name = fn["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty()) return@mapIndexedNotNull null
            val id = tcObj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .ifBlank { "call_${idx}_${UUID.randomUUID().toString().take(6)}" }
            val args = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
            ToolCallRequest(id = id, name = name, argumentsJson = args)
        }.orEmpty()
        return ParsedOpenAiResponse(content, reasoning, toolCalls)
    }

    override suspend fun generateImage(
        config: ProviderConfig,
        apiKey: String,
        request: MediaGenerationRequest,
    ): Result<GeneratedMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val base = validateBaseUrl(config.baseUrl, config.displayName, apiKey)
            val effectiveModelId = normalizeModelIdForRequest(config, base, request.modelId)
            val (endpointUrl, isCloudflareRun) = resolveImageEndpointUrl(config, base, effectiveModelId)

            val requestBody = if (isCloudflareRun) {
                if (requiresCloudflareMultipartImageRequest(effectiveModelId)) {
                    MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("prompt", request.prompt)
                        .build()
                } else {
                    buildJsonObject {
                        put("prompt", request.prompt)
                    }.toString().toRequestBody(jsonMediaType)
                }
            } else {
                buildJsonObject {
                    put("model", effectiveModelId)
                    put("prompt", request.prompt)
                    put("n", 1)
                    put("size", "1024x1024")
                }.toString().toRequestBody(jsonMediaType)
            }

            val httpReq = buildBaseRequest(endpointUrl, config, apiKey)
                .post(requestBody)
                .build()

            okHttpClient.newCall(httpReq).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val requestId = resp.header("x-request-id") ?: resp.header("cf-ray") ?: ""
                if (!resp.isSuccessful) {
                    throw AiException(
                        ErrorParser.fromHttpResponse(
                            resp.code,
                            bytes.decodeToString(),
                            config.displayName,
                            request.modelId,
                            endpointUrl,
                            requestId,
                            apiKey,
                        ),
                    )
                }
                if (bytes.isEmpty()) {
                    error("Empty image response")
                }

                val contentType = resp.header("Content-Type").orEmpty()
                var remoteUrl = ""
                val localUri: String
                val mimeType: String

                if (isJsonResponse(bytes, contentType)) {
                    val raw = bytes.decodeToString()
                    val extracted = extractImagePayloadFromJson(raw)
                    if (extracted.isExplicitFailure) {
                        throw AiException(
                            ErrorParser.fromHttpResponse(
                                400,
                                raw,
                                config.displayName,
                                request.modelId,
                                endpointUrl,
                                requestId,
                                apiKey,
                            ),
                        )
                    }
                    remoteUrl = extracted.remoteUrl
                    when {
                        !extracted.base64.isNullOrBlank() -> {
                            val decoded = decodeBase64ImageBytes(extracted.base64)
                            if (decoded.isEmpty()) error("Decoded image is empty")
                            val (ext, detectedMime) = detectImageFormat(decoded, contentType)
                            mimeType = detectedMime
                            localUri = mediaCacheManager.cacheBytes(decoded, ext)
                        }
                        remoteUrl.isNotBlank() -> {
                            mimeType = "image/png"
                            localUri = mediaCacheManager.downloadAndCache(remoteUrl, "png").getOrThrow()
                        }
                        else -> error("No image returned in response")
                    }
                } else {
                    val (ext, detectedMime) = detectImageFormat(bytes, contentType)
                    mimeType = detectedMime
                    localUri = mediaCacheManager.cacheBytes(bytes, ext)
                }

                GeneratedMedia(
                    id = UUID.randomUUID().toString(),
                    type = GeneratedMediaType.IMAGE,
                    remoteUrl = remoteUrl,
                    localUri = localUri,
                    mimeType = mimeType,
                    prompt = request.prompt,
                    statusText = "Completed",
                )
            }
        }
    }

    override suspend fun generateVideo(
        config: ProviderConfig,
        apiKey: String,
        request: MediaGenerationRequest,
    ): Result<GeneratedMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val base = validateBaseUrl(config.baseUrl, config.displayName, apiKey)
            val chatBase = resolveOpenAiBaseUrl(config, base)
            val endpointUrl = "$chatBase/videos/generations"
            val effectiveModelId = normalizeModelIdForRequest(config, chatBase, request.modelId)
            val payload = buildJsonObject {
                put("model", effectiveModelId)
                put("prompt", request.prompt)
            }.toString()

            val httpReq = buildBaseRequest(endpointUrl, config, apiKey)
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            okHttpClient.newCall(httpReq).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw AiException(
                        ErrorParser.fromHttpResponse(
                            resp.code,
                            raw,
                            config.displayName,
                            request.modelId,
                            endpointUrl,
                            resp.header("x-request-id").orEmpty(),
                            apiKey,
                        ),
                    )
                }
                val root = json.parseToJsonElement(raw).jsonObject
                var remoteUrl = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("url")?.jsonPrimitive?.contentOrNull
                    ?: root["url"]?.jsonPrimitive?.contentOrNull.orEmpty()

                val jobId = root["id"]?.jsonPrimitive?.contentOrNull
                if (remoteUrl.isBlank() && !jobId.isNullOrBlank()) {
                    remoteUrl = pollVideoJob(config, apiKey, "$base/videos/$jobId")
                }

                val localUri = if (remoteUrl.isNotBlank()) {
                    mediaCacheManager.downloadAndCache(remoteUrl, "mp4").getOrDefault("")
                } else {
                    ""
                }

                GeneratedMedia(
                    id = UUID.randomUUID().toString(),
                    type = GeneratedMediaType.VIDEO,
                    remoteUrl = remoteUrl,
                    localUri = localUri,
                    mimeType = "video/mp4",
                    prompt = request.prompt,
                    statusText = if (localUri.isNotBlank() || remoteUrl.isNotBlank()) "Completed" else "Pending",
                )
            }
        }
    }

    private suspend fun pollVideoJob(config: ProviderConfig, apiKey: String, statusUrl: String): String {
        repeat(15) {
            delay(2000L)
            val req = buildBaseRequest(statusUrl, config, apiKey).get().build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val body = resp.body?.string().orEmpty()
                val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@use
                val status = obj["status"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: obj["output"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                    ?: obj["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                    ?: ""
                if (url.isNotBlank() || status == "completed" || status == "succeeded") {
                    return url
                }
                if (status == "failed" || status == "error") {
                    throw AiException(
                        ErrorParser.fromHttpResponse(
                            500,
                            body,
                            config.displayName,
                            "",
                            statusUrl,
                            "",
                            apiKey,
                        ),
                    )
                }
            }
        }
        return ""
    }

    private fun buildBaseRequest(url: String, config: ProviderConfig, apiKey: String): Request.Builder {
        val builder = Request.Builder().url(url)
            .header("User-Agent", "AIChat-Android/1.0")
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        if (config.organizationId.isNotBlank()) {
            builder.header("OpenAI-Organization", config.organizationId.trim())
        }
        if (config.projectId.isNotBlank()) {
            builder.header("OpenAI-Project", config.projectId.trim())
        }
        for (h in config.customHeaders) {
            if (h.name.isNotBlank() && h.value.isNotBlank()) {
                builder.header(h.name.trim(), h.value.trim())
            }
        }
        return builder
    }

    private fun normalizeBaseUrl(raw: String): String {
        var trimmed = raw.trim().trimEnd('/')
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return ""
        val suffixes = listOf("/chat/completions", "/messages", "/models/search", "/models")
        for (suffix in suffixes) {
            if (trimmed.endsWith(suffix, ignoreCase = true)) {
                trimmed = trimmed.dropLast(suffix.length).trimEnd('/')
            }
        }
        return trimmed
    }

    private fun validateBaseUrl(raw: String, providerName: String, apiKey: String): String {
        val normalized = normalizeBaseUrl(raw)
        if (normalized.isBlank() || normalized.contains("YOUR_ACCOUNT_ID", ignoreCase = true)) {
            val msg = if (normalized.contains("YOUR_ACCOUNT_ID", ignoreCase = true)) {
                "Replace YOUR_ACCOUNT_ID in Base URL with your Cloudflare Account ID"
            } else {
                "Invalid Base URL"
            }
            throw AiException(
                ErrorParser.fromThrowable(
                    IllegalArgumentException(msg),
                    providerName = providerName,
                    endpointUrl = raw,
                    activeApiKey = apiKey,
                ).copy(category = AiErrorCategory.INVALID_BASE_URL),
            )
        }
        return normalized
    }
}
