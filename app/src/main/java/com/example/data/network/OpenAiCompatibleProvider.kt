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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
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
        val url = "$base/chat/completions"
        val payload = buildJsonObject {
            put("model", config.selectedModelId ?: "probe")
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
                        validateAndParseModels(config.id, config.displayName, url, apiKey, bodyStr)
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
        return parseModelsJson(providerId, trimmed)
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

        internal fun parseModelsJson(providerId: String, bodyStr: String): List<AiModelInfo> {
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
                        val id = element.contentOrNull?.trim().orEmpty()
                        if (id.isEmpty()) return@mapNotNull null
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

                        val id = if (isCloudflareSchema && rawName != null) {
                            rawName
                        } else {
                            rawId
                                ?: rawModel
                                ?: rawName
                                ?: rawSlug
                                ?: return@mapNotNull null
                        }
                        val display = (element["display_name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                            ?: (element["displayName"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                            ?: rawName
                            ?: id
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
        val endpointUrl = "$base/chat/completions"

        val messagesJson = buildJsonArray {
            if (request.systemPrompt.isNotBlank()) {
                addJsonObject {
                    put("role", "system")
                    put("content", request.systemPrompt)
                }
            }
            if (request.toolConversationHistory.isNotEmpty()) {
                for (item in request.toolConversationHistory) {
                    add(buildConversationItemJson(item))
                }
            } else {
                for (msg in request.messages) {
                    add(buildMessageJson(msg))
                }
            }
        }

        val payload = buildJsonObject {
            put("model", request.modelId)
            put("messages", messagesJson)
            put("stream", request.stream)
            put("temperature", request.temperature.toDouble())
            put("max_tokens", request.maxTokens)
            if (request.tools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        for (tool in request.tools) {
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

        val httpRequest = buildBaseRequest(endpointUrl, config, apiKey)
            .header("Accept", if (request.stream) "text/event-stream" else "application/json")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        val call = okHttpClient.newCall(httpRequest)
        try {
            call.execute().use { response ->
                val requestId = response.header("x-request-id")
                    ?: response.header("cf-ray")
                    ?: ""
                if (!response.isSuccessful) {
                    val errBody = response.body?.string().orEmpty()
                    emit(
                        StreamEvent.Failure(
                            ErrorParser.fromHttpResponse(
                                statusCode = response.code,
                                body = errBody,
                                providerName = config.displayName,
                                modelId = request.modelId,
                                endpointUrl = endpointUrl,
                                requestId = requestId,
                                activeApiKey = apiKey,
                            ),
                        ),
                    )
                    return@flow
                }

                val body = response.body ?: run {
                    emit(
                        StreamEvent.Failure(
                            ErrorParser.fromHttpResponse(
                                statusCode = response.code,
                                body = "Empty response body",
                                providerName = config.displayName,
                                modelId = request.modelId,
                                endpointUrl = endpointUrl,
                                requestId = requestId,
                                activeApiKey = apiKey,
                            ),
                        ),
                    )
                    return@flow
                }

                val contentType = response.header("Content-Type").orEmpty()
                if (!request.stream || contentType.contains("application/json", ignoreCase = true)) {
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
                        modelId = request.modelId,
                        endpointUrl = endpointUrl,
                        activeApiKey = apiKey,
                    ),
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun buildConversationItemJson(item: ConversationItem): JsonObject {
        return when (item) {
            is ConversationItem.StandardMessage -> buildMessageJson(item.message)
            is ConversationItem.AssistantToolCallTurn -> buildJsonObject {
                put("role", "assistant")
                if (item.text.isNotBlank()) {
                    put("content", item.text)
                } else {
                    put("content", "")
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
                put("content", item.content)
            }
        }
    }

    private suspend fun buildMessageJson(msg: ChatMessage): JsonObject {
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
            val endpointUrl = "$base/images/generations"
            val payload = buildJsonObject {
                put("model", request.modelId)
                put("prompt", request.prompt)
                put("n", 1)
                put("size", "1024x1024")
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
                val item = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: error("No image returned in response")
                val b64 = item["b64_json"]?.jsonPrimitive?.contentOrNull
                val remoteUrl = item["url"]?.jsonPrimitive?.contentOrNull.orEmpty()

                val localUri = when {
                    !b64.isNullOrBlank() -> mediaCacheManager.cacheBase64(b64, "png")
                    remoteUrl.isNotBlank() -> mediaCacheManager.downloadAndCache(remoteUrl, "png").getOrDefault("")
                    else -> ""
                }

                GeneratedMedia(
                    id = UUID.randomUUID().toString(),
                    type = GeneratedMediaType.IMAGE,
                    remoteUrl = remoteUrl,
                    localUri = localUri,
                    mimeType = "image/png",
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
            val endpointUrl = "$base/videos/generations"
            val payload = buildJsonObject {
                put("model", request.modelId)
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
