package com.example.core.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.core.model.GeneratedMediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaStoreSaver @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun saveToGallery(
        localUriString: String,
        mediaType: GeneratedMediaType,
        displayName: String = "aichat_${System.currentTimeMillis()}",
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val sourceUri = Uri.parse(localUriString)
            val isVideo = mediaType == GeneratedMediaType.VIDEO
            val lowerPath = sourceUri.path.orEmpty().lowercase()
            val (mimeType, ext) = when {
                isVideo -> "video/mp4" to "mp4"
                lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg") -> "image/jpeg" to "jpg"
                lowerPath.endsWith(".webp") -> "image/webp" to "webp"
                lowerPath.endsWith(".gif") -> "image/gif" to "gif"
                else -> "image/png" to "png"
            }
            val collection = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.$ext")
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        if (isVideo) "${Environment.DIRECTORY_MOVIES}/AI Chat" else "${Environment.DIRECTORY_PICTURES}/AI Chat",
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val insertedUri = resolver.insert(collection, values)
                ?: error("Unable to create MediaStore entry")

            resolver.openOutputStream(insertedUri)?.use { out ->
                resolver.openInputStream(sourceUri)?.use { input ->
                    input.copyTo(out)
                } ?: error("Unable to read source media")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(insertedUri, values, null, null)
            }
            insertedUri
        }
    }

    fun getShareableUri(localUriString: String): Uri {
        val parsed = Uri.parse(localUriString)
        return if (parsed.scheme == "file") {
            val file = File(parsed.path.orEmpty())
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else {
            parsed
        }
    }

    fun createShareIntent(localUriString: String, mimeType: String, title: String): Intent {
        val contentUri = getShareableUri(localUriString)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(shareIntent, title)
    }

    fun createOpenFileIntent(localUriString: String, mimeType: String): Intent {
        val contentUri = getShareableUri(localUriString)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
