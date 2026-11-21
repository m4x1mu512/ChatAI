package com.example.domain.usecase

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.core.error.ErrorParser
import com.example.core.model.Attachment
import com.example.core.model.AttachmentType
import com.example.core.model.CapabilityInferencer
import com.example.core.model.ChatMessage
import com.example.core.model.GitHubIntegrationSettings
import com.example.core.model.GitHubPendingApproval
import com.example.core.model.MessageRole
import com.example.core.model.MessageStatus
import com.example.core.model.ModelCapability
import com.example.core.model.ProviderConfig
import com.example.data.github.GitHubRepository
import com.example.data.github.GitHubToolExecutor
import com.example.data.local.SettingsDataStore
import com.example.data.network.AiProvider
import com.example.data.network.AiProviderRegistry
import com.example.data.network.ChatCompletionRequest
import com.example.data.network.ConversationItem
import com.example.data.network.MediaDownloadWorker
import com.example.data.network.MediaGenerationRequest
import com.example.data.network.StreamEvent
import com.example.data.network.ToolCallRequest
import com.example.data.repository.ChatRepository
import com.example.data.repository.ProviderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    private val gitHubRepository: GitHubRepository,
    private val gitHubToolExecutor: GitHubToolExecutor,
) {
    private val pendingDecisions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val _pendingApprovals = MutableStateFlow<Map<String, GitHubPendingApproval>>(emptyMap())
    val pendingApprovals: StateFlow<Map<String, GitHubPendingApproval>> = _pendingApprovals.asStateFlow()

    fun approvePendingGitHubAction(chatId: String) {
        pendingDecisions.remove(chatId)?.complete(true)
        _pendingApprovals.update { it - chatId }
    }

    fun rejectPendingGitHubAction(chatId: String) {
        pendingDecisions.remove(chatId)?.complete(false)
        _pendingApprovals.update { it - chatId }
    }

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
        val ghSettings = gitHubRepository.getSettings()
        val chatSession = chatRepository.getChat(chatId)
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

        val gitHubToolsActive = ghSettings.isConnected &&
            ghSettings.toolsEnabled &&
            (chatSession?.githubToolsEnabled ?: true) &&
            gitHubRepository.hasToken()

        val activeRepoFullName = chatSession?.githubRepoFullName?.takeIf { it.isNotBlank() }
            ?: ghSettings.defaultRepoFullName.takeIf { it.isNotBlank() }
        val activeBranch = chatSession?.githubBranch?.takeIf { it.isNotBlank() }

        val textBuffer = StringBuilder()
        val reasoningBuffer = StringBuilder()
        val toolActionTrace = StringBuilder()

        try {
            assistantMsg = assistantMsg.copy(status = MessageStatus.GENERATING, updatedAt = System.currentTimeMillis())
            chatRepository.upsertMessage(assistantMsg)

            if (!gitHubToolsActive) {
                val req = ChatCompletionRequest(
                    modelId = modelId,
                    messages = history,
                    temperature = settings.temperature,
                    maxTokens = settings.maxTokens,
                    stream = settings.streamingEnabled,
                )
                runSingleCompletion(
                    impl = impl,
                    provider = provider,
                    apiKey = apiKey,
                    request = req,
                    initialAssistantMsg = assistantMsg,
                    textBuffer = textBuffer,
                    reasoningBuffer = reasoningBuffer,
                )
            } else {
                runAgenticGitHubLoop(
                    chatId = chatId,
                    impl = impl,
                    provider = provider,
                    apiKey = apiKey,
                    modelId = modelId,
                    history = history,
                    ghSettings = ghSettings,
                    activeRepoFullName = activeRepoFullName,
                    activeBranch = activeBranch,
                    temperature = settings.temperature,
                    maxTokens = settings.maxTokens,
                    streaming = settings.streamingEnabled,
                    initialAssistantMsg = assistantMsg,
                    textBuffer = textBuffer,
                    reasoningBuffer = reasoningBuffer,
                    toolActionTrace = toolActionTrace,
                )
            }
        } catch (ce: CancellationException) {
            pendingDecisions.remove(chatId)?.cancel(ce)
            _pendingApprovals.update { it - chatId }
            assistantMsg = assistantMsg.copy(
                content = textBuffer.toString(),
                reasoningContent = combineReasoning(toolActionTrace, reasoningBuffer),
                status = MessageStatus.CANCELLED,
                updatedAt = System.currentTimeMillis(),
            )
            chatRepository.upsertMessage(assistantMsg)
            throw ce
        } catch (t: Throwable) {
            pendingDecisions.remove(chatId)?.cancel()
            _pendingApprovals.update { it - chatId }
            val err = ErrorParser.fromThrowable(t, provider.displayName, modelId, provider.baseUrl, apiKey)
            assistantMsg = assistantMsg.copy(
                content = textBuffer.toString(),
                reasoningContent = combineReasoning(toolActionTrace, reasoningBuffer),
                status = MessageStatus.ERROR,
                error = err,
                updatedAt = System.currentTimeMillis(),
            )
            chatRepository.upsertMessage(assistantMsg)
        }
    }

    private suspend fun runSingleCompletion(
        impl: AiProvider,
        provider: ProviderConfig,
        apiKey: String,
        request: ChatCompletionRequest,
        initialAssistantMsg: ChatMessage,
        textBuffer: StringBuilder,
        reasoningBuffer: StringBuilder,
    ) {
        var assistantMsg = initialAssistantMsg
        var lastPersistTime = 0L

        impl.streamChat(provider, apiKey, request).collect { event ->
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
                is StreamEvent.ToolCallsRequested -> {
                    val fallbackText = event.partialText.ifEmpty { textBuffer.toString() }
                    assistantMsg = assistantMsg.copy(
                        content = fallbackText,
                        reasoningContent = event.partialReasoning.ifEmpty { reasoningBuffer.toString() },
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
    }

    private suspend fun runAgenticGitHubLoop(
        chatId: String,
        impl: AiProvider,
        provider: ProviderConfig,
        apiKey: String,
        modelId: String,
        history: List<ChatMessage>,
        ghSettings: GitHubIntegrationSettings,
        activeRepoFullName: String?,
        activeBranch: String?,
        temperature: Float,
        maxTokens: Int,
        streaming: Boolean,
        initialAssistantMsg: ChatMessage,
        textBuffer: StringBuilder,
        reasoningBuffer: StringBuilder,
        toolActionTrace: StringBuilder,
    ) {
        var assistantMsg = initialAssistantMsg
        val conversation: MutableList<ConversationItem> = history
            .map { ConversationItem.StandardMessage(it) }
            .toMutableList()
        var useNativeTools = true

        for (round in 1..MAX_TOOL_ROUNDS) {
            val roundText = StringBuilder()
            val roundReasoning = StringBuilder()
            var lastPersistTime = 0L
            var pendingToolCalls: List<ToolCallRequest>? = null
            var roundError: com.example.core.error.AiError? = null

            val sysPrompt = gitHubToolExecutor.buildSystemPrompt(
                settings = ghSettings,
                activeRepoFullName = activeRepoFullName,
                activeBranch = activeBranch,
                includeTextFallbackInstructions = !useNativeTools,
            )

            val request = ChatCompletionRequest(
                modelId = modelId,
                messages = history,
                systemPrompt = sysPrompt,
                temperature = temperature,
                maxTokens = maxTokens,
                stream = streaming,
                tools = if (useNativeTools) gitHubToolExecutor.toolDefinitions else emptyList(),
                toolConversationHistory = conversation.toList(),
            )

            impl.streamChat(provider, apiKey, request).collect { event ->
                when (event) {
                    is StreamEvent.ContentDelta -> {
                        roundText.append(event.text)
                        roundReasoning.append(event.reasoningText)
                        val now = System.currentTimeMillis()
                        if (now - lastPersistTime >= 80L) {
                            lastPersistTime = now
                            val combinedText = buildRoundDisplayText(textBuffer, roundText)
                            val combinedReasoning = combineReasoning(toolActionTrace, reasoningBuffer, roundReasoning)
                            assistantMsg = assistantMsg.copy(
                                content = combinedText,
                                reasoningContent = combinedReasoning,
                                status = MessageStatus.GENERATING,
                                updatedAt = now,
                            )
                            chatRepository.upsertMessage(assistantMsg)
                        }
                    }
                    is StreamEvent.ToolCallsRequested -> {
                        val finalRoundText = event.partialText.ifEmpty { roundText.toString() }
                        val finalRoundReasoning = event.partialReasoning.ifEmpty { roundReasoning.toString() }
                        if (finalRoundText.isNotEmpty()) {
                            roundText.clear()
                            roundText.append(finalRoundText)
                        }
                        if (finalRoundReasoning.isNotEmpty()) {
                            roundReasoning.clear()
                            roundReasoning.append(finalRoundReasoning)
                        }
                        pendingToolCalls = event.calls
                    }
                    is StreamEvent.Completed -> {
                        val finalRoundText = event.fullText.ifEmpty { roundText.toString() }
                        val finalRoundReasoning = event.fullReasoning.ifEmpty { roundReasoning.toString() }
                        roundText.clear()
                        roundText.append(finalRoundText)
                        roundReasoning.clear()
                        roundReasoning.append(finalRoundReasoning)

                        val fallbackCalls = gitHubToolExecutor.parseFallbackToolCalls(finalRoundText)
                        if (fallbackCalls.isNotEmpty()) {
                            pendingToolCalls = fallbackCalls
                        }
                    }
                    is StreamEvent.Failure -> {
                        roundError = event.error
                    }
                }
            }

            val err = roundError
            if (err != null) {
                val msgLower = "${err.errorCode} ${err.providerMessage} ${err.technicalDetails}".lowercase()
                val toolsUnsupported = useNativeTools &&
                    (err.httpStatus == 400 || err.httpStatus == 422 || err.httpStatus == 404) &&
                    (msgLower.contains("tool") || msgLower.contains("function") || msgLower.contains("unsupported"))
                if (toolsUnsupported) {
                    useNativeTools = false
                    toolActionTrace.appendLine("GitHub: switching to text tool mode for this model.")
                    continue
                }
                assistantMsg = assistantMsg.copy(
                    content = textBuffer.toString(),
                    reasoningContent = combineReasoning(toolActionTrace, reasoningBuffer),
                    status = MessageStatus.ERROR,
                    error = err,
                    updatedAt = System.currentTimeMillis(),
                )
                chatRepository.upsertMessage(assistantMsg)
                return
            }

            if (roundReasoning.isNotEmpty()) {
                if (reasoningBuffer.isNotEmpty()) reasoningBuffer.append("\n")
                reasoningBuffer.append(roundReasoning)
            }

            val calls = pendingToolCalls
            if (calls.isNullOrEmpty()) {
                if (roundText.isNotEmpty()) {
                    if (textBuffer.isNotEmpty()) textBuffer.append("\n\n")
                    textBuffer.append(roundText)
                }
                assistantMsg = assistantMsg.copy(
                    content = textBuffer.toString(),
                    reasoningContent = combineReasoning(toolActionTrace, reasoningBuffer),
                    status = MessageStatus.COMPLETED,
                    updatedAt = System.currentTimeMillis(),
                )
                chatRepository.upsertMessage(assistantMsg)
                return
            }

            val cleanedRoundText = gitHubToolExecutor.stripFallbackToolTags(roundText.toString())
            if (cleanedRoundText.isNotBlank()) {
                if (textBuffer.isNotEmpty()) textBuffer.append("\n\n")
                textBuffer.append(cleanedRoundText)
            }

            val readCalls = calls.filter { !gitHubToolExecutor.isWriteTool(it.name) }
            val writeCalls = calls.filter { gitHubToolExecutor.isWriteTool(it.name) }

            val resultsById = LinkedHashMap<String, com.example.data.github.ToolExecutionResult>()

            for (rc in readCalls) {
                val res = gitHubToolExecutor.execute(rc, activeRepoFullName, activeBranch)
                resultsById[rc.id] = res
                toolActionTrace.appendLine("• GitHub: ${res.humanSummary}")
            }

            if (writeCalls.isNotEmpty()) {
                val approved = if (!ghSettings.confirmWritesBeforeExecute) {
                    true
                } else {
                    toolActionTrace.appendLine("• GitHub: awaiting confirmation for ${writeCalls.size} write action(s)…")
                    assistantMsg = assistantMsg.copy(
                        content = textBuffer.toString(),
                        reasoningContent = combineReasoning(toolActionTrace, reasoningBuffer),
                        status = MessageStatus.GENERATING,
                        updatedAt = System.currentTimeMillis(),
                    )
                    chatRepository.upsertMessage(assistantMsg)

                    awaitWriteApproval(
                        chatId = chatId,
                        activeRepoFullName = activeRepoFullName ?: "GitHub",
                        activeBranch = activeBranch,
                        writeCalls = writeCalls,
                    )
                }

                for (wc in writeCalls) {
                    if (approved) {
                        val res = gitHubToolExecutor.execute(wc, activeRepoFullName, activeBranch)
                        resultsById[wc.id] = res
                        toolActionTrace.appendLine("✓ GitHub: ${res.humanSummary}")
                    } else {
                        resultsById[wc.id] = com.example.data.github.ToolExecutionResult(
                            toolCallId = wc.id,
                            toolName = wc.name,
                            content = """{"status":"rejected_by_user","message":"User declined this repository modification."}""",
                            isError = false,
                            humanSummary = "Declined ${wc.name}",
                        )
                        toolActionTrace.appendLine("✕ GitHub: Declined ${wc.name}")
                    }
                }
            }

            assistantMsg = assistantMsg.copy(
                content = textBuffer.toString(),
                reasoningContent = combineReasoning(toolActionTrace, reasoningBuffer),
                status = MessageStatus.GENERATING,
                updatedAt = System.currentTimeMillis(),
            )
            chatRepository.upsertMessage(assistantMsg)

            if (useNativeTools) {
                conversation += ConversationItem.AssistantToolCallTurn(
                    text = cleanedRoundText,
                    reasoningText = roundReasoning.toString(),
                    toolCalls = calls,
                )
                for (call in calls) {
                    val res = resultsById[call.id] ?: continue
                    conversation += ConversationItem.ToolResultTurn(
                        toolCallId = call.id,
                        toolName = call.name,
                        content = res.content,
                        isError = res.isError,
                    )
                }
            } else {
                val summaryBlock = calls.mapNotNull { resultsById[it.id] }
                    .joinToString("\n\n") { res ->
                        "[GitHub Tool Result: ${res.toolName}]\n${res.content}"
                    }
                conversation += ConversationItem.StandardMessage(
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        chatId = chatId,
                        role = MessageRole.ASSISTANT,
                        content = roundText.toString(),
                    ),
                )
                conversation += ConversationItem.StandardMessage(
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        chatId = chatId,
                        role = MessageRole.USER,
                        content = summaryBlock,
                    ),
                )
            }
        }

        assistantMsg = assistantMsg.copy(
            content = textBuffer.toString().ifBlank { "GitHub tool execution reached step limit ($MAX_TOOL_ROUNDS rounds)." },
            reasoningContent = combineReasoning(toolActionTrace, reasoningBuffer),
            status = MessageStatus.COMPLETED,
            updatedAt = System.currentTimeMillis(),
        )
        chatRepository.upsertMessage(assistantMsg)
    }

    private suspend fun awaitWriteApproval(
        chatId: String,
        activeRepoFullName: String,
        activeBranch: String?,
        writeCalls: List<ToolCallRequest>,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingDecisions.put(chatId, deferred)?.cancel()
        val actions = writeCalls.map { call ->
            gitHubToolExecutor.describePendingAction(call, activeRepoFullName, activeBranch)
        }
        _pendingApprovals.update { current ->
            current + (chatId to GitHubPendingApproval(chatId, activeRepoFullName, actions))
        }
        return try {
            deferred.await()
        } finally {
            pendingDecisions.remove(chatId)
            _pendingApprovals.update { it - chatId }
        }
    }

    private fun buildRoundDisplayText(textBuffer: StringBuilder, roundText: StringBuilder): String {
        val prev = textBuffer.toString()
        val curr = gitHubToolExecutor.stripFallbackToolTags(roundText.toString())
        return when {
            prev.isBlank() -> curr
            curr.isBlank() -> prev
            else -> "$prev\n\n$curr"
        }
    }

    private fun combineReasoning(
        toolActionTrace: StringBuilder,
        reasoningBuffer: StringBuilder,
        roundReasoning: StringBuilder? = null,
    ): String {
        val parts = buildList {
            val trace = toolActionTrace.toString().trim()
            if (trace.isNotEmpty()) add(trace)
            val baseReason = reasoningBuffer.toString().trim()
            if (baseReason.isNotEmpty()) add(baseReason)
            val roundReason = roundReasoning?.toString()?.trim().orEmpty()
            if (roundReason.isNotEmpty()) add(roundReason)
        }
        return parts.joinToString("\n\n")
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

    companion object {
        private const val MAX_TOOL_ROUNDS = 10
    }
}
