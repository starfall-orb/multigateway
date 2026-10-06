package org.starfall.multigateway.ui.providers

import org.starfall.multigateway.data.model.LlmProviderInfo

internal enum class ProviderSearchField { PROVIDER_NAME, FOLDER_NAME, MODEL_NAME }

internal fun matchesProviderSearch(provider: LlmProviderInfo, query: String, fields: Set<ProviderSearchField>): Boolean {
    val term = query.trim()
    if (term.isEmpty()) return true
    if (ProviderSearchField.PROVIDER_NAME in fields && provider.name.contains(term, ignoreCase = true)) return true
    if (ProviderSearchField.MODEL_NAME !in fields) return false
    val modelIds = provider.config.modelIds ?: provider.config.modelConfigs.keys.toList()
    return modelIds.any { id ->
        id.contains(term, ignoreCase = true) ||
            provider.config.modelConfigs[id]?.displayName?.contains(term, ignoreCase = true) == true
    }
}

internal fun matchesFolderSearch(name: String, query: String, fields: Set<ProviderSearchField>): Boolean =
    ProviderSearchField.FOLDER_NAME in fields && name.contains(query.trim(), ignoreCase = true)
