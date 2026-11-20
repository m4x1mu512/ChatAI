package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY sortOrder ASC, displayName ASC")
    fun observeProviders(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY sortOrder ASC, displayName ASC")
    suspend fun getAllProviders(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getProvider(id: String): ProviderEntity?

    @Query("SELECT * FROM providers WHERE id = :id")
    fun observeProvider(id: String): Flow<ProviderEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProvider(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteProvider(id: String)

    @Query("UPDATE providers SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE providers SET selectedModelId = :modelId WHERE id = :providerId")
    suspend fun setSelectedModel(providerId: String, modelId: String)
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM ai_models ORDER BY displayName ASC")
    fun observeAllModels(): Flow<List<AiModelEntity>>

    @Query("SELECT * FROM ai_models WHERE isEnabled = 1 ORDER BY displayName ASC")
    fun observeEnabledModels(): Flow<List<AiModelEntity>>

    @Query("SELECT * FROM ai_models WHERE providerId = :providerId ORDER BY displayName ASC")
    fun observeModelsForProvider(providerId: String): Flow<List<AiModelEntity>>

    @Query("SELECT * FROM ai_models WHERE providerId = :providerId ORDER BY displayName ASC")
    suspend fun getModelsForProviderSnapshot(providerId: String): List<AiModelEntity>

    @Query("SELECT COUNT(*) FROM ai_models WHERE providerId = :providerId")
    suspend fun getModelCountForProvider(providerId: String): Int

    @Query("SELECT * FROM ai_models WHERE providerId = :providerId AND modelId = :modelId")
    suspend fun getModel(providerId: String, modelId: String): AiModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModels(models: List<AiModelEntity>)

    @Query("UPDATE ai_models SET isEnabled = :enabled WHERE providerId = :providerId AND modelId = :modelId")
    suspend fun setModelEnabled(providerId: String, modelId: String, enabled: Boolean)

    @Query("UPDATE ai_models SET isEnabled = :enabled WHERE providerId = :providerId")
    suspend fun setAllModelsEnabled(providerId: String, enabled: Boolean)

    @Query("DELETE FROM ai_models WHERE providerId = :providerId AND isManual = 0")
    suspend fun deleteFetchedModelsForProvider(providerId: String)

    @Transaction
    suspend fun replaceFetchedModels(providerId: String, fetched: List<AiModelEntity>) {
        deleteFetchedModelsForProvider(providerId)
        upsertModels(fetched)
    }
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY isPinned DESC, updatedAt DESC")
    fun observeChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    fun observeChat(chatId: String): Flow<ChatEntity?>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getChat(chatId: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChat(chat: ChatEntity)

    @Query("UPDATE chats SET title = :title, updatedAt = :updatedAt WHERE id = :chatId")
    suspend fun renameChat(chatId: String, title: String, updatedAt: Long)

    @Query("UPDATE chats SET isPinned = :pinned WHERE id = :chatId")
    suspend fun setPinned(chatId: String, pinned: Boolean)

    @Query("UPDATE chats SET isArchived = :archived WHERE id = :chatId")
    suspend fun setArchived(chatId: String, archived: Boolean)

    @Query("UPDATE chats SET providerId = :providerId, providerName = :providerName, modelId = :modelId, updatedAt = :updatedAt WHERE id = :chatId")
    suspend fun updateChatModel(
        chatId: String,
        providerId: String,
        providerName: String,
        modelId: String,
        updatedAt: Long,
    )

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChat(chatId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC")
    fun observeMessages(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC")
    suspend fun getMessages(chatId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessage(messageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId AND createdAt > :afterTimestamp")
    suspend fun deleteMessagesAfter(chatId: String, afterTimestamp: Long)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE chatId = :chatId")
    fun observeAttachmentsForChat(chatId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE messageId = :messageId")
    suspend fun getAttachmentsForMessage(messageId: String): List<AttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttachments(attachments: List<AttachmentEntity>)

    @Query("DELETE FROM attachments WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)
}
