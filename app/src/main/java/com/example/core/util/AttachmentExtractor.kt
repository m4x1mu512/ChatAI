package com.example.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import android.util.Base64
import com.example.core.model.Attachment
import com.example.core.model.AttachmentType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.Inflater
import javax.inject.Inject
import javax.inject.Singleton

object UrlDetector {
    private val urlRegex = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""", RegexOption.IGNORE_CASE)

    fun extractUrls(text: String): List<String> {
        return urlRegex.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ')', ']', '!') }
            .distinct()
            .toList()
    }
}

@Singleton
class AttachmentExtractor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    suspend fun createImageAttachment(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val (name, size, mime) = queryMetadata(uri, "image.jpg", "image/jpeg")
        val targetDir = File(context.filesDir, "attachments").apply { mkdirs() }
        val ext = if (mime.contains("png")) "png" else "jpg"
        val destFile = File(targetDir, "img_${UUID.randomUUID()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }
        Attachment(
            id = UUID.randomUUID().toString(),
            type = AttachmentType.IMAGE,
            displayName = name,
            mimeType = mime,
            sizeBytes = destFile.length().takeIf { it > 0L } ?: size,
            localUri = Uri.fromFile(destFile).toString(),
        )
    }

    suspend fun createDocumentAttachment(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val (name, size, mime) = queryMetadata(uri, "document", "application/octet-stream")
        val targetDir = File(context.filesDir, "attachments").apply { mkdirs() }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destFile = File(targetDir, "doc_${UUID.randomUUID()}_$safeName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }
        val bytes = destFile.readBytes()
        val isPdf = mime.contains("pdf", ignoreCase = true) || name.endsWith(".pdf", ignoreCase = true)
        val isText = mime.startsWith("text/") ||
            name.endsWith(".txt", ignoreCase = true) ||
            name.endsWith(".md", ignoreCase = true) ||
            name.endsWith(".json", ignoreCase = true) ||
            name.endsWith(".csv", ignoreCase = true) ||
            name.endsWith(".kt", ignoreCase = true) ||
            name.endsWith(".py", ignoreCase = true) ||
            name.endsWith(".js", ignoreCase = true) ||
            name.endsWith(".xml", ignoreCase = true)

        val extracted = when {
            isPdf -> extractPdfText(bytes)
            isText -> bytes.toString(StandardCharsets.UTF_8).take(MAX_EXTRACTED_CHARS)
            else -> ""
        }

        Attachment(
            id = UUID.randomUUID().toString(),
            type = if (isText && !isPdf) AttachmentType.TEXT_FILE else AttachmentType.DOCUMENT,
            displayName = name,
            mimeType = if (isPdf) "application/pdf" else mime,
            sizeBytes = destFile.length().takeIf { it > 0L } ?: size,
            localUri = Uri.fromFile(destFile).toString(),
            extractedText = extracted,
        )
    }

    suspend fun createWebLinkAttachment(
        url: String,
        fetchContentLocally: Boolean,
    ): Attachment = withContext(Dispatchers.IO) {
        var pageTitle = ""
        var extracted = ""
        if (fetchContentLocally) {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "AIChat-Android/1.0")
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val html = response.body?.string().orEmpty()
                        pageTitle = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                            .find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                        val noScripts = html
                            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                        val plain = Html.fromHtml(noScripts, Html.FROM_HTML_MODE_COMPACT).toString()
                        extracted = plain.replace(Regex("\\s+"), " ").trim().take(MAX_EXTRACTED_CHARS)
                    }
                }
            }
        }
        Attachment(
            id = UUID.randomUUID().toString(),
            type = AttachmentType.WEB_LINK,
            displayName = pageTitle.ifBlank { url },
            mimeType = "text/uri-list",
            url = url,
            pageTitle = pageTitle,
            extractedText = extracted,
        )
    }

    suspend fun encodeImageAsBase64(localUriString: String, maxDimension: Int = 1600): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(localUriString)
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
            var sampleSize = 1
            while (options.outWidth / sampleSize > maxDimension || options.outHeight / sampleSize > maxDimension) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
                ?: return@withContext null
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 86, baos)
            bitmap.recycle()
            val encoded = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            "image/jpeg" to encoded
        }

    suspend fun readBytesBase64(localUriString: String): String? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(localUriString)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun queryMetadata(uri: Uri, fallbackName: String, fallbackMime: String): Triple<String, Long, String> {
        var name = fallbackName
        var size = 0L
        val mime = context.contentResolver.getType(uri) ?: fallbackMime
        if (uri.scheme == "file") {
            val file = File(uri.path.orEmpty())
            if (file.exists()) {
                return Triple(file.name, file.length(), mime)
            }
        }
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: fallbackName
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        return Triple(name, size, mime)
    }

    internal fun extractPdfText(pdfBytes: ByteArray): String {
        val raw = String(pdfBytes, StandardCharsets.ISO_8859_1)
        val sb = StringBuilder()

        // Extract text from uncompressed or FlateDecode PDF streams
        val streamRegex = Regex("""stream\r?\n(.*?)\r?\nendstream""", RegexOption.DOT_MATCHES_ALL)
        for (match in streamRegex.findAll(raw)) {
            val streamStr = match.groupValues[1]
            val streamBytes = streamStr.toByteArray(StandardCharsets.ISO_8859_1)
            val content = tryInflate(streamBytes) ?: streamStr
            extractTextOperators(content, sb)
            if (sb.length >= MAX_EXTRACTED_CHARS) break
        }
        if (sb.isBlank()) {
            extractTextOperators(raw, sb)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim().take(MAX_EXTRACTED_CHARS)
    }

    private fun tryInflate(bytes: ByteArray): String? {
        return runCatching {
            val inflater = Inflater()
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) break
                out.write(buffer, 0, count)
                if (out.size() > 2_000_000) break
            }
            inflater.end()
            if (out.size() > 0) String(out.toByteArray(), StandardCharsets.ISO_8859_1) else null
        }.getOrNull()
    }

    private fun extractTextOperators(content: String, out: StringBuilder) {
        val tjRegex = Regex("""\(([^()\\]*(?:\\.[^()\\]*)*)\)\s*Tj""")
        for (m in tjRegex.findAll(content)) {
            out.append(unescapePdfString(m.groupValues[1])).append(' ')
        }
        val tjArrayRegex = Regex("""\[(.*?)\]\s*TJ""", RegexOption.DOT_MATCHES_ALL)
        val innerParenRegex = Regex("""\(([^()\\]*(?:\\.[^()\\]*)*)\)""")
        for (arr in tjArrayRegex.findAll(content)) {
            for (item in innerParenRegex.findAll(arr.groupValues[1])) {
                out.append(unescapePdfString(item.groupValues[1]))
            }
            out.append(' ')
        }
    }

    private fun unescapePdfString(s: String): String {
        return s.replace("\\n", "\n")
            .replace("\\r", " ")
            .replace("\\t", " ")
            .replace("\\(", "(")
            .replace("\\)", ")")
            .replace("\\\\", "\\")
    }

    companion object {
        private const val MAX_EXTRACTED_CHARS = 24_000
    }
}
