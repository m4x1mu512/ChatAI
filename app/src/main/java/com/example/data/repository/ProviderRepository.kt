package com.example.data.repository

import com.example.core.error.ErrorParser
import com.example.core.model.AiModelInfo
import com.example.core.model.ApiProtocol
import com.example.core.model.CapabilityInferencer
import com.example.core.model.ProviderConfig
import com.example.core.model.ProviderStatus
import com.example.core.util.SecureKeyStore
import com.example.data.local.ModelDao
import com.example.data.local.ProviderDao
import com.example.data.local.toDomain
import com.example.data.local.toEntity
import com.example.data.network.AiProviderRegistry
import com.example.data.network.ConnectionCheckResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderRepository @Inject constructor(
    private val providerDao: ProviderDao,
    private val modelDao: ModelDao,
    private val secureKeyStore: SecureKeyStore,
    private val providerRegistry: AiProviderRegistry,
) {
    fun observeProviders(): Flow<List<ProviderConfig>> =
        providerDao.observeProviders().map { list ->
            list.map { entity ->
                entity.toDomain(
                    hasApiKey = secureKeyStore.hasApiKey(entity.id),
                    maskedApiKey = secureKeyStore.getMaskedApiKey(entity.id),
                )
            }
        }

    fun observeProvider(providerId: String): Flow<ProviderConfig?> =
        providerDao.observeProvider(providerId).map { entity ->
            entity?.toDomain(
                hasApiKey = secureKeyStore.hasApiKey(entity.id),
                maskedApiKey = secureKeyStore.getMaskedApiKey(entity.id),
            )
        }

    suspend fun getProvider(providerId: String): ProviderConfig? {
        val entity = providerDao.getProvider(providerId) ?: return null
        return entity.toDomain(
            hasApiKey = secureKeyStore.hasApiKey(entity.id),
            maskedApiKey = secureKeyStore.getMaskedApiKey(entity.id),
        )
    }

    fun getApiKey(providerId: String): String = secureKeyStore.getApiKey(providerId)

    fun observeAllModels(): Flow<List<AiModelInfo>> =
        modelDao.observeAllModels().map { list -> list.map { it.toDomain() } }

    fun observeEnabledModels(): Flow<List<AiModelInfo>> =
        modelDao.observeEnabledModels().map { list -> list.map { it.toDomain() } }

    fun observeModelsForProvider(providerId: String): Flow<List<AiModelInfo>> =
        modelDao.observeModelsForProvider(providerId).map { list -> list.map { it.toDomain() } }

    suspend fun getModelInfo(providerId: String, modelId: String): AiModelInfo? =
        modelDao.getModel(providerId, modelId)?.toDomain()

    suspend fun saveProvider(
        config: ProviderConfig,
        newApiKey: String?,
        fetchModelsAfterSave: Boolean = true,
    ): ProviderConfig {
        val id = config.id.ifBlank { UUID.randomUUID().toString() }
        if (newApiKey != null) {
            secureKeyStore.saveApiKey(id, newApiKey)
        }
        val activeKey = secureKeyStore.getApiKey(id)
        val hasKey = activeKey.isNotBlank()
        val resolved = if (config.protocol == ApiProtocol.AUTO && config.resolvedProtocol != ApiProtocol.AUTO) {
            config.resolvedProtocol
        } else {
            providerRegistry.resolveProtocol(config)
        }
        val updated = config.copy(
            id = id,
            resolvedProtocol = resolved,
            hasApiKey = hasKey,
            maskedApiKey = SecureKeyStore.maskApiKey(activeKey),
        )
        providerDao.upsertProvider(updated.toEntity())

        if (fetchModelsAfterSave && updated.isEnabled) {
            fetchAndStoreModels(updated.id)
        }
        return getProvider(id) ?: updated
    }

    suspend fun testConnection(config: ProviderConfig, overrideApiKey: String?): ConnectionCheckResult {
        val activeKey = overrideApiKey?.takeIf { it.isNotBlank() } ?: secureKeyStore.getApiKey(config.id)
        val result = providerRegistry.testConnectionWithAutoDetect(config, activeKey)
        if (config.id.isNotBlank() && providerDao.getProvider(config.id) != null) {
            val status = if (result.success) ProviderStatus.CONNECTED else ProviderStatus.ERROR
            val mergedModels = if (result.success && result.models.isNotEmpty()) {
                val merged = mergeExistingSelections(config.id, result.models)
                modelDao.replaceFetchedModels(config.id, merged.map { it.toEntity() })
                merged
            } else {
                emptyList()
            }
            val enabledModels = mergedModels.filter { it.isEnabled }
            val nextSelected = config.selectedModelId
                ?.takeIf { existing -> mergedModels.isEmpty() || enabledModels.any { it.modelId == existing } }
                ?: enabledModels.firstOrNull()?.modelId
                ?: mergedModels.firstOrNull()?.modelId
            val updated = config.copy(
                resolvedProtocol = result.resolvedProtocol,
                status = status,
                lastCheckedAt = System.currentTimeMillis(),
                lastCheckMessage = result.message,
                selectedModelId = nextSelected,
            )
            providerDao.upsertProvider(updated.toEntity())
            return result.copy(models = mergedModels)
        }
        return result
    }

    suspend fun syncMissingModelsForConnectedProviders(): Map<String, Result<List<AiModelInfo>>> =
        syncModelsForProviders(onlyIfEmpty = true)

    suspend fun syncModelsForProviders(
        targetProviderIds: List<String>? = null,
        onlyIfEmpty: Boolean = true,
    ): Map<String, Result<List<AiModelInfo>>> {
        val providers = providerDao.getAllProviders()
        val results = linkedMapOf<String, Result<List<AiModelInfo>>>()
        for (entity in providers) {
            if (targetProviderIds != null && entity.id !in targetProviderIds) continue
            if (!entity.isEnabled) continue
            val domain = entity.toDomain(
                hasApiKey = secureKeyStore.hasApiKey(entity.id),
                maskedApiKey = secureKeyStore.getMaskedApiKey(entity.id),
            )
            val isExplicitTarget = targetProviderIds != null
            val isReady = isExplicitTarget ||
                domain.status == ProviderStatus.CONNECTED ||
                domain.hasApiKey ||
                !domain.type.requiresApiKey ||
                domain.customHeaders.isNotEmpty()
            if (!isReady) continue
            if (!onlyIfEmpty || modelDao.getModelCountForProvider(entity.id) == 0) {
                results[entity.id] = fetchAndStoreModels(entity.id)
            }
        }
        return results
    }

    suspend fun fetchAndStoreModels(providerId: String): Result<List<AiModelInfo>> {
        val provider = getProvider(providerId)
            ?: return Result.failure(IllegalArgumentException("Provider not found"))
        val apiKey = secureKeyStore.getApiKey(providerId)
        val (usedProtocol, result) = providerRegistry.fetchModelsWithAutoDetect(provider, apiKey)

        return result.map { fetched ->
            val merged = mergeExistingSelections(providerId, fetched)
            modelDao.replaceFetchedModels(providerId, merged.map { it.toEntity() })
            val enabledModels = merged.filter { it.isEnabled }
            val defaultSelected = provider.selectedModelId
                ?.takeIf { existing -> enabledModels.any { it.modelId == existing } }
                ?: enabledModels.firstOrNull()?.modelId
                ?: merged.firstOrNull()?.modelId
            providerDao.upsertProvider(
                provider.copy(
                    resolvedProtocol = usedProtocol,
                    status = ProviderStatus.CONNECTED,
                    lastCheckedAt = System.currentTimeMillis(),
                    lastCheckMessage = "Models synced (${merged.size})",
                    selectedModelId = defaultSelected,
                ).toEntity(),
            )
            merged
        }.onFailure { throwable ->
            val err = ErrorParser.fromThrowable(
                throwable,
                providerName = provider.displayName,
                endpointUrl = provider.baseUrl,
                activeApiKey = apiKey,
            )
            val nextStatus = if (err.httpStatus == 404 || err.httpStatus == 405) {
                provider.status
            } else {
                ProviderStatus.ERROR
            }
            providerDao.upsertProvider(
                provider.copy(
                    status = nextStatus,
                    lastCheckedAt = System.currentTimeMillis(),
                    lastCheckMessage = err.providerMessage,
                ).toEntity(),
            )
        }
    }

    private suspend fun mergeExistingSelections(providerId: String, fetched: List<AiModelInfo>): List<AiModelInfo> {
        val existing = modelDao.getModelsForProviderSnapshot(providerId)
        if (existing.isEmpty() || existing.none { it.isEnabled }) return fetched
        val existingMap = existing.associate { it.modelId to it.isEnabled }
        val hasCuratedSubset = existing.any { !it.isEnabled }
        return fetched.map { model ->
            val enabled = existingMap[model.modelId] ?: !hasCuratedSubset
            model.copy(isEnabled = enabled)
        }
    }

    suspend fun saveModelsForProvider(providerId: String, models: List<AiModelInfo>) {
        val fetched = models.filter { !it.isManual }.map { it.copy(providerId = providerId).toEntity() }
        val manual = models.filter { it.isManual }.map { it.copy(providerId = providerId).toEntity() }
        modelDao.replaceFetchedModels(providerId, fetched)
        if (manual.isNotEmpty()) {
            modelDao.upsertModels(manual)
        }
    }

    suspend fun setModelEnabled(providerId: String, modelId: String, enabled: Boolean) {
        modelDao.setModelEnabled(providerId, modelId, enabled)
    }

    suspend fun setAllModelsEnabled(providerId: String, enabled: Boolean) {
        modelDao.setAllModelsEnabled(providerId, enabled)
    }

    suspend fun addManualModel(providerId: String, rawModelId: String) {
        val trimmed = rawModelId.trim()
        if (trimmed.isEmpty()) return
        val model = AiModelInfo(
            providerId = providerId,
            modelId = trimmed,
            displayName = trimmed,
            capabilities = CapabilityInferencer.inferCapabilities(trimmed, trimmed),
            isManual = true,
            ownedBy = "manual",
            isEnabled = true,
        )
        modelDao.upsertModels(listOf(model.toEntity()))
        val provider = providerDao.getProvider(providerId)
        if (provider != null) {
            val nextStatus = if (provider.status == ProviderStatus.NOT_CONFIGURED.name) {
                ProviderStatus.CONNECTED.name
            } else {
                provider.status
            }
            providerDao.upsertProvider(
                provider.copy(
                    selectedModelId = trimmed,
                    status = nextStatus,
                ),
            )
        }
    }

    suspend fun setProviderEnabled(providerId: String, enabled: Boolean) {
        providerDao.setEnabled(providerId, enabled)
    }

    suspend fun setSelectedModel(providerId: String, modelId: String?) {
        providerDao.setSelectedModel(providerId, modelId.orEmpty())
    }

    suspend fun deleteProvider(providerId: String) {
        secureKeyStore.deleteApiKey(providerId)
        providerDao.deleteProvider(providerId)
    }
}
