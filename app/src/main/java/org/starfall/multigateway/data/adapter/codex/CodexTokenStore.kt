package org.starfall.multigateway.data.adapter.codex

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class CodexTokenState(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresAt: Long? = null,
    val accountId: String? = null,
    val email: String? = null
)

internal class CodexTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "multigateway.codex.oauth",
        Context.MODE_PRIVATE
    )
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(providerId: String): CodexTokenState? {
        val encrypted = preferences.getString(providerId, null) ?: return null
        return runCatching {
            json.decodeFromString<CodexTokenState>(decrypt(encrypted))
        }.getOrNull()
    }

    fun save(providerId: String, token: CodexTokenState) {
        preferences.edit()
            .putString(providerId, encrypt(json.encodeToString(token)))
            .apply()
    }

    fun delete(providerId: String) {
        preferences.edit().remove(providerId).apply()
    }

    private fun key(create: Boolean): SecretKey {
        return runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            check(create) { "Codex credential key is unavailable." }

            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }.generateKey()
        }.getOrElse {
            fallbackKey ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { fallbackKey = it }
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(payload)
    }

    private fun decrypt(value: String): String {
        require(value.startsWith(PREFIX)) { "Unsupported Codex credential format." }
        val bytes = Base64.getDecoder().decode(value.removePrefix(PREFIX))
        require(bytes.size >= 28) { "Invalid Codex credential payload." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(false),
            GCMParameterSpec(128, bytes.copyOfRange(0, 12))
        )
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }

    private companion object {
        const val KEY_ALIAS = "multigateway.codex.oauth.v1"
        const val PREFIX = "keystore:v1:"
        var fallbackKey: SecretKey? = null
    }
}
