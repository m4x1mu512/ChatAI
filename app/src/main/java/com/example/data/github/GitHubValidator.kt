package com.example.data.github

import java.nio.charset.StandardCharsets
import java.util.Base64

object GitHubValidator {
    private val OWNER_REGEX = Regex("""^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$""")
    private val REPO_REGEX = Regex("""^[A-Za-z0-9._-]{1,100}$""")
    private val FORBIDDEN_REF_CHARS = Regex("""[\s?#\\~\^:*\[\]\u0000-\u001F\u007F]""")

    fun requireOwner(raw: String): String {
        val clean = raw.trim()
        require(OWNER_REGEX.matches(clean)) { "Invalid GitHub owner name" }
        return clean
    }

    fun requireRepo(raw: String): String {
        val clean = raw.trim()
        require(clean != "." && clean != ".." && REPO_REGEX.matches(clean)) {
            "Invalid GitHub repository name"
        }
        return clean
    }

    fun parseFullName(raw: String): Pair<String, String>? {
        val parts = raw.trim().split("/")
        if (parts.size != 2) return null
        val owner = runCatching { requireOwner(parts[0]) }.getOrNull() ?: return null
        val repo = runCatching { requireRepo(parts[1]) }.getOrNull() ?: return null
        return owner to repo
    }

    fun requireFullName(raw: String): Pair<String, String> =
        parseFullName(raw) ?: throw IllegalArgumentException("Repository must be in 'owner/repo' format")

    fun requireBranchOrRef(raw: String): String {
        val clean = raw.trim().trim('/')
        require(clean.isNotEmpty() && clean.length <= 200) { "Invalid branch or ref name" }
        require(!clean.contains("..") && !clean.contains("@{")) { "Invalid branch or ref sequence" }
        require(!FORBIDDEN_REF_CHARS.containsMatchIn(clean)) { "Branch or ref contains unsupported characters" }
        return clean
    }

    fun normalizePath(raw: String, allowEmpty: Boolean = false): String {
        val clean = raw.trim().trim('/')
        if (clean.isEmpty()) {
            require(allowEmpty) { "File path cannot be empty" }
            return ""
        }
        require(!clean.contains('\\') && !clean.contains('?') && !clean.contains('#') && !clean.contains('\u0000')) {
            "File path contains illegal characters"
        }
        val segments = clean.split('/')
        for (segment in segments) {
            require(segment.isNotEmpty() && segment != "." && segment != "..") {
                "Invalid path segment: $segment"
            }
        }
        return segments.joinToString("/")
    }

    fun encodeBase64Utf8(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8))

    fun decodeBase64Utf8(rawBase64: String): String {
        val normalized = rawBase64.replace("\n", "").replace("\r", "").trim()
        if (normalized.isEmpty()) return ""
        val decoded = Base64.getDecoder().decode(normalized)
        return String(decoded, StandardCharsets.UTF_8)
    }
}
