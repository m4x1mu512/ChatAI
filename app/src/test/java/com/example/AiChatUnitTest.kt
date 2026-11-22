package com.example

import com.example.core.error.AiErrorCategory
import com.example.core.error.ErrorFormatter
import com.example.core.error.ErrorParser
import com.example.core.error.SecretRedactor
import com.example.core.model.ApiProtocol
import com.example.core.model.CapabilityInferencer
import com.example.core.model.ModelCapability
import com.example.core.model.ProviderConfig
import com.example.core.model.ProviderType
import com.example.core.util.UrlDetector
import com.example.data.github.GitHubValidator
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

    @Test
    fun `secret redactor masks classic and fine grained github tokens`() {
        val classic = "ghp_AbCdEfGhIjKlMnOpQrStUvWxYz123456"
        val fineGrained = "github_pat_11ABCDEFG0123456789_xyz987654321"
        val sample = "Auth failed: Authorization: Bearer $classic and $fineGrained"
        val redacted = SecretRedactor.redact(sample)
        assertFalse(redacted.contains(classic))
        assertFalse(redacted.contains(fineGrained))
        assertTrue(redacted.contains("Bearer ***"))
    }

    @Test
    fun `github validator sanitizes repos paths branches and encodes utf8`() {
        assertEquals("octocat" to "Hello-World", GitHubValidator.parseFullName(" octocat/Hello-World "))
        assertEquals(null, GitHubValidator.parseFullName("octocat/bad/repo"))
        assertEquals("src/main/App.kt", GitHubValidator.normalizePath("/src/main/App.kt/"))
        assertEquals("main", GitHubValidator.requireBranchOrRef("main"))
        val traversalBlocked = runCatching { GitHubValidator.normalizePath("../etc/passwd") }.isFailure
        val queryBlocked = runCatching { GitHubValidator.normalizePath("src/a.kt?foo=1") }.isFailure
        val refTraversalBlocked = runCatching { GitHubValidator.requireBranchOrRef("feature..bad") }.isFailure
        assertTrue(traversalBlocked)
        assertTrue(queryBlocked)
        assertTrue(refTraversalBlocked)

        val original = "Привет, GitHub! 🚀\nval x = 42"
        val encoded = GitHubValidator.encodeBase64Utf8(original)
        assertEquals(original, GitHubValidator.decodeBase64Utf8(encoded))
    }

    @Test
    fun `buildCandidateModelsUrls includes v1, gemini, and ollama fallbacks`() {
        val noV1Config = ProviderConfig(
            id = "p1",
            type = ProviderType.OPENAI,
            displayName = "OpenAI",
            protocol = ApiProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.openai.com",
        )
        assertEquals(
            listOf("https://api.openai.com/models", "https://api.openai.com/v1/models"),
            OpenAiCompatibleProvider.buildCandidateModelsUrls(noV1Config, "https://api.openai.com"),
        )

        val geminiRootConfig = ProviderConfig(
            id = "p2",
            type = ProviderType.GOOGLE_GEMINI,
            displayName = "Gemini",
            protocol = ApiProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        )
        assertEquals(
            listOf(
                "https://generativelanguage.googleapis.com/v1beta/models",
                "https://generativelanguage.googleapis.com/v1beta/openai/models",
            ),
            OpenAiCompatibleProvider.buildCandidateModelsUrls(
                geminiRootConfig,
                "https://generativelanguage.googleapis.com/v1beta",
            ),
        )

        val ollamaConfig = ProviderConfig(
            id = "p3",
            type = ProviderType.CUSTOM_OPENAI,
            displayName = "Ollama",
            protocol = ApiProtocol.OPENAI_COMPATIBLE,
            baseUrl = "http://localhost:11434/v1",
        )
        assertEquals(
            listOf("http://localhost:11434/v1/models", "http://localhost:11434/api/tags"),
            OpenAiCompatibleProvider.buildCandidateModelsUrls(ollamaConfig, "http://localhost:11434/v1"),
        )
    }

    @Test
    fun `parseModelsJson handles ollama tags and skips empty id shadowing model name`() {
        val ollamaTagsJson = """
            {
              "models": [
                {"name": "llama3.2:latest", "model": "llama3.2:latest"},
                {"id": "", "model": "qwen2.5-coder:7b", "display_name": "Qwen 2.5 Coder"}
              ]
            }
        """.trimIndent()
        val parsed = OpenAiCompatibleProvider.parseModelsJson("ollama-1", ollamaTagsJson)
        assertEquals(2, parsed.size)
        assertEquals("llama3.2:latest", parsed[0].modelId)
        assertEquals("qwen2.5-coder:7b", parsed[1].modelId)
        assertEquals("Qwen 2.5 Coder", parsed[1].displayName)
    }

    @Test
    fun `error parser parses gemini array wrapped 400 INVALID_ARGUMENT response`() {
        val body = """
            [{
              "error": {
                "code": 400,
                "message": "Request contains an invalid argument.",
                "status": "INVALID_ARGUMENT"
              }
            }]
        """.trimIndent()
        val err = ErrorParser.fromHttpResponse(
            statusCode = 400,
            body = body,
            providerName = "Google Gemini",
            modelId = "gemini-4-hydrogen",
            endpointUrl = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        )
        assertEquals("INVALID_ARGUMENT", err.errorCode)
        assertEquals("Request contains an invalid argument.", err.providerMessage)
    }

    @Test
    fun `gemini openai strips models prefix and filters embedding models`() {
        val geminiConfig = ProviderConfig(
            id = "gem-1",
            type = ProviderType.GOOGLE_GEMINI,
            displayName = "Google Gemini",
            protocol = ApiProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        )
        assertEquals(
            "gemini-4-hydrogen",
            OpenAiCompatibleProvider.normalizeModelIdForRequest(
                geminiConfig,
                geminiConfig.baseUrl,
                "models/gemini-4-hydrogen",
            ),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai",
            OpenAiCompatibleProvider.resolveOpenAiBaseUrl(
                geminiConfig,
                "https://generativelanguage.googleapis.com/v1beta",
            ),
        )

        val geminiModelsJson = """
            {
              "object": "list",
              "data": [
                {"id": "models/gemini-2.5-flash", "object": "model", "owned_by": "google"},
                {"id": "models/gemini-embedding-001", "object": "model", "owned_by": "google"},
                {"id": "models/aqa", "object": "model", "owned_by": "google"}
              ]
            }
        """.trimIndent()
        val parsed = OpenAiCompatibleProvider.parseModelsJson(
            providerId = "gem-1",
            bodyStr = geminiModelsJson,
            stripModelsPrefix = true,
        )
        assertEquals(listOf("gemini-2.5-flash"), parsed.map { it.modelId })
    }

    @Test
    fun `cloudflare resolves ai run image endpoint and parses flux json or binary stream`() {
        val cfConfig = ProviderConfig(
            id = "cf-img",
            type = ProviderType.CLOUDFLARE,
            displayName = "Cloudflare AI",
            protocol = ApiProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.cloudflare.com/client/v4/accounts/fc6bad7b047b201fcb4da85d0a00ecbe/ai/v1",
        )
        val (cfUrl, isCfRun) = OpenAiCompatibleProvider.resolveImageEndpointUrl(
            config = cfConfig,
            normalizedBase = cfConfig.baseUrl,
            modelId = "@cf/black-forest-labs/flux-1-schnell",
        )
        assertTrue(isCfRun)
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/fc6bad7b047b201fcb4da85d0a00ecbe/ai/run/@cf/black-forest-labs/flux-1-schnell",
            cfUrl,
        )

        val openAiConfig = ProviderConfig(
            id = "oa-img",
            type = ProviderType.OPENAI,
            displayName = "OpenAI",
            protocol = ApiProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.openai.com/v1",
        )
        val (oaUrl, isOaCf) = OpenAiCompatibleProvider.resolveImageEndpointUrl(
            config = openAiConfig,
            normalizedBase = openAiConfig.baseUrl,
            modelId = "dall-e-3",
        )
        assertFalse(isOaCf)
        assertEquals("https://api.openai.com/v1/images/generations", oaUrl)

        assertTrue(OpenAiCompatibleProvider.requiresCloudflareMultipartImageRequest("@cf/black-forest-labs/flux-2-dev"))
        assertFalse(OpenAiCompatibleProvider.requiresCloudflareMultipartImageRequest("@cf/black-forest-labs/flux-1-schnell"))

        // JPEG magic bytes inside Cloudflare JSON result.image
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        val jpegB64 = java.util.Base64.getEncoder().encodeToString(fakeJpeg)
        val cfResponseJson = """{"result":{"image":"$jpegB64"},"success":true,"errors":[],"messages":[]}"""
        assertTrue(OpenAiCompatibleProvider.isJsonResponse(cfResponseJson.toByteArray(), "application/json"))
        val extracted = OpenAiCompatibleProvider.extractImagePayloadFromJson(cfResponseJson)
        assertFalse(extracted.isExplicitFailure)
        assertEquals(jpegB64, extracted.base64)
        val decoded = OpenAiCompatibleProvider.decodeBase64ImageBytes(extracted.base64!!)
        assertEquals("jpg" to "image/jpeg", OpenAiCompatibleProvider.detectImageFormat(decoded))

        // Raw PNG binary stream (e.g. stable-diffusion-xl-base-1.0)
        val fakePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertFalse(OpenAiCompatibleProvider.isJsonResponse(fakePng, "image/png"))
        assertEquals("png" to "image/png", OpenAiCompatibleProvider.detectImageFormat(fakePng, "image/png"))

        // Explicit Cloudflare success=false failure payload
        val cfFailJson = """{"success":false,"errors":[{"code":7000,"message":"No route for that URI"}],"result":null}"""
        assertTrue(OpenAiCompatibleProvider.extractImagePayloadFromJson(cfFailJson).isExplicitFailure)
    }
}
