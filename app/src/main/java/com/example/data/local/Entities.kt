package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.core.error.AiError
import com.example.core.model.AiModelInfo
import com.example.core.model.ApiProtocol
import com.example.core.model.Attachment
import com.example.core.model.AttachmentType
import com.example.core.model.ChatMessage
import com.example.core.model.ChatSession
import com.example.core.model.GeneratedMedia
import com.example.core.model.HeaderEntry
import com.example.core.model.MessageRole
import com.example.core.model.MessageStatus
import com.example.core.model.ModelCapability
import com.example.core.model.ProviderConfig
import com.example.core.model.ProviderStatus
import com.example.core.model.ProviderType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val roomJson = Json { ignoreUnknownKeys = true }

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val type: String,
    val displayName: String,
    val protocol: String,
    val resolvedProtocol: String,
    val baseUrl: String,
    val organizationId: String,
    val projectId: String,
    val customHeadersJson: String,
    val isEnabled: Boolean,
    val status: String,
    val lastCheckedAt: Long?,
    val lastCheckMessage: String,
    val selectedModelId: String?,
    val sortOrder: Int,
)

@Entity(
    tableName = "ai_models",
    primaryKeys = ["providerId", "modelId"],
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("providerId")],
)
data class AiModelEntity(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val capabilitiesCsv: String,
    val contextWindow: Int?,
    val isManual: Boolean,
    val ownedBy: String,
    val isEnabled: Boolean = true,
)

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    val providerId: String?,
    val providerName: String,
    val modelId: String?,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chatId"), Index("createdAt")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val role: String,
    val content: String,
    val reasoningContent: String,
    val status: String,
    val providerId: String?,
    val providerName: String,
    val modelId: String,
    val errorJson: String?,
    val generatedMediaJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId"), Index("chatId")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val chatId: String,
    val type: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localUri: String,
    val extractedText: String,
    val url: String,
    val pageTitle: String,
)

fun ProviderEntity.toDomain(hasApiKey: Boolean, maskedApiKey: String): ProviderConfig {
    val headers = runCatching {
        roomJson.decodeFromString<List<HeaderEntry>>(customHeadersJson)
    }.getOrDefault(emptyList())
    return ProviderConfig(
        id = id,
        type = runCatching { ProviderType.valueOf(type) }.getOrDefault(ProviderType.CUSTOM_OPENAI),
        displayName = displayName,
        protocol = runCatching { ApiProtocol.valueOf(protocol) }.getOrDefault(ApiProtocol.OPENAI_COMPATIBLE),
        resolvedProtocol = runCatching { ApiProtocol.valueOf(resolvedProtocol) }.getOrDefault(ApiProtocol.OPENAI_COMPATIBLE),
        baseUrl = baseUrl,
        organizationId = organizationId,
        projectId = projectId,
        customHeaders = headers,
        isEnabled = isEnabled,
        status = runCatching { ProviderStatus.valueOf(status) }.getOrDefault(ProviderStatus.NOT_CONFIGURED),
        lastCheckedAt = lastCheckedAt,
        lastCheckMessage = lastCheckMessage,
        selectedModelId = selectedModelId,
        hasApiKey = hasApiKey,
        maskedApiKey = maskedApiKey,
        sortOrder = sortOrder,
    )
}

fun ProviderConfig.toEntity(): ProviderEntity = ProviderEntity(
    id = id,
    type = type.name,
    displayName = displayName,
    protocol = protocol.name,
    resolvedProtocol = resolvedProtocol.name,
    baseUrl = baseUrl,
    organizationId = organizationId,
    projectId = projectId,
    customHeadersJson = roomJson.encodeToString(customHeaders),
    isEnabled = isEnabled,
    status = status.name,
    lastCheckedAt = lastCheckedAt,
    lastCheckMessage = lastCheckMessage,
    selectedModelId = selectedModelId,
    sortOrder = sortOrder,
)

fun AiModelEntity.toDomain(): AiModelInfo = AiModelInfo(
    providerId = providerId,
    modelId = modelId,
    displayName = displayName,
    capabilities = capabilitiesCsv.split(",")
        .mapNotNull { token -> runCatching { ModelCapability.valueOf(token.trim()) }.getOrNull() }
        .toSet()
        .ifEmpty { setOf(ModelCapability.CHAT) },
    contextWindow = contextWindow,
    isManual = isManual,
    ownedBy = ownedBy,
    isEnabled = isEnabled,
)

fun AiModelInfo.toEntity(): AiModelEntity = AiModelEntity(
    providerId = providerId,
    modelId = modelId,
    displayName = displayName,
    capabilitiesCsv = capabilities.joinToString(",") { it.name },
    contextWindow = contextWindow,
    isManual = isManual,
    ownedBy = ownedBy,
    isEnabled = isEnabled,
)

fun ChatEntity.toDomain(): ChatSession = ChatSession(
    id = id,
    title = title,
    providerId = providerId,
    providerName = providerName,
    modelId = modelId,
    isPinned = isPinned,
    isArchived = isArchived,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ChatSession.toEntity(): ChatEntity = ChatEntity(
    id = id,
    title = title,
    providerId = providerId,
    providerName = providerName,
    modelId = modelId,
    isPinned = isPinned,
    isArchived = isArchived,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun AttachmentEntity.toDomain(): Attachment = Attachment(
    id = id,
    type = runCatching { AttachmentType.valueOf(type) }.getOrDefault(AttachmentType.DOCUMENT),
    displayName = displayName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    localUri = localUri,
    extractedText = extractedText,
    url = url,
    pageTitle = pageTitle,
)

fun Attachment.toEntity(messageId: String, chatId: String): AttachmentEntity = AttachmentEntity(
    id = id,
    messageId = messageId,
    chatId = chatId,
    type = type.name,
    displayName = displayName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    localUri = localUri,
    extractedText = extractedText,
    url = url,
    pageTitle = pageTitle,
)

fun MessageEntity.toDomain(attachments: List<Attachment>): ChatMessage {
    val error = errorJson?.let { raw -> runCatching { roomJson.decodeFromString<AiError>(raw) }.getOrNull() }
    val media = runCatching { roomJson.decodeFromString<List<GeneratedMedia>>(generatedMediaJson) }.getOrDefault(emptyList())
    return ChatMessage(
        id = id,
        chatId = chatId,
        role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.USER),
        content = content,
        reasoningContent = reasoningContent,
        status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.COMPLETED),
        providerId = providerId,
        providerName = providerName,
        modelId = modelId,
        attachments = attachments,
        generatedMedia = media,
        error = error,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

fun ChatMessage.toEntity(): MessageEntity = MessageEntity(
    id = id,
    chatId = chatId,
    role = role.name,
    content = content,
    reasoningContent = reasoningContent,
    status = status.name,
    providerId = providerId,
    providerName = providerName,
    modelId = modelId,
    errorJson = error?.let { roomJson.encodeToString(it) },
    generatedMediaJson = roomJson.encodeToString(generatedMedia),
    createdAt = createdAt,
    updatedAt = updatedAt,
)
