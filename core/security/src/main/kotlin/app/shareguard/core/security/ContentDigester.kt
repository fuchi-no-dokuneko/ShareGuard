package app.shareguard.core.security

import app.shareguard.core.model.ContentDigest
import java.io.InputStream
import java.security.MessageDigest

/** Computes session-local integrity digests. A digest must not be used as a stable cross-session ID. */
interface ContentDigester {
    fun digest(bytes: ByteArray): ContentDigest

    /** Reads but does not close [input]. */
    fun digest(input: InputStream): ContentDigest

    fun matches(bytes: ByteArray, expected: ContentDigest): Boolean
}

class Sha256ContentDigester(
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) : ContentDigester {
    init {
        require(bufferSize > 0) { "bufferSize must be positive" }
    }

    override fun digest(bytes: ByteArray): ContentDigest =
        MessageDigest.getInstance(ALGORITHM).digest(bytes).toContentDigest()

    override fun digest(input: InputStream): ContentDigest {
        val messageDigest = MessageDigest.getInstance(ALGORITHM)
        val buffer = ByteArray(bufferSize)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) messageDigest.update(buffer, 0, count)
            }
            return messageDigest.digest().toContentDigest()
        } finally {
            // The buffer is not normally secret once imported, but erasing the scratch copy is cheap.
            buffer.fill(0)
        }
    }

    override fun matches(bytes: ByteArray, expected: ContentDigest): Boolean {
        val actualBytes = MessageDigest.getInstance(ALGORITHM).digest(bytes)
        val expectedBytes = expected.sha256.hexToBytes()
        return try {
            MessageDigest.isEqual(actualBytes, expectedBytes)
        } finally {
            actualBytes.fill(0)
            expectedBytes.fill(0)
        }
    }

    private fun ByteArray.toContentDigest(): ContentDigest {
        val characters = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            characters[index * 2] = HEX_CHARACTERS[value ushr 4]
            characters[index * 2 + 1] = HEX_CHARACTERS[value and 0x0f]
        }
        val hex = String(characters)
        fill(0)
        return ContentDigest(hex)
    }

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private companion object {
        const val ALGORITHM = "SHA-256"
        const val DEFAULT_BUFFER_SIZE = 8 * 1024
        const val HEX_CHARACTERS = "0123456789abcdef"
    }
}
