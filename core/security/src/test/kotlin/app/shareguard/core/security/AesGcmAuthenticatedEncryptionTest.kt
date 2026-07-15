package app.shareguard.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class AesGcmAuthenticatedEncryptionTest {
    private val alias = KeyAlias("session-key")

    @Test
    fun `round trip authenticates plaintext and associated data`() {
        val provider = provider()
        val encryption = AesGcmAuthenticatedEncryption(provider, IncrementingNonceGenerator())
        val plaintext = "private session payload".encodeToByteArray()
        val aad = "format-v1".encodeToByteArray()

        val envelope = encryption.encrypt(alias, plaintext, aad)
        val restored = encryption.decrypt(alias, envelope, aad)

        assertThat(restored.asList()).isEqualTo(plaintext.asList())
        assertThat(envelope.algorithm).isEqualTo("AES-256-GCM")
        assertThat(envelope.nonceSize).isEqualTo(12)
        assertThat(envelope.encryptedSize).isEqualTo(plaintext.size + 16)
        assertThat(envelope.toString()).doesNotContain("private session payload")
        provider.close()
    }

    @Test
    fun `same plaintext receives a fresh nonce and ciphertext`() {
        val provider = provider()
        val encryption = AesGcmAuthenticatedEncryption(provider, IncrementingNonceGenerator())
        val plaintext = "same".encodeToByteArray()

        val first = encryption.encrypt(alias, plaintext)
        val second = encryption.encrypt(alias, plaintext)

        assertThat(first.nonceCopy().asList()).isNotEqualTo(second.nonceCopy().asList())
        assertThat(first.encryptedCopy().asList()).isNotEqualTo(second.encryptedCopy().asList())
        provider.close()
    }

    @Test
    fun `ciphertext mutation is rejected without returning partial plaintext`() {
        val provider = provider()
        val encryption = AesGcmAuthenticatedEncryption(provider, IncrementingNonceGenerator())
        val envelope = encryption.encrypt(alias, "do not disclose".encodeToByteArray())
        val tampered = envelope.encryptedCopy().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val alteredEnvelope = AuthenticatedCiphertext.create(envelope.nonceCopy(), tampered)

        assertThrows(AuthenticationFailedException::class.java) {
            encryption.decrypt(alias, alteredEnvelope)
        }
        provider.close()
    }

    @Test
    fun `wrong associated data is rejected`() {
        val provider = provider()
        val encryption = AesGcmAuthenticatedEncryption(provider, IncrementingNonceGenerator())
        val envelope = encryption.encrypt(alias, "payload".encodeToByteArray(), "right".encodeToByteArray())

        assertThrows(AuthenticationFailedException::class.java) {
            encryption.decrypt(alias, envelope, "wrong".encodeToByteArray())
        }
        provider.close()
    }

    @Test
    fun `deleted or different key cannot decrypt`() {
        val provider = provider()
        val encryption = AesGcmAuthenticatedEncryption(provider, IncrementingNonceGenerator())
        val envelope = encryption.encrypt(alias, "payload".encodeToByteArray())

        provider.delete(alias)

        assertThrows(KeyUnavailableException::class.java) {
            encryption.decrypt(alias, envelope)
        }
        provider.close()
    }

    @Test
    fun `available but wrong key fails authentication rather than exposing ciphertext`() {
        val firstProvider = provider(startingByte = 1)
        val secondProvider = provider(startingByte = 91)
        val first = AesGcmAuthenticatedEncryption(firstProvider, IncrementingNonceGenerator())
        val second = AesGcmAuthenticatedEncryption(secondProvider, IncrementingNonceGenerator())
        val envelope = first.encrypt(alias, "payload".encodeToByteArray())
        secondProvider.getOrCreate(alias)

        assertThrows(AuthenticationFailedException::class.java) {
            second.decrypt(alias, envelope)
        }
        firstProvider.close()
        secondProvider.close()
    }

    @Test
    fun `envelope defensively copies constructor and accessor arrays`() {
        val nonce = ByteArray(12) { it.toByte() }
        val encrypted = ByteArray(16) { (it + 20).toByte() }
        val envelope = AuthenticatedCiphertext.create(nonce, encrypted)
        nonce.fill(0)
        encrypted.fill(0)

        val nonceView = envelope.nonceCopy()
        val encryptedView = envelope.encryptedCopy()
        nonceView.fill(9)
        encryptedView.fill(9)

        assertThat(envelope.nonceCopy().all { it == 0.toByte() }).isFalse()
        assertThat(envelope.encryptedCopy().all { it == 0.toByte() }).isFalse()
    }

    @Test
    fun `invalid nonce generator is rejected before encryption`() {
        val provider = provider()
        val encryption = AesGcmAuthenticatedEncryption(provider, NonceGenerator { ByteArray(it - 1) })

        assertThrows(IllegalArgumentException::class.java) {
            encryption.encrypt(alias, byteArrayOf(1))
        }
        provider.close()
    }

    private fun provider(startingByte: Int = 1): InMemoryAesGcmKeyProvider = InMemoryAesGcmKeyProvider(
        KeyByteGenerator { byteCount -> ByteArray(byteCount) { (it + startingByte).toByte() } },
    )

    private class IncrementingNonceGenerator : NonceGenerator {
        private var next = 0

        override fun generate(byteCount: Int): ByteArray =
            ByteArray(byteCount) { index -> (next + index).toByte() }.also { next += 1 }
    }
}
