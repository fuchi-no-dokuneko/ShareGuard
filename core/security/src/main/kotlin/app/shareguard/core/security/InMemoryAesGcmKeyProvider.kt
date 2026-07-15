package app.shareguard.core.security

import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

fun interface KeyByteGenerator {
    fun generate(byteCount: Int): ByteArray
}

class SecureRandomKeyByteGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
) : KeyByteGenerator {
    override fun generate(byteCount: Int): ByteArray = ByteArray(byteCount).also(secureRandom::nextBytes)
}

/**
 * JVM-testable key provider. This provider is process-local and is not a substitute for Android Keystore
 * when keys must survive process death or protect durable ciphertext.
 */
class InMemoryAesGcmKeyProvider(
    private val keyByteGenerator: KeyByteGenerator = SecureRandomKeyByteGenerator(),
) : AesGcmKeyProvider {
    private val lock = Any()
    private val keys = mutableMapOf<KeyAlias, SecretBytes>()
    private var closed = false

    override fun getOrCreate(alias: KeyAlias): SecretKey = synchronized(lock) {
        check(!closed) { "Key provider is closed" }
        val material = keys[alias] ?: createMaterial().also { keys[alias] = it }
        material.toSecretKey()
    }

    override fun get(alias: KeyAlias): SecretKey? = synchronized(lock) {
        check(!closed) { "Key provider is closed" }
        keys[alias]?.toSecretKey()
    }

    override fun delete(alias: KeyAlias): LogicalKeyDeletionResult = synchronized(lock) {
        if (closed) return@synchronized LogicalKeyDeletionResult.FAILED_BEST_EFFORT
        val removed = keys.remove(alias) ?: return@synchronized LogicalKeyDeletionResult.ALREADY_ABSENT
        return@synchronized try {
            removed.close()
            LogicalKeyDeletionResult.DELETED
        } catch (_: RuntimeException) {
            LogicalKeyDeletionResult.FAILED_BEST_EFFORT
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            keys.values.forEach { material ->
                runCatching { material.close() }
            }
            keys.clear()
            closed = true
        }
    }

    internal fun keyCountForTest(): Int = synchronized(lock) { keys.size }

    private fun createMaterial(): SecretBytes {
        val generated = keyByteGenerator.generate(KEY_BYTES)
        return try {
            require(generated.size == KEY_BYTES) { "Key generator returned the wrong size" }
            SecretBytes.copyOf(generated)
        } finally {
            generated.fill(0)
        }
    }

    private fun SecretBytes.toSecretKey(): SecretKey = access { bytes -> SecretKeySpec(bytes, "AES") }

    private companion object {
        const val KEY_BYTES = 32
    }
}
