package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.AttachmentType
import com.example.core.model.MessageRole
import com.example.core.model.MessageStatus
import com.example.core.util.SecureKeyStore
import com.example.data.local.AppDatabase
import com.example.data.local.AttachmentEntity
import com.example.data.local.ChatEntity
import com.example.data.local.MessageEntity
import com.example.data.local.ProviderEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app_name matches AI Chat`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AI Chat", appName)
    }

    @Test
    fun `secure key store encrypts decrypts and masks keys`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val keyStore = SecureKeyStore(context)
        val providerId = "prov-test-1"
        val secret = "sk-test-1234567890abcdef"

        keyStore.saveApiKey(providerId, secret)
        assertTrue(keyStore.hasApiKey(providerId))
        assertEquals(secret, keyStore.getApiKey(providerId))
        assertEquals("sk-t••••cdef", keyStore.getMaskedApiKey(providerId))

        keyStore.deleteApiKey(providerId)
        assertFalse(keyStore.hasApiKey(providerId))
        assertEquals("", keyStore.getApiKey(providerId))
    }

    @Test
    fun `room database persists provider chat message and attachment`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            db.providerDao().upsertProvider(
                ProviderEntity(
                    id = "p1",
                    type = "OPENAI",
                    displayName = "OpenAI Work",
                    protocol = "OPENAI_COMPATIBLE",
                    resolvedProtocol = "OPENAI_COMPATIBLE",
                    baseUrl = "https://api.openai.com/v1",
                    organizationId = "",
                    projectId = "",
                    customHeadersJson = "[]",
                    isEnabled = true,
                    status = "CONNECTED",
                    lastCheckedAt = 1000L,
                    lastCheckMessage = "OK",
                    selectedModelId = "gpt-4o",
                    sortOrder = 0,
                ),
            )
            db.chatDao().upsertChat(
                ChatEntity(
                    id = "c1",
                    title = "Analyze PDF",
                    providerId = "p1",
                    providerName = "OpenAI Work",
                    modelId = "gpt-4o",
                    isPinned = true,
                    isArchived = false,
                    createdAt = 1000L,
                    updatedAt = 2000L,
                ),
            )
            db.messageDao().upsertMessage(
                MessageEntity(
                    id = "m1",
                    chatId = "c1",
                    role = MessageRole.USER.name,
                    content = "Summarize attached document",
                    reasoningContent = "",
                    status = MessageStatus.COMPLETED.name,
                    providerId = "p1",
                    providerName = "OpenAI Work",
                    modelId = "gpt-4o",
                    errorJson = null,
                    generatedMediaJson = "[]",
                    createdAt = 2000L,
                    updatedAt = 2000L,
                ),
            )
            db.attachmentDao().upsertAttachments(
                listOf(
                    AttachmentEntity(
                        id = "a1",
                        messageId = "m1",
                        chatId = "c1",
                        type = AttachmentType.DOCUMENT.name,
                        displayName = "report.pdf",
                        mimeType = "application/pdf",
                        sizeBytes = 20480L,
                        localUri = "file:///tmp/report.pdf",
                        extractedText = "Quarterly metrics",
                        url = "",
                        pageTitle = "",
                    ),
                ),
            )

            val chats = db.chatDao().observeChats().first()
            assertEquals(1, chats.size)
            assertTrue(chats.first().isPinned)

            val attachments = db.attachmentDao().getAttachmentsForMessage("m1")
            assertEquals(1, attachments.size)
            assertEquals("report.pdf", attachments.first().displayName)
        } finally {
            db.close()
        }
    }
}
