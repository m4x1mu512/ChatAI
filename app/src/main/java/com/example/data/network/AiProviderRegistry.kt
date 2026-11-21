package com.example.data.network

import com.example.core.model.AiModelInfo
import com.example.core.model.ApiProtocol
import com.example.core.model.ProviderConfig
import com.example.core.model.ProviderType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiProviderRegistry @Inject constructor(
    private val openAiProvider: OpenAiCompatibleProvider,
    private val anthropicProvider: AnthropicCompatibleProvider,
) {
    fun resolveProtocol(config: ProviderConfig): ApiProtocol {
        return when (config.protocol) {
            ApiProtocol.OPENAI_COMPATIBLE -> ApiProtocol.OPENAI_COMPATIBLE
            ApiProtocol.ANTHROPIC_COMPATIBLE -> ApiProtocol.ANTHROPIC_COMPATIBLE
            ApiProtocol.AUTO -> {
                val url = config.baseUrl.lowercase()
                if (url.contains("anthropic") ||
                    config.type == ProviderType.ANTHROPIC ||
                    config.type == ProviderType.CLAUDE_CODE
                ) {
                    ApiProtocol.ANTHROPIC_COMPATIBLE
                } else {
                    ApiProtocol.OPENAI_COMPATIBLE
                }
            }
        }
    }

    fun getProvider(config: ProviderConfig): AiProvider {
        val effective = if (config.protocol == ApiProtocol.AUTO) {
            config.resolvedProtocol.takeIf { it != ApiProtocol.AUTO } ?: resolveProtocol(config)
        } else {
            config.protocol
        }
        return when (effective) {
            ApiProtocol.ANTHROPIC_COMPATIBLE -> anthropicProvider
            ApiProtocol.OPENAI_COMPATIBLE, ApiProtocol.AUTO -> openAiProvider
        }
    }

    suspend fun testConnectionWithAutoDetect(
        config: ProviderConfig,
        apiKey: String,
    ): ConnectionCheckResult {
        if (config.protocol != ApiProtocol.AUTO) {
            return getProvider(config).testConnection(config, apiKey)
        }
        val heuristic = resolveProtocol(config)
        val primary = if (heuristic == ApiProtocol.ANTHROPIC_COMPATIBLE) anthropicProvider else openAiProvider
        val primaryResult = primary.testConnection(config, apiKey)
        if (primaryResult.success) return primaryResult

        val secondary = if (primary === anthropicProvider) openAiProvider else anthropicProvider
        val secondaryResult = secondary.testConnection(config, apiKey)
        return if (secondaryResult.success) secondaryResult else primaryResult
    }

    suspend fun fetchModelsWithAutoDetect(
        config: ProviderConfig,
        apiKey: String,
    ): Pair<ApiProtocol, Result<List<AiModelInfo>>> {
        if (config.protocol != ApiProtocol.AUTO) {
            val provider = getProvider(config)
            return provider.protocol to provider.fetchModels(config, apiKey)
        }
        val heuristic = resolveProtocol(config)
        val primary = if (heuristic == ApiProtocol.ANTHROPIC_COMPATIBLE) anthropicProvider else openAiProvider
        val primaryResult = primary.fetchModels(config, apiKey)
        if (primaryResult.isSuccess) {
            return primary.protocol to primaryResult
        }

        val secondary = if (primary === anthropicProvider) openAiProvider else anthropicProvider
        val secondaryResult = secondary.fetchModels(config, apiKey)
        return if (secondaryResult.isSuccess) {
            secondary.protocol to secondaryResult
        } else {
            primary.protocol to primaryResult
        }
    }
}
