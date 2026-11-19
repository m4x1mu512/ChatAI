package com.example.data.network

import com.example.core.error.AiError
import com.example.core.model.AiModelInfo
import com.example.core.model.ApiProtocol
import com.example.core.model.Attachment
import com.example.core.model.ChatMessage
import com.example.core.model.GeneratedMedia
import com.example.core.model.ProviderConfig
import kotlinx.coroutines.flow.Flow

data class ConnectionCheckResult(
    val success: Boolean,
    val resolvedProtocol: ApiProtocol,
    val message: String,
    val modelsCount: Int = 0,
    val error: AiError? = null,
)

data class ChatCompletionRequest(
    val modelId: String,
    val messages: List<ChatMessage>,
    val systemPrompt: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    val stream: Boolean = true,
)

data class MediaGenerationRequest(
    val modelId: String,
    val prompt: String,
    val attachments: List<Attachment> = emptyList(),
)

sealed interface StreamEvent {
    data class ContentDelta(val text: String, val reasoningText: String = "") : StreamEvent
    data class Completed(val fullText: String, val fullReasoning: String = "") : StreamEvent
    data class Failure(val error: AiError) : StreamEvent
}

interface AiProvider {
    val protocol: ApiProtocol

    suspend fun testConnection(config: ProviderConfig, apiKey: String): ConnectionCheckResult

    suspend fun fetchModels(config: ProviderConfig, apiKey: String): Result<List<AiModelInfo>>

    fun streamChat(
        config: ProviderConfig,
        apiKey: String,
        request: ChatCompletionRequest,
    ): Flow<StreamEvent>

    suspend fun generateImage(
        config: ProviderConfig,
        apiKey: String,
        request: MediaGenerationRequest,
    ): Result<GeneratedMedia>

    suspend fun generateVideo(
        config: ProviderConfig,
        apiKey: String,
        request: MediaGenerationRequest,
    ): Result<GeneratedMedia>
}
