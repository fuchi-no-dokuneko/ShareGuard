package app.shareguard.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class InMemoryAesGcmKeyProviderTest {
    @Test
    fun `getOrCreate is stable for one alias and isolated across aliases`() {
        val generator = CountingKeyGenerator()
        val provider = InMemoryAesGcmKeyProvider(generator)

        val first = provider.getOrCreate(KeyAlias("session-key-a")).encoded
        val again = provider.getOrCreate(KeyAlias("session-key-a")).encoded
        val other = provider.getOrCreate(KeyAlias("session-key-b")).encoded

        assertThat(first.asList()).isEqualTo(again.asList())
        assertThat(first.asList()).isNotEqualTo(other.asList())
        assertThat(generator.calls).isEqualTo(2)
        assertThat(provider.keyCountForTest()).isEqualTo(2)
        first.fill(0)
        again.fill(0)
        other.fill(0)
        provider.close()
    }

    @Test
    fun `delete is logical idempotent and removes key availability`() {
        val provider = InMemoryAesGcmKeyProvider(CountingKeyGenerator())
        val alias = KeyAlias("temporary-key")
        provider.getOrCreate(alias)

        assertThat(provider.delete(alias)).isEqualTo(LogicalKeyDeletionResult.DELETED)
        assertThat(provider.get(alias)).isNull()
        assertThat(provider.delete(alias)).isEqualTo(LogicalKeyDeletionResult.ALREADY_ABSENT)
        provider.close()
    }

    @Test
    fun `close clears keys and rejects future access`() {
        val provider = InMemoryAesGcmKeyProvider(CountingKeyGenerator())
        provider.getOrCreate(KeyAlias("temporary-key"))

        provider.close()
        provider.close()

        assertThat(provider.keyCountForTest()).isEqualTo(0)
        assertThrows(IllegalStateException::class.java) {
            provider.getOrCreate(KeyAlias("new-key"))
        }
        assertThat(provider.delete(KeyAlias("temporary-key")))
            .isEqualTo(LogicalKeyDeletionResult.FAILED_BEST_EFFORT)
    }

    @Test
    fun `invalid or path-like aliases are rejected`() {
        listOf("", "../key", "/key", "contains space", "a/b", "9starts-with-digit").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { KeyAlias(value) }
        }
    }

    @Test
    fun `invalid generated key bytes are still erased best effort`() {
        lateinit var generated: ByteArray
        val provider = InMemoryAesGcmKeyProvider(
            KeyByteGenerator { generated = ByteArray(31) { 7 }; generated },
        )

        assertThrows(IllegalArgumentException::class.java) {
            provider.getOrCreate(KeyAlias("temporary-key"))
        }

        assertThat(generated.all { it == 0.toByte() }).isTrue()
        assertThat(provider.keyCountForTest()).isEqualTo(0)
        provider.close()
    }

    private class CountingKeyGenerator : KeyByteGenerator {
        var calls = 0

        override fun generate(byteCount: Int): ByteArray {
            calls += 1
            return ByteArray(byteCount) { (calls + it).toByte() }
        }
    }
}
