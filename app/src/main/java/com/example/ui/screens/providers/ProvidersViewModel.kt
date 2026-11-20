package com.example.ui.screens.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.error.AiError
import com.example.core.error.ErrorParser
import com.example.core.model.AiModelInfo
import com.example.core.model.ApiProtocol
import com.example.core.model.HeaderEntry
import com.example.core.model.ProviderConfig
import com.example.core.model.ProviderStatus
import com.example.core.model.ProviderType
import com.example.data.local.SettingsDataStore
import com.example.data.network.ConnectionCheckResult
import com.example.data.repository.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ProviderEditorState(
    val id: String = "",
    val isExisting: Boolean = false,
    val type: ProviderType = ProviderType.OPENAI,
    val displayName: String = ProviderType.OPENAI.defaultDisplayName,
    val protocol: ApiProtocol = ProviderType.OPENAI.defaultProtocol,
    val baseUrl: String = ProviderType.OPENAI.defaultBaseUrl,
    val apiKeyInput: String = "",
    val hasExistingKey: Boolean = false,
    val maskedExistingKey: String = "",
    val organizationId: String = "",
    val projectId: String = "",
    val customHeaders: List<HeaderEntry> = emptyList(),
    val isEnabled: Boolean = true,
    val status: ProviderStatus = ProviderStatus.NOT_CONFIGURED,
    val lastCheckedAt: Long? = null,
    val lastCheckMessage: String = "",
    val selectedModelId: String? = null,
    val models: List<AiModelInfo> = emptyList(),
    val manualModelInput: String = "",
    val isTesting: Boolean = false,
    val isFetchingModels: Boolean = false,
    val isSaving: Boolean = false,
    val checkResult: ConnectionCheckResult? = null,
    val checkError: AiError? = null,
    val showManualModelPrompt: Boolean = false,
)

@HiltViewModel
class ProvidersViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    val configuredProviders: StateFlow<List<ProviderConfig>> = providerRepository.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allModels: StateFlow<List<AiModelInfo>> = providerRepository.observeAllModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val modelsByProvider: StateFlow<Map<String, List<AiModelInfo>>> = allModels
        .map { list -> list.groupBy { it.providerId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _refreshingProviderIds = MutableStateFlow<Set<String>>(emptySet())
    val refreshingProviderIds: StateFlow<Set<String>> = _refreshingProviderIds.asStateFlow()

    private val _editorState = MutableStateFlow(ProviderEditorState())
    val editorState: StateFlow<ProviderEditorState> = _editorState.asStateFlow()

    private var observeModelsJob: Job? = null

    init {
        viewModelScope.launch {
            providerRepository.syncMissingModelsForConnectedProviders()
        }
    }

    fun startNewProvider(type: ProviderType) {
        observeModelsJob?.cancel()
        _editorState.value = ProviderEditorState(
            id = UUID.randomUUID().toString(),
            isExisting = false,
            type = type,
            displayName = type.defaultDisplayName,
            protocol = type.defaultProtocol,
            baseUrl = type.defaultBaseUrl,
        )
    }

    fun loadExistingProvider(providerId: String) {
        observeModelsJob?.cancel()
        observeModelsJob = viewModelScope.launch {
            val config = providerRepository.getProvider(providerId) ?: return@launch
            val rawKey = providerRepository.getApiKey(providerId)
            _editorState.value = ProviderEditorState(
                id = config.id,
                isExisting = true,
                type = config.type,
                displayName = config.displayName,
                protocol = config.protocol,
                baseUrl = config.baseUrl,
                apiKeyInput = rawKey,
                hasExistingKey = config.hasApiKey,
                maskedExistingKey = config.maskedApiKey,
                organizationId = config.organizationId,
                projectId = config.projectId,
                customHeaders = config.customHeaders,
                isEnabled = config.isEnabled,
                status = config.status,
                lastCheckedAt = config.lastCheckedAt,
                lastCheckMessage = config.lastCheckMessage,
                selectedModelId = config.selectedModelId,
            )
            launch {
                if (config.isEnabled && (config.status == ProviderStatus.CONNECTED || config.hasApiKey)) {
                    providerRepository.syncMissingModelsForConnectedProviders()
                }
            }
            providerRepository.observeModelsForProvider(providerId).collect { models ->
                _editorState.value = _editorState.value.copy(models = models)
            }
        }
    }

    fun updateDisplayName(name: String) {
        _editorState.value = _editorState.value.copy(displayName = name)
    }

    fun updateProtocol(protocol: ApiProtocol) {
        _editorState.value = _editorState.value.copy(protocol = protocol)
    }

    fun updateBaseUrl(url: String) {
        _editorState.value = _editorState.value.copy(baseUrl = url)
    }

    fun updateApiKey(key: String) {
        _editorState.value = _editorState.value.copy(apiKeyInput = key)
    }

    fun updateOrganizationId(org: String) {
        _editorState.value = _editorState.value.copy(organizationId = org)
    }

    fun updateProjectId(proj: String) {
        _editorState.value = _editorState.value.copy(projectId = proj)
    }

    fun addCustomHeader() {
        val current = _editorState.value.customHeaders
        _editorState.value = _editorState.value.copy(
            customHeaders = current + HeaderEntry("", ""),
        )
    }

    fun updateCustomHeader(index: Int, name: String, value: String) {
        val updated = _editorState.value.customHeaders.toMutableList()
        if (index in updated.indices) {
            updated[index] = HeaderEntry(name, value)
            _editorState.value = _editorState.value.copy(customHeaders = updated)
        }
    }

    fun removeCustomHeader(index: Int) {
        val updated = _editorState.value.customHeaders.toMutableList()
        if (index in updated.indices) {
            updated.removeAt(index)
            _editorState.value = _editorState.value.copy(customHeaders = updated)
        }
    }

    fun updateManualModelInput(text: String) {
        _editorState.value = _editorState.value.copy(manualModelInput = text)
    }

    fun testConnection() {
        val state = _editorState.value
        val draftConfig = buildDraftConfig(state)
        viewModelScope.launch {
            _editorState.value = state.copy(isTesting = true, checkError = null, checkResult = null)
            val result = providerRepository.testConnection(draftConfig, state.apiKeyInput)
            val nextModels = if (result.success && result.models.isNotEmpty()) {
                result.models
            } else {
                _editorState.value.models
            }
            val nextSelected = _editorState.value.selectedModelId
                ?: nextModels.firstOrNull()?.modelId
            _editorState.value = _editorState.value.copy(
                isTesting = false,
                checkResult = result,
                checkError = result.error,
                status = if (result.success) ProviderStatus.CONNECTED else ProviderStatus.ERROR,
                lastCheckedAt = System.currentTimeMillis(),
                lastCheckMessage = result.message,
                models = nextModels,
                selectedModelId = nextSelected,
                showManualModelPrompt = result.success && nextModels.isEmpty(),
            )
        }
    }

    fun saveAndFetchModels(onSaved: () -> Unit) {
        val state = _editorState.value
        val draftConfig = buildDraftConfig(state)
        viewModelScope.launch {
            _editorState.value = state.copy(isSaving = true, isFetchingModels = true, checkError = null)
            val saved = providerRepository.saveProvider(
                config = draftConfig,
                newApiKey = state.apiKeyInput,
                fetchModelsAfterSave = false,
            )
            val fetchRes = providerRepository.fetchAndStoreModels(saved.id)
            settingsDataStore.setOnboardingCompleted(true)
            fetchRes.fold(
                onSuccess = { models ->
                    _editorState.value = _editorState.value.copy(
                        isSaving = false,
                        isFetchingModels = false,
                        isExisting = true,
                        models = models,
                        status = ProviderStatus.CONNECTED,
                        lastCheckedAt = System.currentTimeMillis(),
                        lastCheckMessage = "Models synced (${models.size})",
                        selectedModelId = saved.selectedModelId ?: models.firstOrNull()?.modelId,
                        showManualModelPrompt = models.isEmpty(),
                    )
                    onSaved()
                },
                onFailure = {
                    val refreshed = providerRepository.getProvider(saved.id)
                    _editorState.value = _editorState.value.copy(
                        isSaving = false,
                        isFetchingModels = false,
                        isExisting = true,
                        status = refreshed?.status ?: ProviderStatus.ERROR,
                        lastCheckedAt = System.currentTimeMillis(),
                        lastCheckMessage = refreshed?.lastCheckMessage.orEmpty(),
                        showManualModelPrompt = true,
                    )
                    onSaved()
                },
            )
        }
    }

    fun fetchModelsOnly() {
        val state = _editorState.value
        val draftConfig = buildDraftConfig(state)
        viewModelScope.launch {
            _editorState.value = state.copy(isFetchingModels = true, checkError = null)
            val saved = providerRepository.saveProvider(
                config = draftConfig,
                newApiKey = state.apiKeyInput,
                fetchModelsAfterSave = false,
            )
            val res = providerRepository.fetchAndStoreModels(saved.id)
            res.fold(
                onSuccess = { models ->
                    _editorState.value = _editorState.value.copy(
                        isFetchingModels = false,
                        isExisting = true,
                        models = models,
                        status = ProviderStatus.CONNECTED,
                        lastCheckedAt = System.currentTimeMillis(),
                        lastCheckMessage = "Models synced (${models.size})",
                        selectedModelId = saved.selectedModelId ?: models.firstOrNull()?.modelId,
                        showManualModelPrompt = models.isEmpty(),
                    )
                },
                onFailure = { t ->
                    val refreshed = providerRepository.getProvider(saved.id)
                    val parsedErr = ErrorParser.fromThrowable(
                        t,
                        providerName = saved.displayName,
                        endpointUrl = saved.baseUrl,
                        activeApiKey = state.apiKeyInput,
                    )
                    _editorState.value = _editorState.value.copy(
                        isFetchingModels = false,
                        isExisting = true,
                        status = refreshed?.status ?: ProviderStatus.ERROR,
                        lastCheckedAt = System.currentTimeMillis(),
                        lastCheckMessage = parsedErr.providerMessage,
                        checkError = parsedErr,
                        showManualModelPrompt = true,
                    )
                },
            )
        }
    }

    fun selectDefaultModel(modelId: String) {
        val state = _editorState.value
        _editorState.value = state.copy(selectedModelId = modelId)
        if (state.isExisting) {
            viewModelScope.launch {
                providerRepository.setSelectedModel(state.id, modelId)
            }
        }
    }

    fun selectProviderDefaultModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            providerRepository.setSelectedModel(providerId, modelId)
        }
    }

    fun refreshModelsForProvider(providerId: String) {
        viewModelScope.launch {
            _refreshingProviderIds.value = _refreshingProviderIds.value + providerId
            providerRepository.fetchAndStoreModels(providerId)
            _refreshingProviderIds.value = _refreshingProviderIds.value - providerId
        }
    }

    fun addManualModel() {
        val state = _editorState.value
        val modelId = state.manualModelInput.trim()
        if (modelId.isEmpty()) return
        viewModelScope.launch {
            if (!state.isExisting) {
                providerRepository.saveProvider(
                    config = buildDraftConfig(state),
                    newApiKey = state.apiKeyInput,
                    fetchModelsAfterSave = false,
                )
            }
            providerRepository.addManualModel(state.id, modelId)
            _editorState.value = _editorState.value.copy(
                manualModelInput = "",
                isExisting = true,
                selectedModelId = modelId,
            )
            loadExistingProvider(state.id)
        }
    }

    fun toggleProviderEnabled(providerId: String, enabled: Boolean) {
        viewModelScope.launch {
            providerRepository.setProviderEnabled(providerId, enabled)
        }
    }

    fun deleteProvider(providerId: String) {
        viewModelScope.launch {
            providerRepository.deleteProvider(providerId)
        }
    }

    fun skipOnboarding() {
        viewModelScope.launch {
            settingsDataStore.setOnboardingCompleted(true)
        }
    }

    private fun buildDraftConfig(state: ProviderEditorState): ProviderConfig {
        return ProviderConfig(
            id = state.id.ifBlank { UUID.randomUUID().toString() },
            type = state.type,
            displayName = state.displayName.trim().ifBlank { state.type.defaultDisplayName },
            protocol = state.protocol,
            baseUrl = state.baseUrl.trim(),
            organizationId = state.organizationId.trim(),
            projectId = state.projectId.trim(),
            customHeaders = state.customHeaders.filter { it.name.isNotBlank() },
            isEnabled = state.isEnabled,
            status = state.status,
            lastCheckedAt = state.lastCheckedAt,
            lastCheckMessage = state.lastCheckMessage,
            selectedModelId = state.selectedModelId,
        )
    }
}
