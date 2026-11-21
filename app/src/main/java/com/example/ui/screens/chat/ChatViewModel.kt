package com.example.ui.screens.chat

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.error.AiError
import com.example.core.error.ErrorParser
import com.example.core.model.AiModelInfo
import com.example.core.model.Attachment
import com.example.core.model.AttachmentType
import com.example.core.model.ChatMessage
import com.example.core.model.ChatSession
import com.example.core.model.GeneratedMedia
import com.example.core.model.GitHubBranchInfo
import com.example.core.model.GitHubIntegrationSettings
import com.example.core.model.GitHubPendingApproval
import com.example.core.model.GitHubRepoSummary
import com.example.core.model.MessageStatus
import com.example.core.model.ProviderConfig
import com.example.core.model.ProviderStatus
import com.example.core.util.AttachmentExtractor
import com.example.core.util.MediaStoreSaver
import com.example.core.util.UrlDetector
import com.example.data.github.GitHubRepository
import com.example.data.github.GitHubValidator
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

private data class ChatGitHubPickerState(
    val repos: List<GitHubRepoSummary> = emptyList(),
    val branches: List<GitHubBranchInfo> = emptyList(),
    val isLoading: Boolean = false,
)

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
    val refreshingProviderIds: Set<String> = emptySet(),
    val modelLoadErrors: Map<String, AiError> = emptyMap(),
    val gitHubSettings: GitHubIntegrationSettings = GitHubIntegrationSettings(),
    val gitHubRepos: List<GitHubRepoSummary> = emptyList(),
    val gitHubBranches: List<GitHubBranchInfo> = emptyList(),
    val isLoadingGitHubRepos: Boolean = false,
    val pendingGitHubApproval: GitHubPendingApproval? = null,
) {
    val effectiveGitHubRepo: String?
        get() = chat?.githubRepoFullName?.takeIf { it.isNotBlank() }
            ?: gitHubSettings.defaultRepoFullName.takeIf { it.isNotBlank() }

    val effectiveGitHubBranch: String?
        get() = chat?.githubBranch?.takeIf { it.isNotBlank() }

    val isGitHubToolsActiveInChat: Boolean
        get() = gitHubSettings.isConnected &&
            gitHubSettings.toolsEnabled &&
            (chat?.githubToolsEnabled ?: true)
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    private val sendMessageUseCase: SendMessageUseCase,
    private val attachmentExtractor: AttachmentExtractor,
    private val mediaStoreSaver: MediaStoreSaver,
    private val settingsDataStore: SettingsDataStore,
    private val gitHubRepository: GitHubRepository,
) : ViewModel() {

    private val activeChatId = MutableStateFlow<String?>(null)
    private val inputText = MutableStateFlow("")
    private val stagedAttachments = MutableStateFlow<List<Attachment>>(emptyList())
    private val editingMessage = MutableStateFlow<ChatMessage?>(null)
    private val isLoadingAttachment = MutableStateFlow(false)
    private val unsupportedWarning = MutableStateFlow<AttachmentType?>(null)
    private val refreshingProviderIds = MutableStateFlow<Set<String>>(emptySet())
    private val modelLoadErrors = MutableStateFlow<Map<String, AiError>>(emptyMap())
    private val gitHubPickerState = MutableStateFlow(ChatGitHubPickerState())

    private var activeGenerationJob: Job? = null
    private var pendingCameraFile: File? = null
    private val attemptedSyncSignatures = mutableMapOf<String, String>()

    init {
        viewModelScope.launch {
            combine(
                providerRepository.observeProviders(),
                providerRepository.observeEnabledModels(),
            ) { providers, models -> providers to models }.collect { (providers, models) ->
                val enabled = providers.filter { it.isEnabled }
                val activeIds = enabled.map { it.id }.toSet()
                attemptedSyncSignatures.keys.retainAll(activeIds)
                modelLoadErrors.value = modelLoadErrors.value.filterKeys { it in activeIds }
                val needsAutoSync = enabled.any { p ->
                    val hasModels = models.any { it.providerId == p.id }
                    val sig = providerSyncSignature(p)
                    !hasModels &&
                        (p.hasApiKey || !p.type.requiresApiKey || p.status == ProviderStatus.CONNECTED || p.customHeaders.isNotEmpty()) &&
                        attemptedSyncSignatures[p.id] != sig
                }
                if (needsAutoSync) {
                    syncMissingModelsInternal(forceRefresh = false)
                }
            }
        }
    }

    private fun providerSyncSignature(p: ProviderConfig): String =
        "${p.baseUrl}|${p.hasApiKey}|${p.protocol}|${p.isEnabled}|${p.customHeaders.size}"

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val chatFlow = activeChatId.flatMapLatest { id ->
        if (id == null) flowOf(null) else chatRepository.observeChat(id)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val messagesFlow = activeChatId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else chatRepository.observeMessages(id)
    }

    private val extrasFlow = combine(
        combine(inputText, stagedAttachments, editingMessage, isLoadingAttachment) { a, b, c, d ->
            arrayOf(a, b, c, d)
        },
        combine(
            unsupportedWarning,
            refreshingProviderIds,
            modelLoadErrors,
            gitHubRepository.settingsFlow,
            gitHubPickerState,
        ) { warning, refreshing, loadErrors, ghSettings, ghPicker ->
            arrayOf(warning, refreshing, loadErrors, ghSettings, ghPicker)
        },
        sendMessageUseCase.pendingApprovals,
    ) { coreExtras, auxExtras, approvals ->
        Triple(coreExtras, auxExtras, approvals)
    }

    val uiState: StateFlow<ChatUiState> = combine(
        chatFlow,
        messagesFlow,
        providerRepository.observeProviders(),
        providerRepository.observeEnabledModels(),
        extrasFlow,
    ) { chat, messages, providers, models, bundledExtras ->
        val (extras, auxExtras, approvalsMap) = bundledExtras
        val text = extras[0] as String
        @Suppress("UNCHECKED_CAST")
        val attachments = extras[1] as List<Attachment>
        val editing = extras[2] as ChatMessage?
        val loadingAtt = extras[3] as Boolean

        val warning = auxExtras[0] as AttachmentType?
        @Suppress("UNCHECKED_CAST")
        val refreshing = auxExtras[1] as Set<String>
        @Suppress("UNCHECKED_CAST")
        val loadErrors = auxExtras[2] as Map<String, AiError>
        val ghSettings = auxExtras[3] as GitHubIntegrationSettings
        val ghPicker = auxExtras[4] as ChatGitHubPickerState

        val enabledProviders = providers.filter { it.isEnabled }
        val activeProv = enabledProviders.firstOrNull { it.id == chat?.providerId }
            ?: enabledProviders.firstOrNull { p -> models.any { it.providerId == p.id } }
            ?: enabledProviders.firstOrNull { it.status == ProviderStatus.CONNECTED }
            ?: enabledProviders.firstOrNull { it.hasApiKey || !it.type.requiresApiKey }
            ?: enabledProviders.firstOrNull()

        val providerModels = models.filter { it.providerId == activeProv?.id }
        val chatModelMatchesProvider = chat?.providerId == null || chat.providerId == activeProv?.id
        val activeModel = chat?.modelId?.takeIf { !it.isNullOrBlank() && chatModelMatchesProvider }
            ?: activeProv?.selectedModelId?.takeIf { selected ->
                providerModels.isEmpty() || providerModels.any { it.modelId == selected }
            }
            ?: providerModels.firstOrNull()?.modelId

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
            refreshingProviderIds = refreshing,
            modelLoadErrors = loadErrors,
            gitHubSettings = ghSettings,
            gitHubRepos = ghPicker.repos,
            gitHubBranches = ghPicker.branches,
            isLoadingGitHubRepos = ghPicker.isLoading,
            pendingGitHubApproval = chat?.id?.let { approvalsMap[it] },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    fun loadChat(chatId: String) {
        activeChatId.value = chatId
        syncConnectedProviderModels()
    }

    fun syncConnectedProviderModels() {
        viewModelScope.launch {
            syncMissingModelsInternal(forceRefresh = false)
        }
    }

    fun refreshAllProviderModels() {
        viewModelScope.launch {
            syncMissingModelsInternal(forceRefresh = true)
        }
    }

    fun refreshProviderModels(providerId: String) {
        viewModelScope.launch {
            if (providerId in refreshingProviderIds.value) return@launch
            refreshingProviderIds.value = refreshingProviderIds.value + providerId
            modelLoadErrors.value = modelLoadErrors.value - providerId
            try {
                val provider = providerRepository.getProvider(providerId)
                val res = providerRepository.fetchAndStoreModels(providerId)
                res.fold(
                    onSuccess = { loaded ->
                        modelLoadErrors.value = modelLoadErrors.value - providerId
                        autoBindActiveChatModelIfNeeded(providerId, loaded)
                    },
                    onFailure = { throwable ->
                        val parsed = ErrorParser.fromThrowable(
                            throwable,
                            providerName = provider?.displayName ?: "Provider",
                            endpointUrl = provider?.baseUrl.orEmpty(),
                            activeApiKey = providerRepository.getApiKey(providerId),
                        )
                        modelLoadErrors.value = modelLoadErrors.value + (providerId to parsed)
                    },
                )
            } finally {
                refreshingProviderIds.value = refreshingProviderIds.value - providerId
            }
        }
    }

    private suspend fun syncMissingModelsInternal(forceRefresh: Boolean) {
        val providers = providerRepository.observeProviders().first()
        val existingModels = providerRepository.observeEnabledModels().first()
        val enabled = providers.filter { it.isEnabled }
        if (enabled.isEmpty()) return

        val targets = if (forceRefresh) {
            enabled
        } else {
            enabled.filter { p ->
                val hasModels = existingModels.any { it.providerId == p.id }
                val sig = providerSyncSignature(p)
                !hasModels &&
                    (p.hasApiKey || !p.type.requiresApiKey || p.status == ProviderStatus.CONNECTED || p.customHeaders.isNotEmpty()) &&
                    attemptedSyncSignatures[p.id] != sig
            }
        }.filter { it.id !in refreshingProviderIds.value }

        if (targets.isEmpty()) return

        val targetIds = targets.map { it.id }
        targets.forEach { p -> attemptedSyncSignatures[p.id] = providerSyncSignature(p) }
        refreshingProviderIds.value = refreshingProviderIds.value + targetIds
        modelLoadErrors.value = modelLoadErrors.value - targetIds.toSet()

        try {
            val results = providerRepository.syncModelsForProviders(
                targetProviderIds = targetIds,
                onlyIfEmpty = !forceRefresh,
            )
            val updatedErrors = modelLoadErrors.value.toMutableMap()
            for (target in targets) {
                val res = results[target.id] ?: continue
                res.fold(
                    onSuccess = { loaded ->
                        updatedErrors.remove(target.id)
                        autoBindActiveChatModelIfNeeded(target.id, loaded)
                    },
                    onFailure = { throwable ->
                        updatedErrors[target.id] = ErrorParser.fromThrowable(
                            throwable,
                            providerName = target.displayName,
                            endpointUrl = target.baseUrl,
                            activeApiKey = providerRepository.getApiKey(target.id),
                        )
                    },
                )
            }
            modelLoadErrors.value = updatedErrors
        } finally {
            refreshingProviderIds.value = refreshingProviderIds.value - targetIds.toSet()
        }
    }

    private suspend fun autoBindActiveChatModelIfNeeded(providerId: String, loadedModels: List<AiModelInfo>) {
        val chatId = activeChatId.value ?: return
        val firstEnabled = loadedModels.firstOrNull { it.isEnabled } ?: loadedModels.firstOrNull() ?: return
        val chat = chatRepository.getChat(chatId) ?: return
        val prov = providerRepository.getProvider(providerId) ?: return
        val targetModelId = prov.selectedModelId?.takeIf { sel -> loadedModels.any { it.modelId == sel && it.isEnabled } }
            ?: firstEnabled.modelId
        if (chat.modelId.isNullOrBlank() && (chat.providerId == null || chat.providerId == providerId)) {
            chatRepository.updateChatModel(chatId, prov.id, prov.displayName, targetModelId)
        }
    }

    fun loadGitHubPickerData() {
        viewModelScope.launch {
            gitHubPickerState.value = gitHubPickerState.value.copy(isLoading = true)
            val repos = gitHubRepository.listRepos().getOrDefault(gitHubPickerState.value.repos)
            val activeRepo = uiState.value.effectiveGitHubRepo
            val branches = if (!activeRepo.isNullOrBlank()) {
                GitHubValidator.parseFullName(activeRepo)?.let { (owner, repo) ->
                    gitHubRepository.listBranches(owner, repo).getOrDefault(emptyList())
                }.orEmpty()
            } else {
                emptyList()
            }
            gitHubPickerState.value = ChatGitHubPickerState(
                repos = repos,
                branches = branches,
                isLoading = false,
            )
        }
    }

    fun selectChatGitHubRepo(fullName: String?, branch: String?) {
        val chatId = activeChatId.value ?: return
        val currentEnabled = uiState.value.chat?.githubToolsEnabled ?: true
        viewModelScope.launch {
            chatRepository.updateChatGitHubConfig(
                chatId = chatId,
                repoFullName = fullName,
                branch = branch,
                toolsEnabled = currentEnabled,
            )
            if (!fullName.isNullOrBlank()) {
                GitHubValidator.parseFullName(fullName)?.let { (owner, repo) ->
                    val branches = gitHubRepository.listBranches(owner, repo).getOrDefault(emptyList())
                    gitHubPickerState.value = gitHubPickerState.value.copy(branches = branches)
                }
            } else {
                gitHubPickerState.value = gitHubPickerState.value.copy(branches = emptyList())
            }
        }
    }

    fun toggleChatGitHubTools(enabled: Boolean) {
        val chatId = activeChatId.value ?: return
        val chat = uiState.value.chat
        viewModelScope.launch {
            chatRepository.updateChatGitHubConfig(
                chatId = chatId,
                repoFullName = chat?.githubRepoFullName,
                branch = chat?.githubBranch,
                toolsEnabled = enabled,
            )
        }
    }

    fun approvePendingGitHubWrite() {
        val chatId = activeChatId.value ?: return
        sendMessageUseCase.approvePendingGitHubAction(chatId)
    }

    fun rejectPendingGitHubWrite() {
        val chatId = activeChatId.value ?: return
        sendMessageUseCase.rejectPendingGitHubAction(chatId)
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
