package org.starfall.multigateway.data.model

import kotlinx.serialization.Serializable

/** Only references and display metadata are stored here. Tokens stay in encrypted adapter storage. */
@Serializable
data class ProviderOAuthAccount(
    val id: String,
    val label: String = "",
    val identity: String? = null,
    val marker: String
) {
    fun authorization() = Authorization(AuthMethod.OAUTH, identity, marker, oauthAccountId = id)
}

internal val LlmProviderInfo.oauthCredentialId: String
    get() = auth.oauthAccountId?.takeIf { it.isNotBlank() } ?: id

internal fun LlmProviderInfo.oauthAccountsWithCurrent(): List<ProviderOAuthAccount> {
    val accounts = config.oauthAccounts
    if (auth.method != AuthMethod.OAUTH || auth.value.isNullOrBlank()) return accounts
    val account = ProviderOAuthAccount(oauthCredentialId, identity = auth.key, marker = auth.value)
    return if (accounts.any { it.id == account.id }) accounts else accounts + account
}

internal fun LlmProviderInfo.recordOAuthAccount(label: String? = null): LlmProviderInfo {
    val previous = config.oauthAccounts.firstOrNull { it.id == oauthCredentialId }
    val account = ProviderOAuthAccount(oauthCredentialId, label ?: previous?.label.orEmpty(), auth.key, auth.value.orEmpty())
    val accounts = oauthAccountsWithCurrent().map { if (it.id == account.id) account else it }
    return copy(config = config.copy(oauthAccounts = accounts))
}

internal fun LlmProviderInfo.removeOAuthAccount(accountId: String): LlmProviderInfo {
    val accounts = oauthAccountsWithCurrent().filterNot { it.id == accountId }
    val nextAuth = if (oauthCredentialId == accountId) {
        accounts.firstOrNull()?.authorization() ?: Authorization(AuthMethod.OAUTH)
    } else auth
    return copy(auth = nextAuth, config = config.copy(oauthAccounts = accounts))
}
