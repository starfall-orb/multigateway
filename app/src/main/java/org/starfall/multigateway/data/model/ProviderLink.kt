package org.starfall.multigateway.data.model

import android.net.Uri
import java.util.Locale
import java.util.UUID

/** Import a new provider; query parameter `key` is the credential value, not a header name. */
fun parseProviderLink(uri: Uri): LlmProviderInfo {
    require(uri.scheme.equals("multigateway", true) && uri.host.equals("provider", true) &&
        uri.path.orEmpty() in listOf("", "/")) { "Invalid provider link." }
    fun option(name: String, default: String) = uri.getQueryParameter(name)?.trim()
        ?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)?.replace('_', '-') ?: default
    val type = when (option("type", "chat-completions")) {
        "chat-completions", "openai", "openai-chat-completions" -> ProviderType.OPENAI
        "responses", "openai-responses" -> ProviderType.OPENAI_RESPONSES
        "anthropic", "messages", "anthropic-messages" -> ProviderType.ANTHROPIC
        "google", "gemini", "google-gemini" -> ProviderType.GOOGLE
        "ollama" -> ProviderType.OLLAMA
        "antigravity" -> ProviderType.ANTIGRAVITY
        "github-copilot" -> ProviderType.GITHUB_COPILOT
        "openai-codex" -> ProviderType.OPENAI_CODEX
        "claude-code" -> ProviderType.CLAUDE_CODE
        else -> throw IllegalArgumentException("Unsupported provider type in link.")
    }
    val method = when (option("auth", "platform")) {
        "platform", "platform-default" -> AuthMethod.PLATFORM_DEFAULT
        "none" -> AuthMethod.NONE
        "bearer", "bearer-token" -> AuthMethod.BEARER_TOKEN
        "query", "query-param" -> AuthMethod.QUERY_PARAM
        "header", "custom-header" -> AuthMethod.CUSTOM_HEADER
        "oauth", "oauth2" -> AuthMethod.OAUTH
        "other" -> AuthMethod.OTHER
        else -> throw IllegalArgumentException("Unsupported authentication method in link.")
    }
    val url = uri.getQueryParameter("url")?.trim()?.takeIf { it.isNotEmpty() } ?: type.defaultBaseUrl
    if (url.isNotEmpty()) {
        val parsed = Uri.parse(url)
        require(parsed.scheme?.lowercase(Locale.ROOT) in listOf("http", "https") && !parsed.host.isNullOrBlank()) {
            "Provider URL must be an HTTP or HTTPS address."
        }
    }
    val name = uri.getQueryParameter("name")?.trim()?.takeIf { it.isNotEmpty() }
        ?: type.defaultName.ifBlank { Uri.parse(url).host ?: "New provider" }
    val iconUrl = uri.getQueryParameter("icon_url")?.trim()?.takeIf { it.isNotEmpty() }
    if (iconUrl != null) {
        val parsed = Uri.parse(iconUrl)
        require(parsed.scheme?.lowercase(Locale.ROOT) in listOf("http", "https") && !parsed.host.isNullOrBlank()) {
            "Icon URL must be an HTTP or HTTPS address."
        }
    }
    return LlmProviderInfo(
        id = UUID.randomUUID().toString(),
        name = name,
        type = type,
        baseUrl = url,
        icon = iconUrl,
        auth = Authorization(
            method = method,
            key = when (method) {
                AuthMethod.BEARER_TOKEN, AuthMethod.CUSTOM_HEADER -> "Authorization"
                AuthMethod.QUERY_PARAM -> "key"
                else -> null
            },
            value = if (method in listOf(AuthMethod.NONE, AuthMethod.OAUTH)) "" else uri.getQueryParameter("key").orEmpty()
        )
    )
}
