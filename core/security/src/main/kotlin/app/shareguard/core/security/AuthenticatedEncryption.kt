package app.shareguard.core.security

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val KEY_ALIAS_PATTERN = Regex("[A-Za-z][A-Za-z0-9_.-]{0,95}")

/** An opaque application-scoped key name. It must not contain account, device, or content identifiers. */
@JvmInline
value class KeyAlias(val value: String) {
    init {
        require(KEY_ALIAS_PATTERN.matches(value)) { "Key alias must be an opaque non-path identifier" }
    }

    override fun toString(): String = "KeyAlias(redacted)"
}

enum class LogicalKeyDeletionResult {
    DELETED,
    ALREADY_ABSENT,
    FAILED_BEST_EFFORT,
}

/**
 * Returns key handles without requiring key bytes to be exportable. Android Keystore implementations can
 * therefore keep material outside the application process, while JVM tests can use an in-memory provider.
 */
interface AesGcmKeyProvider : AutoCloseable {
    fun getOrCreate(alias: KeyAlias): SecretKey
    fun get(alias: KeyAlias): SecretKey?

    /** Logical deletion only; no physical sanitization is claimed. */
    fun delete(alias: KeyAlias): LogicalKeyDeletionResult

    override fun close() = Unit
}

fun interface NonceGenerator {
    fun generate(byteCount: Int): ByteArray
}

class SecureRandomNonceGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
) : NonceGenerator {
    override fun generate(byteCount: Int): ByteArray = ByteArray(byteCount).also(secureRandom::nextBytes)
}

/** Immutable-by-copy AES-GCM envelope. Ciphertext includes the authentication tag. */
class AuthenticatedCiphertext private constructor(
    val formatVersion: Int,
    nonce: ByteArray,
    encrypted: ByteArray,
) {
    private val nonceBytes = nonce.copyOf()
    private val encryptedBytes = encrypted.copyOf()

    val algorithm: String
        get() = ALGORITHM_LABEL

    val nonceSize: Int
        get() = nonceBytes.size

    val encryptedSize: Int
        get() = encryptedBytes.size

    fun nonceCopy(): ByteArray = nonceBytes.copyOf()
    fun encryptedCopy(): ByteArray = encryptedBytes.copyOf()

    internal fun <T> access(block: (nonce: ByteArray, encrypted: ByteArray) -> T): T =
        block(nonceBytes, encryptedBytes)

    override fun toString(): String =
        "AuthenticatedCiphertext(formatVersion=$formatVersion, algorithm=$ALGORITHM_LABEL, payload=redacted)"

    companion object {
        const val CURRENT_FORMAT_VERSION: Int = 1
        const val NONCE_BYTES: Int = 12
        const val TAG_BYTES: Int = 16
        const val ALGORITHM_LABEL: String = "AES-256-GCM"

        fun create(
            nonce: ByteArray,
            encrypted: ByteArray,
            formatVersion: Int = CURRENT_FORMAT_VERSION,
        ): AuthenticatedCiphertext {
            require(formatVersion == CURRENT_FORMAT_VERSION) { "Unsupported ciphertext format" }
            require(nonce.size == NONCE_BYTES) { "AES-GCM nonce must be 96 bits" }
            require(encrypted.size >= TAG_BYTES) { "Ciphertext must contain an authentication tag" }
            return AuthenticatedCiphertext(formatVersion, nonce, encrypted)
        }
    }
}

interface AuthenticatedEncryption {
    fun encrypt(
        alias: KeyAlias,
        plaintext: ByteArray,
        associatedData: ByteArray = byteArrayOf(),
    ): AuthenticatedCiphertext

    @Throws(AuthenticationFailedException::class, KeyUnavailableException::class)
    fun decrypt(
        alias: KeyAlias,
        ciphertext: AuthenticatedCiphertext,
        associatedData: ByteArray = byteArrayOf(),
    ): ByteArray
}

class AuthenticationFailedException internal constructor() :
    GeneralSecurityException("Ciphertext authentication failed")

class KeyUnavailableException internal constructor() :
    GeneralSecurityException("Encryption key is unavailable")

class CryptographicOperationException internal constructor() :
    GeneralSecurityException("Cryptographic operation failed")

class AesGcmAuthenticatedEncryption(
    private val keyProvider: AesGcmKeyProvider,
    private val nonceGenerator: NonceGenerator = SecureRandomNonceGenerator(),
) : AuthenticatedEncryption {
    override fun encrypt(
        alias: KeyAlias,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): AuthenticatedCiphertext {
        val nonce = nonceGenerator.generate(AuthenticatedCiphertext.NONCE_BYTES)
        require(nonce.size == AuthenticatedCiphertext.NONCE_BYTES) { "Nonce generator returned the wrong size" }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                keyProvider.getOrCreate(alias),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
            val encrypted = cipher.doFinal(plaintext)
            try {
                AuthenticatedCiphertext.create(nonce, encrypted)
            } finally {
                encrypted.fill(0)
            }
        } catch (exception: GeneralSecurityException) {
            throw CryptographicOperationException()
        } finally {
            nonce.fill(0)
        }
    }

    override fun decrypt(
        alias: KeyAlias,
        ciphertext: AuthenticatedCiphertext,
        associatedData: ByteArray,
    ): ByteArray {
        if (ciphertext.formatVersion != AuthenticatedCiphertext.CURRENT_FORMAT_VERSION) {
            throw AuthenticationFailedException()
        }
        val key = keyProvider.get(alias) ?: throw KeyUnavailableException()
        return try {
            ciphertext.access { nonce, encrypted ->
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
                cipher.doFinal(encrypted)
            }
        } catch (exception: AEADBadTagException) {
            throw AuthenticationFailedException()
        } catch (exception: GeneralSecurityException) {
            throw CryptographicOperationException()
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = AuthenticatedCiphertext.TAG_BYTES * 8
    }
}
