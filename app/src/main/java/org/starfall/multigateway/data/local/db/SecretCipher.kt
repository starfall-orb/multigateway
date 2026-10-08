package org.starfall.multigateway.data.local.db

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned envelopes; key material never leaves Android Keystore. */
internal object SecretCipher {
    private const val PREFIX = "keystore:v1:"
    private const val ALIAS = "multigateway.credentials.v1"
    // The AndroidKeyStore handle is just a reference to the non-exportable key, safe to reuse.
    // Looking it up on every call costs a Keystore IPC round trip per decrypt.
    @Volatile private var cachedKey: SecretKey? = null
    @Synchronized private fun key(create: Boolean): SecretKey {
        cachedKey?.let { return it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { cachedKey = it; return it }
        check(create) { "Credential key unavailable. Restore access on the original device." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey().also { cachedKey = it }
    }
    fun isEncrypted(value: String) = value.startsWith(PREFIX)
    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        return PREFIX + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }
    fun decrypt(value: String): String {
        if (!isEncrypted(value)) return value // Legacy records are upgraded transactionally at database open.
        try {
            val bytes = Base64.getDecoder().decode(value.removePrefix(PREFIX))
            require(bytes.size >= 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalStateException("Could not decrypt saved credentials; existing data has been retained.", e)
        }
    }
}
