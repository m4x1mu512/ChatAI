package com.example.data.network

import com.example.core.error.AiError
import com.example.core.error.AiErrorCategory
import com.example.core.error.AiException
import com.example.core.error.ErrorParser
import com.example.core.model.AiModelInfo
import com.example.core.model.ApiProtocol
import com.example.core.model.AttachmentType
import com.example.core.model.CapabilityInferencer
import com.example.core.model.ChatMessage
import com.example.core.model.GeneratedMedia
import com.example.core.model.MessageRole
import com.example.core.model.ProviderConfig
import com.example.core.util.AttachmentExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnthropicCompatibleProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val attachmentExtractor: AttachmentExtractor,
) : AiProvider {

    override val protocol: ApiProtocol = ApiProtocol.ANTHROPIC_COMPATIBLE
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
                    )
                },
                onFailure = { throwable ->
                    val err = ErrorParser.fromThrowable(
                        throwable,
                        config.displayName,
                        endpointUrl = "$base/v1/models",
                        activeApiKey = apiKey,
                    )
                    ConnectionCheckResult(
                        success = false,
                        resolvedProtocol = protocol,
                        message = err.providerMessage,
                        error = err,
                    )
                },
            )
        }

    override suspend fun fetchModels(config: ProviderConfig, apiKey: String): Result<List<AiModelInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = validateBaseUrl(config.baseUrl, config.displayName, apiKey)
                val url = if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
                val request = buildBaseRequest(url, config, apiKey).get().build()
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
                                requestId = response.header("request-id").orEmpty(),
                                activeApiKey = apiKey,
                            ),
                        )
                    }
                    val root = json.parseToJsonElement(bodyStr).jsonObject
                    val data = root["data"]?.jsonArray ?: buildJsonArray {}
                    data.mapNotNull { elem ->
                        val obj = elem.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val display = obj["display_name"]?.jsonPrimitive?.contentOrNull ?: id
                        AiModelInfo(
                            providerId = config.id,
                            modelId = id,
                            displayName = display,
                            capabilities = CapabilityInferencer.inferCapabilities(id, display),
                            isManual = false,
                            ownedBy = "anthropic",
                        )
                    }.sortedBy { it.displayName.lowercase() }
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
        val endpointUrl = if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"

        val nonSystemMessages = request.messages.filter { it.role != MessageRole.SYSTEM }
        val systemParts = mutableListOf<String>()
        if (request.systemPrompt.isNotBlank()) systemParts += request.systemPrompt
        request.messages.filter { it.role == MessageRole.SYSTEM && it.content.isNotBlank() }
            .forEach { systemParts += it.content }

        val messagesJson = buildJsonArray {
            for (msg in nonSystemMessages) {
                add(buildMessageJson(msg))
            }
        }

        val payload = buildJsonObject {
            put("model", request.modelId)
            put("max_tokens", request.maxTokens.coerceAtLeast(1))
            put("stream", request.stream)
            if (systemParts.isNotEmpty()) {
                put("system", systemParts.joinToString("\n\n"))
            }
            put("messages", messagesJson)
        }.toString()

        val httpRequest = buildBaseRequest(endpointUrl, config, apiKey)
            .header("Accept", if (request.stream) "text/event-stream" else "application/json")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        val call = okHttpClient.newCall(httpRequest)
        try {
            call.execute().use { response ->
                val requestId = response.header("request-id") ?: ""
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
                                response.code,
                                "Empty response body",
                                config.displayName,
                                request.modelId,
                                endpointUrl,
                                requestId,
                                apiKey,
                            ),
                        ),
                    )
                    return@flow
                }

                val contentType = response.header("Content-Type").orEmpty()
                if (!request.stream || contentType.contains("application/json", ignoreCase = true)) {
                    val raw = body.string()
                    val text = parseNonStreamingContent(raw)
                    emit(StreamEvent.Completed(text))
                } else {
                    val (fullText, fullThinking) = readAnthropicSse(
                        source = body.source(),
                        providerName = config.displayName,
                        modelId = request.modelId,
                        endpointUrl = endpointUrl,
                        requestId = requestId,
                        apiKey = apiKey,
                    ) { deltaText, deltaThinking ->
                        emit(StreamEvent.ContentDelta(deltaText, deltaThinking))
                    }
                    emit(StreamEvent.Completed(fullText, fullThinking))
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
        val role = if (msg.role == MessageRole.ASSISTANT) "assistant" else "user"
        val blocks = buildJsonArray {
            for (att in msg.attachments) {
                when (att.type) {
                    AttachmentType.IMAGE -> {
                        val encoded = attachmentExtractor.encodeImageAsBase64(att.localUri)
                        if (encoded != null) {
                            addJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", encoded.first)
                                    put("data", encoded.second)
                                }
                            }
                        }
                    }
                    AttachmentType.DOCUMENT -> {
                        if (att.mimeType == "application/pdf" && att.sizeBytes in 1..15_000_000L) {
                            val pdfB64 = attachmentExtractor.readBytesBase64(att.localUri)
                            if (!pdfB64.isNullOrBlank()) {
                                addJsonObject {
                                    put("type", "document")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", "application/pdf")
                                        put("data", pdfB64)
                                    }
                                }
                            } else if (att.extractedText.isNotBlank()) {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", "[Document: ${att.displayName}]\n${att.extractedText}")
                                }
                            }
                        } else if (att.extractedText.isNotBlank()) {
                            addJsonObject {
                                put("type", "text")
                                put("text", "[Document: ${att.displayName}]\n${att.extractedText}")
                            }
                        }
                    }
                    AttachmentType.TEXT_FILE -> {
                        addJsonObject {
                            put("type", "text")
                            put("text", "[File: ${att.displayName}]\n${att.extractedText}")
                        }
                    }
                    AttachmentType.WEB_LINK -> {
                        val linkText = buildString {
                            append("[Web Link: ${att.url}]")
                            if (att.pageTitle.isNotBlank()) append("\nTitle: ${att.pageTitle}")
                            if (att.extractedText.isNotBlank()) append("\nExtracted Content:\n${att.extractedText}")
                        }
                        addJsonObject {
                            put("type", "text")
                            put("text", linkText)
                        }
                    }
                }
            }
            if (msg.content.isNotBlank() || msg.attachments.isEmpty()) {
                addJsonObject {
                    put("type", "text")
                    put("text", msg.content)
                }
            }
        }
        return buildJsonObject {
            put("role", role)
            put("content", blocks)
        }
    }

    private suspend fun readAnthropicSse(
        source: BufferedSource,
        providerName: String,
        modelId: String,
        endpointUrl: String,
        requestId: String,
        apiKey: String,
        onDelta: suspend (String, String) -> Unit,
    ): Pair<String, String> {
        val fullText = StringBuilder()
        val fullThinking = StringBuilder()
        var currentEvent = ""
        while (currentCoroutineContext().isActive && !source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.startsWith("event:")) {
                currentEvent = line.removePrefix("event:").trim()
                continue
            }
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isBlank()) continue

            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            val eventType = obj["type"]?.jsonPrimitive?.contentOrNull ?: currentEvent

            when (eventType) {
                "content_block_delta" -> {
                    val delta = obj["delta"]?.jsonObject ?: continue
                    val deltaType = delta["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (deltaType == "thinking_delta") {
                        val thinking = delta["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (thinking.isNotEmpty()) {
                            fullThinking.append(thinking)
                            onDelta("", thinking)
                        }
                    } else {
                        val text = delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (text.isNotEmpty()) {
                            fullText.append(text)
                            onDelta(text, "")
                        }
                    }
                }
                "error" -> {
                    throw AiException(
                        ErrorParser.fromHttpResponse(
                            400,
                            data,
                            providerName,
                            modelId,
                            endpointUrl,
                            requestId,
                            apiKey,
                        ),
                    )
                }
                "message_stop" -> break
            }
        }
        return fullText.toString() to fullThinking.toString()
    }

    private fun parseNonStreamingContent(rawJson: String): String {
        val obj = json.parseToJsonElement(rawJson).jsonObject
        val contentArr = obj["content"]?.jsonArray ?: return ""
        return contentArr.mapNotNull { block ->
            val bObj = block.jsonObject
            if (bObj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                bObj["text"]?.jsonPrimitive?.contentOrNull
            } else {
                null
            }
        }.joinToString("")
    }

    override suspend fun generateImage(
        config: ProviderConfig,
        apiKey: String,
        request: MediaGenerationRequest,
    ): Result<GeneratedMedia> = Result.failure(
        AiException(
            AiError(
                providerName = config.displayName,
                modelId = request.modelId,
                endpointUrl = config.baseUrl,
                errorCode = "UNSUPPORTED_CAPABILITY",
                providerMessage = "Anthropic Messages API does not support native image generation.",
                category = AiErrorCategory.UNSUPPORTED_MODEL,
            ),
        ),
    )

    override suspend fun generateVideo(
        config: ProviderConfig,
        apiKey: String,
        request: MediaGenerationRequest,
    ): Result<GeneratedMedia> = Result.failure(
        AiException(
            AiError(
                providerName = config.displayName,
                modelId = request.modelId,
                endpointUrl = config.baseUrl,
                errorCode = "UNSUPPORTED_CAPABILITY",
                providerMessage = "Anthropic Messages API does not support native video generation.",
                category = AiErrorCategory.UNSUPPORTED_MODEL,
            ),
        ),
    )

    private fun buildBaseRequest(url: String, config: ProviderConfig, apiKey: String): Request.Builder {
        val builder = Request.Builder().url(url)
            .header("anthropic-version", ANTHROPIC_VERSION)
        if (apiKey.isNotBlank()) {
            builder.header("x-api-key", apiKey)
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

    companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
