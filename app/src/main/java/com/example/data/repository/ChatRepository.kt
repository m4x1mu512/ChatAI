package com.example.data.repository

import com.example.core.model.Attachment
import com.example.core.model.ChatMessage
import com.example.core.model.ChatSession
import com.example.data.local.AttachmentDao
import com.example.data.local.ChatDao
import com.example.data.local.MessageDao
import com.example.data.local.toDomain
import com.example.data.local.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
) {
    fun observeChats(): Flow<List<ChatSession>> =
        chatDao.observeChats().map { entities -> entities.map { it.toDomain() } }

    fun observeChat(chatId: String): Flow<ChatSession?> =
        chatDao.observeChat(chatId).map { it?.toDomain() }

    suspend fun getChat(chatId: String): ChatSession? = chatDao.getChat(chatId)?.toDomain()

    fun observeMessages(chatId: String): Flow<List<ChatMessage>> =
        combine(
            messageDao.observeMessages(chatId),
            attachmentDao.observeAttachmentsForChat(chatId),
        ) { messages, attachments ->
            val grouped = attachments.groupBy { it.messageId }
            messages.map { msg ->
                val msgAttachments = grouped[msg.id].orEmpty().map { it.toDomain() }
                msg.toDomain(msgAttachments)
            }
        }

    suspend fun getMessagesSnapshot(chatId: String): List<ChatMessage> {
        val messages = messageDao.getMessages(chatId)
        return messages.map { msg ->
            val attachments = attachmentDao.getAttachmentsForMessage(msg.id).map { it.toDomain() }
            msg.toDomain(attachments)
        }
    }

    suspend fun createChat(
        title: String = "New Chat",
        providerId: String? = null,
        providerName: String = "",
        modelId: String? = null,
    ): ChatSession {
        val now = System.currentTimeMillis()
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            title = title,
            providerId = providerId,
            providerName = providerName,
            modelId = modelId,
            createdAt = now,
            updatedAt = now,
        )
        chatDao.upsertChat(session.toEntity())
        return session
    }

    suspend fun upsertMessage(message: ChatMessage) {
        messageDao.upsertMessage(message.toEntity())
        if (message.attachments.isNotEmpty()) {
            attachmentDao.upsertAttachments(
                message.attachments.map { it.toEntity(message.id, message.chatId) },
            )
        }
    }

    suspend fun updateMessageAttachments(messageId: String, chatId: String, attachments: List<Attachment>) {
        attachmentDao.deleteForMessage(messageId)
        if (attachments.isNotEmpty()) {
            attachmentDao.upsertAttachments(attachments.map { it.toEntity(messageId, chatId) })
        }
    }

    suspend fun deleteMessage(messageId: String) {
        messageDao.deleteMessage(messageId)
    }

    suspend fun deleteMessagesAfter(chatId: String, timestamp: Long) {
        messageDao.deleteMessagesAfter(chatId, timestamp)
    }

    suspend fun renameChat(chatId: String, newTitle: String) {
        chatDao.renameChat(chatId, newTitle.trim(), System.currentTimeMillis())
    }

    suspend fun setPinned(chatId: String, pinned: Boolean) {
        chatDao.setPinned(chatId, pinned)
    }

    suspend fun setArchived(chatId: String, archived: Boolean) {
        chatDao.setArchived(chatId, archived)
    }

    suspend fun updateChatModel(
        chatId: String,
        providerId: String,
        providerName: String,
        modelId: String,
    ) {
        chatDao.updateChatModel(chatId, providerId, providerName, modelId, System.currentTimeMillis())
    }

    suspend fun deleteChat(chatId: String) {
        chatDao.deleteChat(chatId)
    }
}
