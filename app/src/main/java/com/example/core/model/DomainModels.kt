package com.example.core.model

import com.example.core.error.AiError
import kotlinx.serialization.Serializable

@Serializable
data class HeaderEntry(
    val name: String,
    val value: String,
)

@Serializable
data class ProviderConfig(
    val id: String,
    val type: ProviderType,
    val displayName: String,
    val protocol: ApiProtocol,
    val resolvedProtocol: ApiProtocol = ApiProtocol.OPENAI_COMPATIBLE,
    val baseUrl: String,
    val organizationId: String = "",
    val projectId: String = "",
    val customHeaders: List<HeaderEntry> = emptyList(),
    val isEnabled: Boolean = true,
    val status: ProviderStatus = ProviderStatus.NOT_CONFIGURED,
    val lastCheckedAt: Long? = null,
    val lastCheckMessage: String = "",
    val selectedModelId: String? = null,
    val hasApiKey: Boolean = false,
    val maskedApiKey: String = "",
    val sortOrder: Int = 0,
)

@Serializable
data class AiModelInfo(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val capabilities: Set<ModelCapability> = setOf(ModelCapability.CHAT),
    val contextWindow: Int? = null,
    val isManual: Boolean = false,
    val ownedBy: String = "",
    val isEnabled: Boolean = true,
)

@Serializable
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

@Serializable
enum class MessageStatus {
    SENDING,
    GENERATING,
    COMPLETED,
    ERROR,
    CANCELLED,
}

@Serializable
enum class AttachmentType {
    IMAGE,
    DOCUMENT,
    TEXT_FILE,
    WEB_LINK,
}

@Serializable
data class Attachment(
    val id: String,
    val type: AttachmentType,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long = 0L,
    val localUri: String = "",
    val extractedText: String = "",
    val url: String = "",
    val pageTitle: String = "",
)

@Serializable
enum class GeneratedMediaType {
    IMAGE,
    VIDEO,
}

@Serializable
data class GeneratedMedia(
    val id: String,
    val type: GeneratedMediaType,
    val remoteUrl: String = "",
    val localUri: String = "",
    val mimeType: String = "",
    val prompt: String = "",
    val statusText: String = "",
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val providerId: String?,
    val providerName: String = "",
    val modelId: String?,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val githubRepoFullName: String? = null,
    val githubBranch: String? = null,
    val githubToolsEnabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class ChatMessage(
    val id: String,
    val chatId: String,
    val role: MessageRole,
    val content: String,
    val reasoningContent: String = "",
    val status: MessageStatus = MessageStatus.COMPLETED,
    val providerId: String? = null,
    val providerName: String = "",
    val modelId: String = "",
    val attachments: List<Attachment> = emptyList(),
    val generatedMedia: List<GeneratedMedia> = emptyList(),
    val error: AiError? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

@Serializable
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    EN("en"),
    RU("ru"),
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val streamingEnabled: Boolean = true,
    val fetchWebLinksLocally: Boolean = true,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    val onboardingCompleted: Boolean = false,
)
