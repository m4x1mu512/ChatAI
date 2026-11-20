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
        val resolved = providerRegistry.resolveProtocol(config)
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
            if (result.success && result.models.isNotEmpty()) {
                modelDao.replaceFetchedModels(config.id, result.models.map { it.toEntity() })
            }
            val nextSelected = config.selectedModelId
                ?.takeIf { existing -> result.models.isEmpty() || result.models.any { it.modelId == existing } }
                ?: result.models.firstOrNull()?.modelId
            val updated = config.copy(
                resolvedProtocol = result.resolvedProtocol,
                status = status,
                lastCheckedAt = System.currentTimeMillis(),
                lastCheckMessage = result.message,
                selectedModelId = nextSelected,
            )
            providerDao.upsertProvider(updated.toEntity())
        }
        return result
    }

    suspend fun syncMissingModelsForConnectedProviders() {
        val providers = providerDao.getAllProviders()
        for (entity in providers) {
            if (!entity.isEnabled) continue
            val isConnectedOrConfigured = entity.status == ProviderStatus.CONNECTED.name ||
                secureKeyStore.hasApiKey(entity.id)
            if (!isConnectedOrConfigured) continue
            if (modelDao.getModelCountForProvider(entity.id) == 0) {
                runCatching { fetchAndStoreModels(entity.id) }
            }
        }
    }

    suspend fun fetchAndStoreModels(providerId: String): Result<List<AiModelInfo>> {
        val provider = getProvider(providerId)
            ?: return Result.failure(IllegalArgumentException("Provider not found"))
        val apiKey = secureKeyStore.getApiKey(providerId)
        val impl = providerRegistry.getProvider(provider)
        val result = impl.fetchModels(provider, apiKey)

        result.fold(
            onSuccess = { models ->
                modelDao.replaceFetchedModels(providerId, models.map { it.toEntity() })
                val defaultSelected = provider.selectedModelId
                    ?.takeIf { existing -> models.any { it.modelId == existing } }
                    ?: models.firstOrNull()?.modelId
                providerDao.upsertProvider(
                    provider.copy(
                        status = ProviderStatus.CONNECTED,
                        lastCheckedAt = System.currentTimeMillis(),
                        lastCheckMessage = "Models synced (${models.size})",
                        selectedModelId = defaultSelected,
                    ).toEntity(),
                )
            },
            onFailure = { throwable ->
                val err = ErrorParser.fromThrowable(
                    throwable,
                    providerName = provider.displayName,
                    endpointUrl = provider.baseUrl,
                    activeApiKey = apiKey,
                )
                val nextStatus = if (err.httpStatus == 404 || err.httpStatus == 405) {
                    // Endpoint doesn't list models; keep connected if previously connected or allow manual entry
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
            },
        )
        return result
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

    suspend fun setSelectedModel(providerId: String, modelId: String) {
        providerDao.setSelectedModel(providerId, modelId)
    }

    suspend fun deleteProvider(providerId: String) {
        secureKeyStore.deleteApiKey(providerId)
        providerDao.deleteProvider(providerId)
    }
}
