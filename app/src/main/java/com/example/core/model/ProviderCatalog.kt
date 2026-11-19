package com.example.core.model

import androidx.annotation.StringRes
import com.example.R
import kotlinx.serialization.Serializable

@Serializable
enum class ApiProtocol {
    OPENAI_COMPATIBLE,
    ANTHROPIC_COMPATIBLE,
    AUTO,
}

@Serializable
enum class ProviderStatus {
    NOT_CONFIGURED,
    CONNECTED,
    ERROR,
}

@Serializable
enum class ModelCapability {
    CHAT,
    VISION,
    IMAGE,
    VIDEO,
    TOOLS,
    REASONING,
}

@Serializable
enum class ProviderType(
    val defaultDisplayName: String,
    val defaultBaseUrl: String,
    val defaultProtocol: ApiProtocol,
    val supportsOrgAndProject: Boolean,
    val requiresApiKey: Boolean,
    val monogram: String,
    val accentHex: Long,
    @param:StringRes val descriptionRes: Int,
) {
    COARENA(
        defaultDisplayName = "Coarena",
        defaultBaseUrl = "https://api.coarena.com/v1",
        defaultProtocol = ApiProtocol.OPENAI_COMPATIBLE,
        supportsOrgAndProject = false,
        requiresApiKey = true,
        monogram = "CA",
        accentHex = 0xFF0EA5E9,
        descriptionRes = R.string.provider_desc_coarena,
    ),
    OPENAI(
        defaultDisplayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultProtocol = ApiProtocol.OPENAI_COMPATIBLE,
        supportsOrgAndProject = true,
        requiresApiKey = true,
        monogram = "OA",
        accentHex = 0xFF10A37F,
        descriptionRes = R.string.provider_desc_openai,
    ),
    PERPLEXITY(
        defaultDisplayName = "Perplexity",
        defaultBaseUrl = "https://api.perplexity.ai",
        defaultProtocol = ApiProtocol.OPENAI_COMPATIBLE,
        supportsOrgAndProject = false,
        requiresApiKey = true,
        monogram = "PX",
        accentHex = 0xFF1FB8CD,
        descriptionRes = R.string.provider_desc_perplexity,
    ),
    ANTHROPIC(
        defaultDisplayName = "Anthropic",
        defaultBaseUrl = "https://api.anthropic.com",
        defaultProtocol = ApiProtocol.ANTHROPIC_COMPATIBLE,
        supportsOrgAndProject = false,
        requiresApiKey = true,
        monogram = "AN",
        accentHex = 0xFFD97757,
        descriptionRes = R.string.provider_desc_anthropic,
    ),
    CLAUDE_CODE(
        defaultDisplayName = "Claude Code",
        defaultBaseUrl = "https://api.anthropic.com",
        defaultProtocol = ApiProtocol.ANTHROPIC_COMPATIBLE,
        supportsOrgAndProject = false,
        requiresApiKey = true,
        monogram = "CC",
        accentHex = 0xFFC2410C,
        descriptionRes = R.string.provider_desc_claude_code,
    ),
    GOOGLE_GEMINI(
        defaultDisplayName = "Google Gemini",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultProtocol = ApiProtocol.OPENAI_COMPATIBLE,
        supportsOrgAndProject = false,
        requiresApiKey = true,
        monogram = "GM",
        accentHex = 0xFF3B82F6,
        descriptionRes = R.string.provider_desc_gemini,
    ),
    DEEPSEEK(
        defaultDisplayName = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultProtocol = ApiProtocol.OPENAI_COMPATIBLE,
        supportsOrgAndProject = false,
        requiresApiKey = true,
        monogram = "DS",
        accentHex = 0xFF4F46E5,
        descriptionRes = R.string.provider_desc_deepseek,
    ),
    GROQ(
        defaultDisplayName = "Groq",
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        defaultProtocol = ApiProtocol.OPENAI_COMPATIBLE,
        supportsOrgAndProject = false,
        requiresApiKey = true,
        monogram = "GQ",
        accentHex = 0xFFF97316,
        descriptionRes = R.string.provider_desc_groq,
    ),
    OPENROUTER(
        defaultDisplayName = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultProtocol = ApiProtocol.OPENAI_COMPATIBLE,
        supportsOrgAndProject = false,
        requiresApiKey = true,
        monogram = "OR",
        accentHex = 0xFF6366F1,
        descriptionRes = R.string.provider_desc_openrouter,
    ),
    GROK(
        defaultDisplayName = "Grok",
        defaultBaseUrl = "https://api.x.ai/v1",
        defaultProtocol = ApiProtocol.OPENAI_COMPATIBLE,
        supportsOrgAndProject = false,
        requiresApiKey = true,
        monogram = "GK",
        accentHex = 0xFF334155,
        descriptionRes = R.string.provider_desc_grok,
    ),
    MISTRAL(
        defaultDisplayName = "Mistral AI",
        defaultBaseUrl = "https://api.mistral.ai/v1",
        defaultProtocol = ApiProtocol.OPENAI_COMPATIBLE,
        supportsOrgAndProject = false,
        requiresApiKey = true,
        monogram = "MS",
        accentHex = 0xFFEA580C,
        descriptionRes = R.string.provider_desc_mistral,
    ),
    TOGETHER(
        defaultDisplayName = "Together AI",
        defaultBaseUrl = "https://api.together.xyz/v1",
        defaultProtocol = ApiProtocol.OPENAI_COMPATIBLE,
        supportsOrgAndProject = false,
        requiresApiKey = true,
        monogram = "TG",
        accentHex = 0xFF0284C7,
        descriptionRes = R.string.provider_desc_together,
    ),
    CLOUDFLARE(
        defaultDisplayName = "Cloudflare AI",
        defaultBaseUrl = "https://api.cloudflare.com/client/v4/accounts/YOUR_ACCOUNT_ID/ai/v1",
        defaultProtocol = ApiProtocol.OPENAI_COMPATIBLE,
        supportsOrgAndProject = false,
        requiresApiKey = true,
        monogram = "CF",
        accentHex = 0xFFF59E0B,
        descriptionRes = R.string.provider_desc_cloudflare,
    ),
    HUGGINGFACE(
        defaultDisplayName = "Hugging Face",
        defaultBaseUrl = "https://router.huggingface.co/v1",
        defaultProtocol = ApiProtocol.OPENAI_COMPATIBLE,
        supportsOrgAndProject = false,
        requiresApiKey = true,
        monogram = "HF",
        accentHex = 0xFFEAB308,
        descriptionRes = R.string.provider_desc_huggingface,
    ),
    XAI(
        defaultDisplayName = "xAI",
        defaultBaseUrl = "https://api.x.ai/v1",
        defaultProtocol = ApiProtocol.OPENAI_COMPATIBLE,
        supportsOrgAndProject = false,
        requiresApiKey = true,
        monogram = "xA",
        accentHex = 0xFF1E293B,
        descriptionRes = R.string.provider_desc_xai,
    ),
    CUSTOM_OPENAI(
        defaultDisplayName = "Custom Server",
        defaultBaseUrl = "http://localhost:11434/v1",
        defaultProtocol = ApiProtocol.OPENAI_COMPATIBLE,
        supportsOrgAndProject = true,
        requiresApiKey = false,
        monogram = "SV",
        accentHex = 0xFF0D9488,
        descriptionRes = R.string.provider_desc_custom,
    ),
}

object CapabilityInferencer {
    fun inferCapabilities(
        modelId: String,
        displayName: String = modelId,
        explicitTypes: Set<String> = emptySet(),
    ): Set<ModelCapability> {
        val lower = "$modelId $displayName".lowercase()
        val caps = mutableSetOf<ModelCapability>()
        val normalizedExplicit = explicitTypes.map { it.lowercase() }.toSet()

        val isImageGen = "image" in normalizedExplicit ||
            lower.contains("dall-e") ||
            lower.contains("gpt-image") ||
            lower.contains("imagen") ||
            lower.contains("flux") ||
            lower.contains("stable-diffusion") ||
            lower.contains("sdxl") ||
            lower.contains("grok-2-image") ||
            lower.contains("-image")

        val isVideoGen = "video" in normalizedExplicit ||
            lower.contains("sora") ||
            lower.contains("veo") ||
            lower.contains("kling") ||
            lower.contains("runway") ||
            lower.contains("minimax-video") ||
            lower.contains("-video")

        if (isImageGen) {
            caps += ModelCapability.IMAGE
        }
        if (isVideoGen) {
            caps += ModelCapability.VIDEO
        }
        if (!isImageGen && !isVideoGen) {
            caps += ModelCapability.CHAT
        }

        if ("vision" in normalizedExplicit ||
            lower.contains("gpt-4o") ||
            lower.contains("gpt-4.1") ||
            lower.contains("gpt-4.5") ||
            lower.contains("gpt-5") ||
            lower.contains("claude-3") ||
            lower.contains("claude-4") ||
            lower.contains("claude-sonnet-4") ||
            lower.contains("claude-opus-4") ||
            lower.contains("claude-haiku-4") ||
            lower.contains("gemini") ||
            lower.contains("pixtral") ||
            lower.contains("grok-2-vision") ||
            lower.contains("grok-3") ||
            lower.contains("grok-4") ||
            lower.contains("llama-3.2-11b-vision") ||
            lower.contains("llama-3.2-90b-vision") ||
            lower.contains("llama-4") ||
            lower.contains("qwen2.5-vl") ||
            lower.contains("qwen-vl") ||
            lower.contains("vision") ||
            lower.contains("vl")
        ) {
            caps += ModelCapability.VISION
        }

        if (lower.contains("o1") ||
            lower.contains("o3") ||
            lower.contains("o4") ||
            lower.contains("deepseek-r1") ||
            lower.contains("deepseek-reasoner") ||
            lower.contains("qwq") ||
            lower.contains("thinking") ||
            lower.contains("reasoning") ||
            lower.contains("grok-3-mini")
        ) {
            caps += ModelCapability.REASONING
        }

        if (!isImageGen && !isVideoGen && (
                lower.contains("gpt-4") ||
                    lower.contains("gpt-5") ||
                    lower.contains("claude") ||
                    lower.contains("gemini") ||
                    lower.contains("mistral") ||
                    lower.contains("deepseek") ||
                    lower.contains("qwen") ||
                    lower.contains("llama-3.3") ||
                    lower.contains("llama-4") ||
                    lower.contains("grok")
                )
        ) {
            caps += ModelCapability.TOOLS
        }

        return caps
    }
}
