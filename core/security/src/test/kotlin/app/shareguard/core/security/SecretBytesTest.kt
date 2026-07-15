package app.shareguard.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SecretBytesTest {
    @Test
    fun `copyOf does not alias caller bytes`() {
        val caller = byteArrayOf(1, 2, 3, 4)
        val secret = SecretBytes.copyOf(caller)
        caller.fill(9)

        val observed = secret.access { it.copyOf() }

        assertThat(observed.asList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte()).inOrder()
        secret.close()
    }

    @Test
    fun `consume erases caller bytes immediately`() {
        val caller = byteArrayOf(4, 3, 2, 1)

        val secret = SecretBytes.consume(caller)

        assertThat(caller.all { it == 0.toByte() }).isTrue()
        assertThat(secret.access { it.toList() }).containsExactly(4.toByte(), 3.toByte(), 2.toByte(), 1.toByte()).inOrder()
        secret.close()
    }

    @Test
    fun `temporary access copy is erased after callback`() {
        val secret = SecretBytes.copyOf(byteArrayOf(7, 8, 9))
        lateinit var retainedReference: ByteArray

        secret.access { bytes ->
            retainedReference = bytes
            assertThat(bytes.asList()).containsExactly(7.toByte(), 8.toByte(), 9.toByte()).inOrder()
        }

        assertThat(retainedReference.all { it == 0.toByte() }).isTrue()
        secret.close()
    }

    @Test
    fun `close is idempotent zeroizes storage and forbids access`() {
        val secret = SecretBytes.copyOf(byteArrayOf(5, 6, 7))

        secret.close()
        secret.close()

        assertThat(secret.isDestroyed).isTrue()
        assertThat(secret.isStorageZeroizedForTest()).isTrue()
        assertThrows(IllegalStateException::class.java) { secret.access { it.size } }
        assertThat(secret.toString()).doesNotContain("5")
        assertThat(secret.toString()).contains("redacted")
    }
}
