package org.starfall.multigateway.data.adapter.antigravity

/** Canonical IDs and wire routes from vscode-unify-chat-provider's model resolver. */
internal object AntigravityModels {
    val available = listOf("gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.1-pro",
        "gemini-3-flash", "claude-sonnet-4-6", "claude-opus-4-6")

    fun resolve(model: String, effort: String? = null): String {
        val id = model.trim().removePrefix("models/")
        if (id.contains("claude")) {
            val base = id.removeSuffix("-thinking")
            return if (base.contains("opus")) "$base-thinking" else base
        }
        if (id in listOf("gemini-3-flash-agent", "gemini-3.5-flash-extra-low", "gemini-3.5-flash-low")) return id
        val suffix = Regex("-(minimal|low|medium|high)$").find(id)
        val base = suffix?.let { id.removeSuffix(it.value) } ?: id
        val level = effort?.takeIf { it in listOf("minimal", "low", "medium", "high") }
            ?: suffix?.groupValues?.get(1) ?: "high"
        return when (base) {
            "gemini-3.1-pro" -> if (level == "low") "gemini-3.1-pro-low" else "gemini-pro-agent"
            "gemini-3.5-flash" -> when (level) {
                "low", "minimal" -> "gemini-3.5-flash-extra-low"
                "medium" -> "gemini-3.5-flash-low"
                else -> "gemini-3-flash-agent"
            }
            "gemini-3.6-flash", "gemini-3.7-flash" -> "$base-${if (level == "minimal") "low" else level}"
            else -> id
        }
    }
}
