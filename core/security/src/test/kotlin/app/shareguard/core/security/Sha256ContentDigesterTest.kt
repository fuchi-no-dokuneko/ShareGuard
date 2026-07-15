package app.shareguard.core.security

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.Test

class Sha256ContentDigesterTest {
    private val digester = Sha256ContentDigester(bufferSize = 3)

    @Test
    fun `known SHA-256 vectors are canonical lowercase hex`() {
        assertThat(digester.digest(byteArrayOf()).sha256)
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        assertThat(digester.digest("abc".encodeToByteArray()).sha256)
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `stream and byte array paths agree across buffer boundaries`() {
        val bytes = ByteArray(10_007) { index -> (index * 31).toByte() }

        assertThat(digester.digest(ByteArrayInputStream(bytes)))
            .isEqualTo(digester.digest(bytes))
    }

    @Test
    fun `matches accepts exact content and rejects any changed byte`() {
        val bytes = "sealed source".encodeToByteArray()
        val expected = digester.digest(bytes)

        assertThat(digester.matches(bytes, expected)).isTrue()
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        assertThat(digester.matches(bytes, expected)).isFalse()
    }

    @Test
    fun `digest does not close caller owned stream`() {
        val stream = TrackingInputStream("content".encodeToByteArray())

        digester.digest(stream)

        assertThat(stream.closed).isFalse()
    }

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
