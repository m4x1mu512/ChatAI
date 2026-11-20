package com.example

import com.example.core.error.AiErrorCategory
import com.example.core.error.ErrorFormatter
import com.example.core.error.ErrorParser
import com.example.core.model.ApiProtocol
import com.example.core.model.CapabilityInferencer
import com.example.core.model.ModelCapability
import com.example.core.model.ProviderConfig
import com.example.core.model.ProviderType
import com.example.core.util.UrlDetector
import com.example.data.network.OpenAiCompatibleProvider
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

    @Test
    fun `parseModelsJson handles coarena payload with null fields and nested shapes`() {
        val sampleJson = """
            {
              "object": "list",
              "data": [
                {
                  "id": "gpt-4o",
                  "object": "model",
                  "owned_by": "openai",
                  "display_name": null,
                  "context_length": 128000,
                  "pricing": {"prompt": "0.0025"}
                },
                {
                  "id": "claude-3-7-sonnet",
                  "object": "model",
                  "owned_by": "anthropic",
                  "name": "Claude 3.7 Sonnet",
                  "context_window": null
                }
              ]
            }
        """.trimIndent()
        val parsed = OpenAiCompatibleProvider.parseModelsJson("coarena-1", sampleJson)
        assertEquals(2, parsed.size)
        assertEquals("claude-3-7-sonnet", parsed[0].modelId)
        assertEquals("Claude 3.7 Sonnet", parsed[0].displayName)
        assertEquals("gpt-4o", parsed[1].modelId)
        assertEquals(128000, parsed[1].contextWindow)

        val nestedJson = """{"data":{"models":["llama-3.3-70b","qwen-2.5-72b"]}}"""
        val nestedParsed = OpenAiCompatibleProvider.parseModelsJson("custom-1", nestedJson)
        assertEquals(listOf("llama-3.3-70b", "qwen-2.5-72b"), nestedParsed.map { it.modelId })
    }

    @Test
    fun `cloudflare resolves models search endpoint and parses cloudflare catalog`() {
        val cfConfig = ProviderConfig(
            id = "cf-1",
            type = ProviderType.CLOUDFLARE,
            displayName = "Cloudflare AI",
            protocol = ApiProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.cloudflare.com/client/v4/accounts/0123456789abcdef0123456789abcdef/ai/v1",
        )
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/0123456789abcdef0123456789abcdef/ai/models/search?per_page=100",
            OpenAiCompatibleProvider.resolveModelsEndpointUrl(cfConfig, cfConfig.baseUrl),
        )

        val cfCatalogJson = """
            {
              "result": [
                {
                  "id": "f2bc3f7a-0000-0000-0000-000000000001",
                  "name": "@cf/meta/llama-3.1-8b-instruct",
                  "task": {"id": "t1", "name": "Text Generation"}
                },
                {
                  "id": "f2bc3f7a-0000-0000-0000-000000000002",
                  "name": "@cf/black-forest-labs/flux-1-schnell",
                  "task": {"id": "t2", "name": "Text-to-Image"}
                },
                {
                  "id": "f2bc3f7a-0000-0000-0000-000000000003",
                  "name": "@cf/baai/bge-base-en-v1.5",
                  "task": {"id": "t3", "name": "Text Embeddings"}
                }
              ],
              "success": true,
              "errors": []
            }
        """.trimIndent()
        val parsed = OpenAiCompatibleProvider.parseModelsJson("cf-1", cfCatalogJson)
        assertEquals(2, parsed.size)
        assertEquals("@cf/black-forest-labs/flux-1-schnell", parsed[0].modelId)
        assertTrue(ModelCapability.IMAGE in parsed[0].capabilities)
        assertEquals("@cf/meta/llama-3.1-8b-instruct", parsed[1].modelId)
        assertTrue(ModelCapability.CHAT in parsed[1].capabilities)
    }

    @Test
    fun `error parser parses cloudflare errors array and placeholder account id`() {
        val err = ErrorParser.fromHttpResponse(
            statusCode = 405,
            body = """{"result":null,"success":false,"errors":[{"code":7001,"message":"GET not supported for requested URI."}],"messages":[]}""",
            providerName = "Cloudflare AI",
            modelId = "",
            endpointUrl = "https://api.cloudflare.com/client/v4/accounts/YOUR_ACCOUNT_ID/ai/v1/models",
        )
        assertEquals("7001", err.errorCode)
        assertEquals("GET not supported for requested URI.", err.providerMessage)
        assertEquals(AiErrorCategory.INVALID_BASE_URL, err.category)
    }
}
