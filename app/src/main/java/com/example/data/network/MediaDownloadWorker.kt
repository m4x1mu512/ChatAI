package com.example.data.network

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.example.core.model.GeneratedMedia
import com.example.data.local.MessageDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@HiltWorker
class MediaDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val mediaCacheManager: MediaCacheManager,
    private val messageDao: MessageDao,
) : CoroutineWorker(appContext, workerParams) {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: return Result.failure()
        val mediaId = inputData.getString(KEY_MEDIA_ID) ?: return Result.failure()
        val remoteUrl = inputData.getString(KEY_REMOTE_URL) ?: return Result.failure()
        val extension = inputData.getString(KEY_EXTENSION) ?: "png"

        val downloadResult = mediaCacheManager.downloadAndCache(remoteUrl, extension)
        return downloadResult.fold(
            onSuccess = { localUri ->
                val entity = messageDao.getMessage(messageId)
                if (entity != null) {
                    val mediaList = runCatching {
                        json.decodeFromString<List<GeneratedMedia>>(entity.generatedMediaJson)
                    }.getOrDefault(emptyList())
                    val updated = mediaList.map { media ->
                        if (media.id == mediaId) media.copy(localUri = localUri, statusText = "Cached") else media
                    }
                    messageDao.upsertMessage(
                        entity.copy(
                            generatedMediaJson = json.encodeToString(updated),
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                Result.success(Data.Builder().putString(KEY_LOCAL_URI, localUri).build())
            },
            onFailure = {
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            },
        )
    }

    companion object {
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_MEDIA_ID = "media_id"
        const val KEY_REMOTE_URL = "remote_url"
        const val KEY_EXTENSION = "extension"
        const val KEY_LOCAL_URI = "local_uri"
    }
}
