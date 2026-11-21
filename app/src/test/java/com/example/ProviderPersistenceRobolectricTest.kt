package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.ApiProtocol
import com.example.core.model.AttachmentType
import com.example.core.model.MessageRole
import com.example.core.model.MessageStatus
import com.example.core.model.ProviderStatus
import com.example.core.model.ProviderType
import com.example.data.local.AiModelEntity
import com.example.data.local.AppDatabase
import com.example.data.local.AttachmentEntity
import com.example.data.local.ChatEntity
import com.example.data.local.MessageEntity
import com.example.data.local.ProviderEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProviderPersistenceRobolectricTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `upserting existing provider does not cascade delete models`() = runTest {
        val providerDao = database.providerDao()
        val modelDao = database.modelDao()

        val initialProvider = sampleProviderEntity(id = "prov-1", selectedModelId = null)
        providerDao.upsertProvider(initialProvider)

        val fetchedModels = listOf(
            sampleModelEntity(providerId = "prov-1", modelId = "gpt-4o", isManual = false),
            sampleModelEntity(providerId = "prov-1", modelId = "gpt-4.1-mini", isManual = false),
        )
        modelDao.replaceFetchedModels("prov-1", fetchedModels)

        // Mimic fetchAndStoreModels / repeated testConnection / saveProvider updating parent row
        providerDao.upsertProvider(
            initialProvider.copy(
                status = ProviderStatus.CONNECTED.name,
                lastCheckedAt = 123456L,
                lastCheckMessage = "Models synced (2)",
                selectedModelId = "gpt-4o",
            ),
        )

        val storedAfterFetch = modelDao.getModelsForProviderSnapshot("prov-1")
        assertEquals(2, storedAfterFetch.size)
        assertEquals(listOf("gpt-4.1-mini", "gpt-4o"), storedAfterFetch.map { it.modelId })

        // Mimic second testConnection on the same existing provider
        modelDao.replaceFetchedModels("prov-1", fetchedModels)
        providerDao.upsertProvider(
            initialProvider.copy(
                status = ProviderStatus.CONNECTED.name,
                lastCheckedAt = 234567L,
                lastCheckMessage = "Connected (2 models)",
                selectedModelId = "gpt-4o",
            ),
        )

        val storedAfterSecondCheck = modelDao.getModelsForProviderSnapshot("prov-1")
        assertEquals(2, storedAfterSecondCheck.size)

        // Mimic addManualModel inserting a manual model and then upserting provider
        val manual = sampleModelEntity(providerId = "prov-1", modelId = "custom-model-x", isManual = true)
        modelDao.upsertModels(listOf(manual))
        providerDao.upsertProvider(initialProvider.copy(selectedModelId = "custom-model-x"))

        val storedAfterManual = modelDao.getModelsForProviderSnapshot("prov-1")
        assertEquals(3, storedAfterManual.size)
        assertTrue(storedAfterManual.any { it.modelId == "custom-model-x" && it.isManual })
    }

    @Test
    fun `upserting existing chat and message preserves child messages and attachments`() = runTest {
        val chatDao = database.chatDao()
        val messageDao = database.messageDao()
        val attachmentDao = database.attachmentDao()

        val chat = ChatEntity(
            id = "chat-1",
            title = "Initial",
            providerId = "prov-1",
            providerName = "Coarena",
            modelId = "gpt-4o",
            isPinned = false,
            isArchived = false,
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        chatDao.upsertChat(chat)

        val message = MessageEntity(
            id = "msg-1",
            chatId = "chat-1",
            role = MessageRole.USER.name,
            content = "Hello",
            reasoningContent = "",
            status = MessageStatus.COMPLETED.name,
            providerId = "prov-1",
            providerName = "Coarena",
            modelId = "gpt-4o",
            errorJson = null,
            generatedMediaJson = "[]",
            createdAt = 1001L,
            updatedAt = 1001L,
        )
        messageDao.upsertMessage(message)

        val attachment = AttachmentEntity(
            id = "att-1",
            messageId = "msg-1",
            chatId = "chat-1",
            type = AttachmentType.DOCUMENT.name,
            displayName = "spec.txt",
            mimeType = "text/plain",
            sizeBytes = 42L,
            localUri = "content://example/spec.txt",
            extractedText = "hello",
            url = "",
            pageTitle = "",
        )
        attachmentDao.upsertAttachments(listOf(attachment))

        chatDao.upsertChat(chat.copy(title = "Renamed", updatedAt = 2000L))
        messageDao.upsertMessage(message.copy(content = "Hello edited", updatedAt = 2001L))

        val messages = messageDao.getMessages("chat-1")
        val attachments = attachmentDao.getAttachmentsForMessage("msg-1")
        assertEquals(1, messages.size)
        assertEquals("Hello edited", messages.first().content)
        assertEquals(1, attachments.size)
        assertEquals("spec.txt", attachments.first().displayName)
    }

    private fun sampleProviderEntity(id: String, selectedModelId: String?) = ProviderEntity(
        id = id,
        type = ProviderType.COARENA.name,
        displayName = "Coarena",
        protocol = ApiProtocol.OPENAI_COMPATIBLE.name,
        resolvedProtocol = ApiProtocol.OPENAI_COMPATIBLE.name,
        baseUrl = "https://coarena.ai/api/v1",
        organizationId = "",
        projectId = "",
        customHeadersJson = "[]",
        isEnabled = true,
        status = ProviderStatus.NOT_CONFIGURED.name,
        lastCheckedAt = null,
        lastCheckMessage = "",
        selectedModelId = selectedModelId,
        sortOrder = 0,
    )

    private fun sampleModelEntity(providerId: String, modelId: String, isManual: Boolean) = AiModelEntity(
        providerId = providerId,
        modelId = modelId,
        displayName = modelId,
        capabilitiesCsv = "CHAT",
        contextWindow = 128000,
        isManual = isManual,
        ownedBy = if (isManual) "manual" else "coarena",
        isEnabled = true,
    )
}
