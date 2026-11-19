package com.example.ui.screens.chat

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.AiModelInfo
import com.example.core.model.Attachment
import com.example.core.model.AttachmentType
import com.example.core.model.ChatMessage
import com.example.core.model.ChatSession
import com.example.core.model.GeneratedMedia
import com.example.core.model.MessageStatus
import com.example.core.model.ProviderConfig
import com.example.core.model.ProviderStatus
import com.example.core.util.AttachmentExtractor
import com.example.core.util.MediaStoreSaver
import com.example.core.util.UrlDetector
import com.example.data.local.SettingsDataStore
import com.example.data.repository.ChatRepository
import com.example.data.repository.ProviderRepository
import com.example.domain.usecase.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class ChatUiState(
    val chat: ChatSession? = null,
    val messages: List<ChatMessage> = emptyList(),
    val providers: List<ProviderConfig> = emptyList(),
    val models: List<AiModelInfo> = emptyList(),
    val activeProvider: ProviderConfig? = null,
    val activeModelId: String? = null,
    val inputText: String = "",
    val stagedAttachments: List<Attachment> = emptyList(),
    val detectedUrls: List<String> = emptyList(),
    val unsupportedAttachmentWarning: AttachmentType? = null,
    val isGenerating: Boolean = false,
    val editingMessage: ChatMessage? = null,
    val isLoadingAttachment: Boolean = false,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    private val sendMessageUseCase: SendMessageUseCase,
    private val attachmentExtractor: AttachmentExtractor,
    private val mediaStoreSaver: MediaStoreSaver,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    private val activeChatId = MutableStateFlow<String?>(null)
    private val inputText = MutableStateFlow("")
    private val stagedAttachments = MutableStateFlow<List<Attachment>>(emptyList())
    private val editingMessage = MutableStateFlow<ChatMessage?>(null)
    private val isLoadingAttachment = MutableStateFlow(false)
    private val unsupportedWarning = MutableStateFlow<AttachmentType?>(null)

    private var activeGenerationJob: Job? = null
    private var pendingCameraFile: File? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val chatFlow = activeChatId.flatMapLatest { id ->
        if (id == null) flowOf(null) else chatRepository.observeChat(id)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val messagesFlow = activeChatId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else chatRepository.observeMessages(id)
    }

    val uiState: StateFlow<ChatUiState> = combine(
        chatFlow,
        messagesFlow,
        providerRepository.observeProviders(),
        providerRepository.observeAllModels(),
        combine(inputText, stagedAttachments, editingMessage, isLoadingAttachment, unsupportedWarning) { a, b, c, d, e ->
            arrayOf(a, b, c, d, e)
        },
    ) { chat, messages, providers, models, extras ->
        val text = extras[0] as String
        @Suppress("UNCHECKED_CAST")
        val attachments = extras[1] as List<Attachment>
        val editing = extras[2] as ChatMessage?
        val loadingAtt = extras[3] as Boolean
        val warning = extras[4] as AttachmentType?

        val enabledProviders = providers.filter { it.isEnabled }
        val activeProv = enabledProviders.firstOrNull { it.id == chat?.providerId }
            ?: enabledProviders.firstOrNull { it.status == ProviderStatus.CONNECTED }
            ?: enabledProviders.firstOrNull()

        val activeModel = chat?.modelId?.takeIf { !it.isNullOrBlank() }
            ?: activeProv?.selectedModelId
            ?: models.firstOrNull { it.providerId == activeProv?.id }?.modelId

        val detected = UrlDetector.extractUrls(text)
            .filter { url -> attachments.none { it.type == AttachmentType.WEB_LINK && it.url == url } }

        val generating = messages.any {
            it.status == MessageStatus.SENDING || it.status == MessageStatus.GENERATING
        }

        ChatUiState(
            chat = chat,
            messages = messages,
            providers = providers,
            models = models,
            activeProvider = activeProv,
            activeModelId = activeModel,
            inputText = text,
            stagedAttachments = attachments,
            detectedUrls = detected,
            unsupportedAttachmentWarning = warning,
            isGenerating = generating,
            editingMessage = editing,
            isLoadingAttachment = loadingAtt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    fun loadChat(chatId: String) {
        activeChatId.value = chatId
    }

    fun updateInputText(text: String) {
        inputText.value = text
    }

    fun selectModel(provider: ProviderConfig, model: AiModelInfo) {
        viewModelScope.launch {
            providerRepository.setSelectedModel(provider.id, model.modelId)
            val chatId = activeChatId.value ?: return@launch
            chatRepository.updateChatModel(chatId, provider.id, provider.displayName, model.modelId)
            validateStagedAttachments(provider, model.modelId)
        }
    }

    fun addManualModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            providerRepository.addManualModel(providerId, modelId)
            val provider = providerRepository.getProvider(providerId) ?: return@launch
            val chatId = activeChatId.value ?: return@launch
            chatRepository.updateChatModel(chatId, provider.id, provider.displayName, modelId)
        }
    }

    fun attachImageFromUri(uri: Uri) {
        viewModelScope.launch {
            isLoadingAttachment.value = true
            runCatching { attachmentExtractor.createImageAttachment(uri) }
                .onSuccess { att ->
                    stagedAttachments.value = stagedAttachments.value + att
                    revalidateCurrentAttachments()
                }
            isLoadingAttachment.value = false
        }
    }

    fun prepareCameraOutputUri(): Uri {
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val file = File(dir, "cam_${UUID.randomUUID()}.jpg")
        pendingCameraFile = file
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun onCameraCaptured(success: Boolean) {
        val file = pendingCameraFile
        pendingCameraFile = null
        if (!success || file == null || !file.exists()) return
        attachImageFromUri(Uri.fromFile(file))
    }

    fun attachDocumentFromUri(uri: Uri) {
        viewModelScope.launch {
            isLoadingAttachment.value = true
            runCatching { attachmentExtractor.createDocumentAttachment(uri) }
                .onSuccess { att ->
                    stagedAttachments.value = stagedAttachments.value + att
                    revalidateCurrentAttachments()
                }
            isLoadingAttachment.value = false
        }
    }

    fun attachWebLink(url: String) {
        viewModelScope.launch {
            isLoadingAttachment.value = true
            val settings = settingsDataStore.settingsFlow.first()
            val att = attachmentExtractor.createWebLinkAttachment(
                url = url,
                fetchContentLocally = settings.fetchWebLinksLocally,
            )
            stagedAttachments.value = stagedAttachments.value + att
            isLoadingAttachment.value = false
        }
    }

    fun removeStagedAttachment(attachmentId: String) {
        stagedAttachments.value = stagedAttachments.value.filterNot { it.id == attachmentId }
        viewModelScope.launch { revalidateCurrentAttachments() }
    }

    private suspend fun revalidateCurrentAttachments() {
        val state = uiState.value
        val provider = state.activeProvider
        val modelId = state.activeModelId
        if (provider == null || modelId.isNullOrBlank()) {
            unsupportedWarning.value = null
            return
        }
        validateStagedAttachments(provider, modelId)
    }

    private suspend fun validateStagedAttachments(provider: ProviderConfig, modelId: String) {
        unsupportedWarning.value = sendMessageUseCase.validateAttachmentCompatibility(
            provider = provider,
            modelId = modelId,
            attachments = stagedAttachments.value,
        )
    }

    fun sendMessage() {
        val state = uiState.value
        val chatId = activeChatId.value ?: return
        val provider = state.activeProvider ?: return
        val modelId = state.activeModelId ?: return
        val text = state.inputText.trim()
        val attachments = state.stagedAttachments
        if (text.isEmpty() && attachments.isEmpty()) return

        val editing = state.editingMessage
        inputText.value = ""
        stagedAttachments.value = emptyList()
        editingMessage.value = null
        unsupportedWarning.value = null

        activeGenerationJob?.cancel()
        activeGenerationJob = viewModelScope.launch {
            if (editing != null) {
                sendMessageUseCase.editAndResendUserMessage(
                    chatId = chatId,
                    originalUserMessage = editing,
                    updatedText = text,
                    provider = provider,
                    modelId = modelId,
                )
            } else {
                sendMessageUseCase.sendNewMessage(
                    chatId = chatId,
                    provider = provider,
                    modelId = modelId,
                    text = text,
                    attachments = attachments,
                )
            }
        }
    }

    fun stopGeneration() {
        activeGenerationJob?.cancel()
        activeGenerationJob = null
    }

    fun retryAssistantMessage(assistantMessage: ChatMessage) {
        val state = uiState.value
        val chatId = activeChatId.value ?: return
        val provider = state.activeProvider ?: return
        val modelId = state.activeModelId ?: return
        activeGenerationJob?.cancel()
        activeGenerationJob = viewModelScope.launch {
            sendMessageUseCase.retryMessage(chatId, assistantMessage, provider, modelId)
        }
    }

    fun startEditUserMessage(userMessage: ChatMessage) {
        editingMessage.value = userMessage
        inputText.value = userMessage.content
    }

    fun cancelEditMessage() {
        editingMessage.value = null
        inputText.value = ""
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            chatRepository.deleteMessage(messageId)
        }
    }

    fun saveMediaToGallery(media: GeneratedMedia, onResult: (Result<Uri>) -> Unit) {
        viewModelScope.launch {
            val source = media.localUri.ifBlank { media.remoteUrl }
            val result = mediaStoreSaver.saveToGallery(source, media.type)
            onResult(result)
        }
    }

    fun createShareMediaIntent(media: GeneratedMedia, title: String) =
        mediaStoreSaver.createShareIntent(
            localUriString = media.localUri.ifBlank { media.remoteUrl },
            mimeType = media.mimeType,
            title = title,
        )

    fun createOpenDocumentIntent(attachment: Attachment) =
        mediaStoreSaver.createOpenFileIntent(
            localUriString = attachment.localUri,
            mimeType = attachment.mimeType,
        )
}
