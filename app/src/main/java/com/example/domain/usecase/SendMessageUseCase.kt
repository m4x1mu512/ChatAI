package com.example.domain.usecase

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.core.error.AiError
import com.example.core.error.AiErrorCategory
import com.example.core.error.ErrorParser
import com.example.core.model.Attachment
import com.example.core.model.AttachmentType
import com.example.core.model.CapabilityInferencer
import com.example.core.model.ChatMessage
import com.example.core.model.MessageRole
import com.example.core.model.MessageStatus
import com.example.core.model.ModelCapability
import com.example.core.model.ProviderConfig
import com.example.data.local.SettingsDataStore
import com.example.data.network.AiProviderRegistry
import com.example.data.network.ChatCompletionRequest
import com.example.data.network.MediaDownloadWorker
import com.example.data.network.MediaGenerationRequest
import com.example.data.network.StreamEvent
import com.example.data.repository.ChatRepository
import com.example.data.repository.ProviderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SendMessageUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    private val providerRegistry: AiProviderRegistry,
    private val settingsDataStore: SettingsDataStore,
) {
    suspend fun validateAttachmentCompatibility(
        provider: ProviderConfig,
        modelId: String,
        attachments: List<Attachment>,
    ): AttachmentType? {
        if (attachments.isEmpty()) return null
        val modelInfo = providerRepository.getModelInfo(provider.id, modelId)
        val caps = modelInfo?.capabilities ?: CapabilityInferencer.inferCapabilities(modelId)

        val hasImage = attachments.any { it.type == AttachmentType.IMAGE }
        if (hasImage && ModelCapability.VISION !in caps && ModelCapability.IMAGE !in caps) {
            return AttachmentType.IMAGE
        }
        return null
    }

    suspend fun sendNewMessage(
        chatId: String,
        provider: ProviderConfig,
        modelId: String,
        text: String,
        attachments: List<Attachment>,
    ) {
        val now = System.currentTimeMillis()
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            chatId = chatId,
            role = MessageRole.USER,
            content = text.trim(),
            status = MessageStatus.COMPLETED,
            providerId = provider.id,
            providerName = provider.displayName,
            modelId = modelId,
            attachments = attachments,
            createdAt = now,
            updatedAt = now,
        )
        chatRepository.upsertMessage(userMsg)

        // Auto-title chat on the first message
        val existingChat = chatRepository.getChat(chatId)
        if (existingChat != null && (existingChat.title == "New Chat" || existingChat.title == "Новый чат")) {
            val autoTitle = buildAutoTitle(text, attachments)
            if (autoTitle.isNotBlank()) {
                chatRepository.renameChat(chatId, autoTitle)
            }
        }
        chatRepository.updateChatModel(chatId, provider.id, provider.displayName, modelId)

        executeAssistantResponse(chatId, provider, modelId)
    }

    suspend fun retryMessage(
        chatId: String,
        failedOrCancelledAssistantMessage: ChatMessage,
        provider: ProviderConfig,
        modelId: String,
    ) {
        chatRepository.deleteMessage(failedOrCancelledAssistantMessage.id)
        executeAssistantResponse(chatId, provider, modelId)
    }

    suspend fun editAndResendUserMessage(
        chatId: String,
        originalUserMessage: ChatMessage,
        updatedText: String,
        provider: ProviderConfig,
        modelId: String,
    ) {
        chatRepository.deleteMessagesAfter(chatId, originalUserMessage.createdAt)
        val updated = originalUserMessage.copy(
            content = updatedText.trim(),
            updatedAt = System.currentTimeMillis(),
        )
        chatRepository.upsertMessage(updated)
        chatRepository.updateChatModel(chatId, provider.id, provider.displayName, modelId)
        executeAssistantResponse(chatId, provider, modelId)
    }

    private suspend fun executeAssistantResponse(
        chatId: String,
        provider: ProviderConfig,
        modelId: String,
    ) {
        val settings = settingsDataStore.settingsFlow.first()
        val apiKey = providerRepository.getApiKey(provider.id)
        val impl = providerRegistry.getProvider(provider)

        val assistantId = UUID.randomUUID().toString()
        val startTs = System.currentTimeMillis()
        var assistantMsg = ChatMessage(
            id = assistantId,
            chatId = chatId,
            role = MessageRole.ASSISTANT,
            content = "",
            status = MessageStatus.SENDING,
            providerId = provider.id,
            providerName = provider.displayName,
            modelId = modelId,
            createdAt = startTs,
            updatedAt = startTs,
        )
        chatRepository.upsertMessage(assistantMsg)

        val history = chatRepository.getMessagesSnapshot(chatId)
            .filter { it.id != assistantId && it.status == MessageStatus.COMPLETED }

        val modelInfo = providerRepository.getModelInfo(provider.id, modelId)
        val caps = modelInfo?.capabilities ?: CapabilityInferencer.inferCapabilities(modelId)

        if (ModelCapability.IMAGE in caps && ModelCapability.CHAT !in caps) {
            val prompt = history.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
            assistantMsg = assistantMsg.copy(status = MessageStatus.GENERATING, updatedAt = System.currentTimeMillis())
            chatRepository.upsertMessage(assistantMsg)

            val result = impl.generateImage(
                provider,
                apiKey,
                MediaGenerationRequest(modelId = modelId, prompt = prompt),
            )
            result.fold(
                onSuccess = { media ->
                    assistantMsg = assistantMsg.copy(
                        status = MessageStatus.COMPLETED,
                        generatedMedia = listOf(media),
                        updatedAt = System.currentTimeMillis(),
                    )
                    chatRepository.upsertMessage(assistantMsg)
                    if (media.localUri.isBlank() && media.remoteUrl.isNotBlank()) {
                        enqueueMediaCacheWorker(assistantId, media.id, media.remoteUrl, "png")
                    }
                },
                onFailure = { t ->
                    val err = ErrorParser.fromThrowable(t, provider.displayName, modelId, provider.baseUrl, apiKey)
                    assistantMsg = assistantMsg.copy(
                        status = MessageStatus.ERROR,
                        error = err,
                        updatedAt = System.currentTimeMillis(),
                    )
                    chatRepository.upsertMessage(assistantMsg)
                },
            )
            return
        }

        if (ModelCapability.VIDEO in caps && ModelCapability.CHAT !in caps) {
            val prompt = history.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
            assistantMsg = assistantMsg.copy(status = MessageStatus.GENERATING, updatedAt = System.currentTimeMillis())
            chatRepository.upsertMessage(assistantMsg)

            val result = impl.generateVideo(
                provider,
                apiKey,
                MediaGenerationRequest(modelId = modelId, prompt = prompt),
            )
            result.fold(
                onSuccess = { media ->
                    assistantMsg = assistantMsg.copy(
                        status = MessageStatus.COMPLETED,
                        generatedMedia = listOf(media),
                        updatedAt = System.currentTimeMillis(),
                    )
                    chatRepository.upsertMessage(assistantMsg)
                    if (media.localUri.isBlank() && media.remoteUrl.isNotBlank()) {
                        enqueueMediaCacheWorker(assistantId, media.id, media.remoteUrl, "mp4")
                    }
                },
                onFailure = { t ->
                    val err = ErrorParser.fromThrowable(t, provider.displayName, modelId, provider.baseUrl, apiKey)
                    assistantMsg = assistantMsg.copy(
                        status = MessageStatus.ERROR,
                        error = err,
                        updatedAt = System.currentTimeMillis(),
                    )
                    chatRepository.upsertMessage(assistantMsg)
                },
            )
            return
        }

        // Standard / Multimodal streaming chat completion
        val textBuffer = StringBuilder()
        val reasoningBuffer = StringBuilder()
        var lastPersistTime = 0L

        try {
            assistantMsg = assistantMsg.copy(status = MessageStatus.GENERATING, updatedAt = System.currentTimeMillis())
            chatRepository.upsertMessage(assistantMsg)

            val req = ChatCompletionRequest(
                modelId = modelId,
                messages = history,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                stream = settings.streamingEnabled,
            )

            impl.streamChat(provider, apiKey, req).collect { event ->
                when (event) {
                    is StreamEvent.ContentDelta -> {
                        textBuffer.append(event.text)
                        reasoningBuffer.append(event.reasoningText)
                        val now = System.currentTimeMillis()
                        if (now - lastPersistTime >= 80L) {
                            lastPersistTime = now
                            assistantMsg = assistantMsg.copy(
                                content = textBuffer.toString(),
                                reasoningContent = reasoningBuffer.toString(),
                                status = MessageStatus.GENERATING,
                                updatedAt = now,
                            )
                            chatRepository.upsertMessage(assistantMsg)
                        }
                    }
                    is StreamEvent.Completed -> {
                        val finalText = event.fullText.ifEmpty { textBuffer.toString() }
                        val finalReasoning = event.fullReasoning.ifEmpty { reasoningBuffer.toString() }
                        assistantMsg = assistantMsg.copy(
                            content = finalText,
                            reasoningContent = finalReasoning,
                            status = MessageStatus.COMPLETED,
                            updatedAt = System.currentTimeMillis(),
                        )
                        chatRepository.upsertMessage(assistantMsg)
                    }
                    is StreamEvent.Failure -> {
                        assistantMsg = assistantMsg.copy(
                            content = textBuffer.toString(),
                            reasoningContent = reasoningBuffer.toString(),
                            status = MessageStatus.ERROR,
                            error = event.error,
                            updatedAt = System.currentTimeMillis(),
                        )
                        chatRepository.upsertMessage(assistantMsg)
                    }
                }
            }
        } catch (ce: CancellationException) {
            assistantMsg = assistantMsg.copy(
                content = textBuffer.toString(),
                reasoningContent = reasoningBuffer.toString(),
                status = MessageStatus.CANCELLED,
                updatedAt = System.currentTimeMillis(),
            )
            chatRepository.upsertMessage(assistantMsg)
            throw ce
        } catch (t: Throwable) {
            val err = ErrorParser.fromThrowable(t, provider.displayName, modelId, provider.baseUrl, apiKey)
            assistantMsg = assistantMsg.copy(
                content = textBuffer.toString(),
                status = MessageStatus.ERROR,
                error = err,
                updatedAt = System.currentTimeMillis(),
            )
            chatRepository.upsertMessage(assistantMsg)
        }
    }

    private fun enqueueMediaCacheWorker(
        messageId: String,
        mediaId: String,
        remoteUrl: String,
        extension: String,
    ) {
        val data = Data.Builder()
            .putString(MediaDownloadWorker.KEY_MESSAGE_ID, messageId)
            .putString(MediaDownloadWorker.KEY_MEDIA_ID, mediaId)
            .putString(MediaDownloadWorker.KEY_REMOTE_URL, remoteUrl)
            .putString(MediaDownloadWorker.KEY_EXTENSION, extension)
            .build()

        val req = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        runCatching { WorkManager.getInstance(context).enqueue(req) }
    }

    private fun buildAutoTitle(text: String, attachments: List<Attachment>): String {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.isNotEmpty()) {
            return if (clean.length <= 42) clean else clean.take(40).trimEnd() + "…"
        }
        val firstAtt = attachments.firstOrNull() ?: return ""
        return firstAtt.displayName.take(40)
    }
}
