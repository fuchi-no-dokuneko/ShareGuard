package app.shareguard.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** AES-GCM key provider backed by Android Keystore. Key bytes are never requested or exported. */
class AndroidKeystoreAesGcmKeyProvider : AesGcmKeyProvider {
    private val lock = Any()
    private val keyStore: KeyStore by lazy {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (_: Exception) {
            throw KeyProviderOperationException()
        }
    }

    override fun getOrCreate(alias: KeyAlias): SecretKey = synchronized(lock) {
        getInternal(alias) ?: generate(alias)
    }

    override fun get(alias: KeyAlias): SecretKey? = synchronized(lock) { getInternal(alias) }

    override fun delete(alias: KeyAlias): LogicalKeyDeletionResult = synchronized(lock) {
        try {
            if (!keyStore.containsAlias(alias.value)) {
                LogicalKeyDeletionResult.ALREADY_ABSENT
            } else {
                keyStore.deleteEntry(alias.value)
                LogicalKeyDeletionResult.DELETED
            }
        } catch (_: Exception) {
            LogicalKeyDeletionResult.FAILED_BEST_EFFORT
        }
    }

    private fun getInternal(alias: KeyAlias): SecretKey? = try {
        keyStore.getKey(alias.value, null) as? SecretKey
    } catch (_: Exception) {
        throw KeyProviderOperationException()
    }

    private fun generate(alias: KeyAlias): SecretKey = try {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val specification = KeyGenParameterSpec.Builder(
            alias.value,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(specification)
        generator.generateKey()
    } catch (_: GeneralSecurityException) {
        throw KeyProviderOperationException()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_SIZE_BITS = 256
    }
}

class KeyProviderOperationException internal constructor() :
    GeneralSecurityException("Key provider operation failed")
