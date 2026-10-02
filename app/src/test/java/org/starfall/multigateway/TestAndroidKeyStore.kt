package org.starfall.multigateway

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.*
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.*

/** JVM-only Keystore stand-in; production still requires Android Keystore. */
internal fun installTestAndroidKeyStore() {
    if (Security.getProvider("AndroidKeyStore") == null) {
        Security.addProvider(object : Provider("AndroidKeyStore", 1.0, "Test-only Android Keystore") {
            init {
                put("KeyStore.AndroidKeyStore", TestKeyStore::class.java.name)
                put("KeyGenerator.AES", TestKeyGenerator::class.java.name)
            }
        })
    }
}

private val testKeys = ConcurrentHashMap<String, Key>()

class TestKeyGenerator : KeyGeneratorSpi() {
    private var alias: String? = null
    override fun engineInit(random: SecureRandom?) = Unit
    override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
    override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
        alias = (params as? KeyGenParameterSpec)?.keystoreAlias
    }
    override fun engineGenerateKey(): SecretKey =
        KeyGenerator.getInstance("AES", "SunJCE").apply { init(256) }.generateKey().also { key ->
            alias?.let { testKeys[it] = key }
        }
}

class TestKeyStore : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): Key? = testKeys[alias]
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCreationDate(alias: String): Date? = null
    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<Certificate>?) { testKeys[alias] = key }
    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<Certificate>?) = Unit
    override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit
    override fun engineDeleteEntry(alias: String) { testKeys.remove(alias) }
    override fun engineAliases(): Enumeration<String> = Collections.enumeration(testKeys.keys)
    override fun engineContainsAlias(alias: String): Boolean = testKeys.containsKey(alias)
    override fun engineSize(): Int = testKeys.size
    override fun engineIsKeyEntry(alias: String): Boolean = testKeys.containsKey(alias)
    override fun engineIsCertificateEntry(alias: String): Boolean = false
    override fun engineGetCertificateAlias(cert: Certificate): String? = null
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
}
