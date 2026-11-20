package com.example

import com.example.core.error.AiErrorCategory
import com.example.core.error.ErrorFormatter
import com.example.core.error.ErrorParser
import com.example.core.model.CapabilityInferencer
import com.example.core.model.ModelCapability
import com.example.core.model.ProviderType
import com.example.core.util.UrlDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiChatUnitTest {

    @Test
    fun `all 16 preset providers are defined`() {
        assertEquals(16, ProviderType.entries.size)
        assertTrue(ProviderType.entries.any { it == ProviderType.COARENA })
        assertTrue(ProviderType.entries.any { it == ProviderType.CUSTOM_OPENAI })
    }

    @Test
    fun `url detector extracts distinct http and https urls`() {
        val text = "Check https://example.com/docs, and also http://localhost:11434/v1! Repeat https://example.com/docs."
        val urls = UrlDetector.extractUrls(text)
        assertEquals(
            listOf("https://example.com/docs", "http://localhost:11434/v1"),
            urls,
        )
    }

    @Test
    fun `error parser categorizes 401 and scrubs api keys from diagnostics`() {
        val secretKey = "sk-supersecretkey123456789"
        val err = ErrorParser.fromHttpResponse(
            statusCode = 401,
            body = """{"error":{"code":"invalid_api_key","message":"Incorrect API key provided: $secretKey"}}""",
            providerName = "OpenAI",
            modelId = "gpt-4o",
            endpointUrl = "https://api.openai.com/v1/chat/completions?key=$secretKey",
            requestId = "req_999",
            activeApiKey = secretKey,
        )
        assertEquals(AiErrorCategory.INVALID_API_KEY, err.category)
        val report = ErrorFormatter.formatDiagnosticReport(err)
        assertFalse(report.contains(secretKey))
        assertTrue(report.contains("HTTP статус: 401"))
        assertTrue(report.contains("Провайдер: OpenAI"))
        assertTrue(report.contains("Request ID: req_999"))
    }

    @Test
    fun `capability inferencer assigns badges accurately`() {
        val visionCaps = CapabilityInferencer.inferCapabilities("gpt-4o")
        assertTrue(ModelCapability.CHAT in visionCaps)
        assertTrue(ModelCapability.VISION in visionCaps)
        assertTrue(ModelCapability.TOOLS in visionCaps)

        val reasonCaps = CapabilityInferencer.inferCapabilities("deepseek-r1")
        assertTrue(ModelCapability.REASONING in reasonCaps)

        val imgCaps = CapabilityInferencer.inferCapabilities("dall-e-3")
        assertTrue(ModelCapability.IMAGE in imgCaps)
        assertFalse(ModelCapability.CHAT in imgCaps)

        val videoCaps = CapabilityInferencer.inferCapabilities("sora-1")
        assertTrue(ModelCapability.VIDEO in videoCaps)
    }

    @Test
    fun `coarena uses official coarena ai api v1 base url`() {
        assertEquals("https://coarena.ai/api/v1", ProviderType.COARENA.defaultBaseUrl)
    }

    @Test
    fun `error parser detects cloudflare html 403 challenge`() {
        val err = ErrorParser.fromHttpResponse(
            statusCode = 403,
            body = "<!DOCTYPE html><html><head><title>Just a moment...</title></head><body>cf_chl_opt</body></html>",
            providerName = "Coarena",
            modelId = "",
            endpointUrl = "https://api.coarena.com/v1/models",
        )
        assertEquals(AiErrorCategory.INVALID_BASE_URL, err.category)
        assertEquals("WAF_CHALLENGE_HTTP_403", err.errorCode)
    }
}
