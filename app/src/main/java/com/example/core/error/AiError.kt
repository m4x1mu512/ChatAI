package com.example.core.error

import android.content.Context
import com.example.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.format.DateTimeFormatter

@Serializable
enum class AiErrorCategory {
    NO_INTERNET,
    INVALID_API_KEY,
    EXPIRED_OR_REVOKED_KEY,
    RATE_LIMIT,
    INSUFFICIENT_CREDITS,
    UNSUPPORTED_MODEL,
    PAYLOAD_TOO_LARGE,
    UNSUPPORTED_ATTACHMENT,
    TIMEOUT,
    SERVER_ERROR,
    INVALID_BASE_URL,
    UNKNOWN,
}

@Serializable
data class AiError(
    val timestampIso: String = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
    val providerName: String,
    val modelId: String = "",
    val endpointUrl: String = "",
    val httpStatus: Int? = null,
    val errorCode: String = "",
    val providerMessage: String = "",
    val requestId: String = "",
    val technicalDetails: String = "",
    val category: AiErrorCategory = AiErrorCategory.UNKNOWN,
)

class AiException(val aiError: AiError) : Exception(aiError.providerMessage.ifBlank { aiError.category.name })

object SecretRedactor {
    private val keyPatterns = listOf(
        Regex("""(?i)(Bearer\s+)[A-Za-z0-9._\-/+]+=*"""),
        Regex("""(?i)(x-api-key["':=\s]+)[A-Za-z0-9._\-/+]+=*"""),
        Regex("""\b(sk-[A-Za-z0-9\-_]{8,})\b"""),
        Regex("""\b(sk-ant-[A-Za-z0-9\-_]{8,})\b"""),
        Regex("""\b(AIza[A-Za-z0-9\-_]{10,})\b"""),
        Regex("""\b(hf_[A-Za-z0-9]{8,})\b"""),
        Regex("""\b(gsk_[A-Za-z0-9]{8,})\b"""),
        Regex("""\b(pplx-[A-Za-z0-9]{8,})\b"""),
        Regex("""\b(xai-[A-Za-z0-9]{8,})\b"""),
    )

    fun redact(text: String, activeKey: String = ""): String {
        var result = text
        if (activeKey.isNotBlank() && activeKey.length >= 4) {
            result = result.replace(activeKey, "***")
        }
        for (regex in keyPatterns) {
            result = regex.replace(result) { match ->
                if (match.groupValues.size > 1 && regex.pattern.contains("Bearer", ignoreCase = true) ||
                    regex.pattern.contains("x-api-key", ignoreCase = true)
                ) {
                    "${match.groupValues[1]}***"
                } else {
                    "***"
                }
            }
        }
        return result
    }

    fun sanitizeUrl(rawUrl: String): String {
        return rawUrl.replace(Regex("""([?&](?:key|api_key|token|access_token)=)[^&]+""", RegexOption.IGNORE_CASE), "$1***")
    }
}

object ErrorParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun fromHttpResponse(
        statusCode: Int,
        body: String,
        providerName: String,
        modelId: String,
        endpointUrl: String,
        requestId: String = "",
        activeApiKey: String = "",
    ): AiError {
        val cleanBody = SecretRedactor.redact(body.take(4000), activeApiKey)
        val cleanEndpoint = SecretRedactor.sanitizeUrl(SecretRedactor.redact(endpointUrl, activeApiKey))

        var parsedCode = ""
        var parsedMessage = ""

        runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val errorObj = root["error"]?.jsonObject
            if (errorObj != null) {
                parsedCode = errorObj["code"]?.jsonPrimitive?.content
                    ?: errorObj["type"]?.jsonPrimitive?.content.orEmpty()
                parsedMessage = errorObj["message"]?.jsonPrimitive?.content.orEmpty()
            } else {
                parsedMessage = root["message"]?.jsonPrimitive?.content
                    ?: root["detail"]?.jsonPrimitive?.content.orEmpty()
                parsedCode = root["code"]?.jsonPrimitive?.content.orEmpty()
            }
        }

        val isHtmlChallenge = body.trimStart().startsWith("<!doctype", ignoreCase = true) ||
            body.trimStart().startsWith("<html", ignoreCase = true) ||
            body.contains("cf_chl_opt") ||
            body.contains("Just a moment...", ignoreCase = true)

        if (isHtmlChallenge && parsedMessage.isBlank()) {
            parsedCode = "WAF_CHALLENGE_HTTP_$statusCode"
            parsedMessage = "HTTP $statusCode: Blocked by Cloudflare/WAF or wrong Base URL"
        }

        val messageLower = "$parsedCode $parsedMessage $cleanBody".lowercase()
        val category = when {
            isHtmlChallenge && (statusCode == 403 || statusCode == 503) ->
                AiErrorCategory.INVALID_BASE_URL
            statusCode == 401 && (messageLower.contains("expired") || messageLower.contains("revoked")) ->
                AiErrorCategory.EXPIRED_OR_REVOKED_KEY
            statusCode == 401 || messageLower.contains("invalid_api_key") || messageLower.contains("incorrect api key") ->
                AiErrorCategory.INVALID_API_KEY
            statusCode == 402 || messageLower.contains("insufficient_quota") ||
                messageLower.contains("credit") || messageLower.contains("billing") ||
                messageLower.contains("balance") ->
                AiErrorCategory.INSUFFICIENT_CREDITS
            statusCode == 429 || messageLower.contains("rate_limit") || messageLower.contains("too many requests") ->
                AiErrorCategory.RATE_LIMIT
            statusCode == 413 || messageLower.contains("too large") || messageLower.contains("payload") ||
                messageLower.contains("context_length_exceeded") ->
                AiErrorCategory.PAYLOAD_TOO_LARGE
            statusCode == 404 || messageLower.contains("model_not_found") ||
                (messageLower.contains("model") && messageLower.contains("not found")) ||
                messageLower.contains("unsupported model") ->
                AiErrorCategory.UNSUPPORTED_MODEL
            statusCode == 400 && (messageLower.contains("image") || messageLower.contains("attachment") ||
                messageLower.contains("media_type") || messageLower.contains("vision") ||
                messageLower.contains("document")) ->
                AiErrorCategory.UNSUPPORTED_ATTACHMENT
            statusCode == 408 || statusCode == 504 -> AiErrorCategory.TIMEOUT
            statusCode in 500..599 -> AiErrorCategory.SERVER_ERROR
            statusCode == 403 && messageLower.contains("terms") -> AiErrorCategory.UNKNOWN
            statusCode == 403 -> AiErrorCategory.INVALID_API_KEY
            else -> AiErrorCategory.UNKNOWN
        }

        return AiError(
            providerName = providerName,
            modelId = modelId,
            endpointUrl = cleanEndpoint,
            httpStatus = statusCode,
            errorCode = parsedCode.ifBlank { "HTTP_$statusCode" },
            providerMessage = SecretRedactor.redact(parsedMessage.ifBlank { "HTTP $statusCode" }, activeApiKey),
            requestId = requestId,
            technicalDetails = cleanBody,
            category = category,
        )
    }

    fun fromThrowable(
        throwable: Throwable,
        providerName: String,
        modelId: String = "",
        endpointUrl: String = "",
        activeApiKey: String = "",
    ): AiError {
        if (throwable is AiException) return throwable.aiError

        val cleanEndpoint = SecretRedactor.sanitizeUrl(SecretRedactor.redact(endpointUrl, activeApiKey))
        val cleanDetails = SecretRedactor.redact(
            throwable.stackTraceToString().take(3000),
            activeApiKey,
        )
        val rawMsg = SecretRedactor.redact(throwable.message.orEmpty(), activeApiKey)

        val category = when (throwable) {
            is MalformedURLException, is IllegalArgumentException -> {
                if (rawMsg.contains("url", ignoreCase = true) ||
                    rawMsg.contains("scheme", ignoreCase = true) ||
                    rawMsg.contains("host", ignoreCase = true)
                ) {
                    AiErrorCategory.INVALID_BASE_URL
                } else {
                    AiErrorCategory.UNKNOWN
                }
            }
            is SocketTimeoutException -> AiErrorCategory.TIMEOUT
            is UnknownHostException, is ConnectException -> AiErrorCategory.NO_INTERNET
            is IOException -> {
                val lower = rawMsg.lowercase()
                when {
                    lower.contains("timeout") -> AiErrorCategory.TIMEOUT
                    lower.contains("unable to resolve host") || lower.contains("failed to connect") ||
                        lower.contains("network") -> AiErrorCategory.NO_INTERNET
                    else -> AiErrorCategory.UNKNOWN
                }
            }
            else -> AiErrorCategory.UNKNOWN
        }

        return AiError(
            providerName = providerName,
            modelId = modelId,
            endpointUrl = cleanEndpoint,
            httpStatus = null,
            errorCode = throwable::class.simpleName ?: "ERROR",
            providerMessage = rawMsg.ifBlank { throwable::class.simpleName ?: "Unknown error" },
            requestId = "",
            technicalDetails = cleanDetails,
            category = category,
        )
    }
}

object ErrorFormatter {
    fun userExplanation(context: Context, error: AiError): String {
        return when (error.category) {
            AiErrorCategory.NO_INTERNET -> context.getString(R.string.error_no_internet)
            AiErrorCategory.INVALID_API_KEY -> context.getString(R.string.error_invalid_api_key)
            AiErrorCategory.EXPIRED_OR_REVOKED_KEY -> context.getString(R.string.error_expired_key)
            AiErrorCategory.RATE_LIMIT -> context.getString(R.string.error_rate_limit)
            AiErrorCategory.INSUFFICIENT_CREDITS -> context.getString(R.string.error_insufficient_credits)
            AiErrorCategory.UNSUPPORTED_MODEL -> context.getString(R.string.error_unsupported_model)
            AiErrorCategory.PAYLOAD_TOO_LARGE -> context.getString(R.string.error_payload_too_large)
            AiErrorCategory.UNSUPPORTED_ATTACHMENT -> context.getString(R.string.error_unsupported_attachment)
            AiErrorCategory.TIMEOUT -> context.getString(R.string.error_timeout)
            AiErrorCategory.SERVER_ERROR -> context.getString(R.string.error_server)
            AiErrorCategory.INVALID_BASE_URL -> context.getString(R.string.error_invalid_base_url)
            AiErrorCategory.UNKNOWN -> context.getString(R.string.error_unknown)
        }
    }

    fun formatDiagnosticReport(error: AiError): String {
        val statusText = error.httpStatus?.toString() ?: "N/A"
        val codeText = error.errorCode.ifBlank { "N/A" }
        val msgText = error.providerMessage.ifBlank { "N/A" }
        val reqIdText = error.requestId.ifBlank { "N/A" }
        val detailsText = error.technicalDetails.ifBlank { "N/A" }
        val endpointText = error.endpointUrl.ifBlank { "N/A" }
        val modelText = error.modelId.ifBlank { "N/A" }

        return buildString {
            appendLine("Время: ${error.timestampIso}")
            appendLine("Провайдер: ${error.providerName}")
            appendLine("Модель: $modelText")
            appendLine("Endpoint: $endpointText")
            appendLine("HTTP статус: $statusText")
            appendLine("Код ошибки: $codeText")
            appendLine("Сообщение: $msgText")
            appendLine("Request ID: $reqIdText")
            append("Технические детали: $detailsText")
        }
    }
}
