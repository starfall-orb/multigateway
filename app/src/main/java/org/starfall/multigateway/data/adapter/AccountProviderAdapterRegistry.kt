package org.starfall.multigateway.data.adapter

import android.content.Context
import org.starfall.multigateway.data.adapter.codex.OpenAICodexAdapter
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.data.service.AttachmentResolver

/** Single integration point between core services and removable account adapters. */
internal class AccountProviderAdapterRegistry(
    context: Context,
    attachments: AttachmentResolver
) {
    private val adapters: Map<ProviderType, AccountProviderAdapter> = listOf(
        OpenAICodexAdapter(context, attachments)
    ).associateBy { it.providerType }

    fun get(type: ProviderType): AccountProviderAdapter? = adapters[type]

    fun clearCredentials(type: ProviderType, providerId: String) {
        adapters[type]?.clearCredentials(providerId)
    }
}
