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
            if (base.isBlank()) {
                return@withContext ConnectionCheckResult(
                    success = false,
                    resolvedProtocol = protocol,
                    message = "Invalid Base URL",
                    error = ErrorParser.fromThrowable(
                        IllegalArgumentException("Invalid Base URL"),
                        config.displayName,
                        endpointUrl = config.baseUrl,
                        activeApiKey = apiKey,
                    ),
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
                        endpointUrl = "$base/models",
                        activeApiKey = apiKey,
                    )
                    // Some endpoints (e.g. Perplexity) may not support GET /models -> probe minimal chat if 404/405
                    if (aiError.httpStatus == 404 || aiError.httpStatus == 405) {
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
                val url = "$base/models"
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
                    parseModelsResponse(config.id, bodyStr)
                }
            }
        }

    internal fun parseModelsResponse(providerId: String, bodyStr: String): List<AiModelInfo> =
        parseModelsJson(providerId, bodyStr)

    companion object {
        private val modelsJsonParser = Json { ignoreUnknownKeys = true }

        internal fun parseModelsJson(providerId: String, bodyStr: String): List<AiModelInfo> {
            val root = runCatching { modelsJsonParser.parseToJsonElement(bodyStr) }.getOrNull() ?: return emptyList()
            val array: JsonArray = when (root) {
                is JsonArray -> root
                is JsonObject -> {
                    val dataField = root["data"]
                    when {
                        dataField is JsonArray -> dataField
                        dataField is JsonObject && dataField["models"] is JsonArray -> dataField["models"] as JsonArray
                        dataField is JsonObject && dataField["items"] is JsonArray -> dataField["items"] as JsonArray
                        root["models"] is JsonArray -> root["models"] as JsonArray
                        root["results"] is JsonArray -> root["results"] as JsonArray
                        root["items"] is JsonArray -> root["items"] as JsonArray
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
                        val id = (element["id"] as? JsonPrimitive)?.contentOrNull
                            ?: (element["model"] as? JsonPrimitive)?.contentOrNull
                            ?: (element["name"] as? JsonPrimitive)?.contentOrNull
                            ?: (element["slug"] as? JsonPrimitive)?.contentOrNull
                            ?: return@mapNotNull null
                        val display = (element["display_name"] as? JsonPrimitive)?.contentOrNull
                            ?: (element["displayName"] as? JsonPrimitive)?.contentOrNull
                            ?: (element["name"] as? JsonPrimitive)?.contentOrNull
                            ?: id
                        val ownedBy = (element["owned_by"] as? JsonPrimitive)?.contentOrNull
                            ?: (element["provider"] as? JsonPrimitive)?.contentOrNull
                            ?: ""
                        val ctx = (element["context_length"] as? JsonPrimitive)?.intOrNull
                            ?: (element["context_window"] as? JsonPrimitive)?.intOrNull
                            ?: (element["max_context_length"] as? JsonPrimitive)?.intOrNull
                        val caps = CapabilityInferencer.inferCapabilities(id, display)
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
            for (msg in request.messages) {
                add(buildMessageJson(msg))
            }
        }

        val payload = buildJsonObject {
            put("model", request.modelId)
            put("messages", messagesJson)
            put("stream", request.stream)
            put("temperature", request.temperature.toDouble())
            put("max_tokens", request.maxTokens)
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
                    emit(StreamEvent.Completed(parsed.first, parsed.second))
                } else {
                    val (fullText, fullReasoning) = readSseStream(body.source()) { deltaText, deltaReasoning ->
                        emit(StreamEvent.ContentDelta(deltaText, deltaReasoning))
                    }
                    emit(StreamEvent.Completed(fullText, fullReasoning))
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

    private suspend fun readSseStream(
        source: BufferedSource,
        onDelta: suspend (String, String) -> Unit,
    ): Pair<String, String> {
        val fullText = StringBuilder()
        val fullReasoning = StringBuilder()
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
            }
        }
        return fullText.toString() to fullReasoning.toString()
    }

    private fun parseNonStreamingChoice(rawJson: String): Pair<String, String> {
        val obj = json.parseToJsonElement(rawJson).jsonObject
        val first = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return "" to ""
        val msg = first["message"]?.jsonObject ?: return "" to ""
        val content = msg["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val reasoning = msg["reasoning_content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return content to reasoning
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
        val trimmed = raw.trim().trimEnd('/')
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return ""
        return trimmed
    }

    private fun validateBaseUrl(raw: String, providerName: String, apiKey: String): String {
        val normalized = normalizeBaseUrl(raw)
        if (normalized.isBlank()) {
            throw AiException(
                ErrorParser.fromThrowable(
                    IllegalArgumentException("Invalid Base URL"),
                    providerName = providerName,
                    endpointUrl = raw,
                    activeApiKey = apiKey,
                ).copy(category = AiErrorCategory.INVALID_BASE_URL),
            )
        }
        return normalized
    }
}
