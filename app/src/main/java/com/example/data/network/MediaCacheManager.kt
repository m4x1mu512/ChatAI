package com.example.data.network

import android.content.Context
import android.net.Uri
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaCacheManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    private val mediaDir: File
        get() = File(context.filesDir, "generated_media").apply { mkdirs() }

    suspend fun cacheBase64(base64Data: String, extension: String): String = withContext(Dispatchers.IO) {
        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
        val file = File(mediaDir, "media_${UUID.randomUUID()}.$extension")
        file.writeBytes(bytes)
        Uri.fromFile(file).toString()
    }

    suspend fun downloadAndCache(remoteUrl: String, extension: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(remoteUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}")
                }
                val body = response.body ?: error("Empty response body")
                val file = File(mediaDir, "media_${UUID.randomUUID()}.$extension")
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Uri.fromFile(file).toString()
            }
        }
    }
}
