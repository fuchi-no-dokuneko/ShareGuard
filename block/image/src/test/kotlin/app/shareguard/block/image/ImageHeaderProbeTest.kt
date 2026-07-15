package app.shareguard.block.image

import app.shareguard.core.model.MimeType
import app.shareguard.core.model.SafeSummary
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Test

class ImageHeaderProbeTest {
    private val probe = ImageHeaderProbe()
    private val policy = ImageHeaderProbePolicy(4_096, 64, SafeSummary("header-corpus-v1"))

    @Test
    fun `valid PNG inventory is content-free and exact`() {
        val bytes = png(2, 3)
        val source = ByteArrayImageByteSource(bytes)

        val result = probe.probe(source, MimeType("image/png"), policy)

        val accepted = result as ImageHeaderProbeResult.Accepted
        assertThat(accepted.header.format).isEqualTo(ImageFormat.PNG)
        assertThat(accepted.header.width).isEqualTo(2)
        assertThat(accepted.header.height).isEqualTo(3)
        assertThat(accepted.header.container.completeWithinSource).isTrue()
        assertThat(accepted.header.container.elements.map { it.typeCode })
            .containsExactly("IHDR", "IDAT", "IEND").inOrder()
        assertThat(accepted.warnings).isEmpty()
        source.close()
        assertThat(source.isZeroizedForTest()).isTrue()
    }

    @Test
    fun `claimed MIME conflict malformed CRC and invalid profile are rejected`() {
        val valid = png(2, 3)
        val mimeConflict = probe.probe(ByteArrayImageByteSource(valid), MimeType("image/jpeg"), policy)
        assertThat((mimeConflict as ImageHeaderProbeResult.Rejected).reason)
            .isEqualTo(HeaderRejectionReason.MIME_SIGNATURE_CONFLICT)

        val badCrc = valid.copyOf().also { it[it.lastIndex - 5] = (it[it.lastIndex - 5].toInt() xor 1).toByte() }
        val crcResult = probe.probe(ByteArrayImageByteSource(badCrc), null, policy)
        assertThat((crcResult as ImageHeaderProbeResult.Rejected).reason)
            .isEqualTo(HeaderRejectionReason.MALFORMED_CONTAINER)

        val invalidCompression = png(2, 3, compression = 1)
        val profile = probe.probe(ByteArrayImageByteSource(invalidCompression), null, policy)
        assertThat((profile as ImageHeaderProbeResult.Rejected).reason)
            .isEqualTo(HeaderRejectionReason.INCONSISTENT_HEADER)
    }

    @Test
    fun `strong trailing format is rejected as polyglot while inert trailing data is inventoried`() {
        val valid = png(1, 1)
        val polyglot = valid + byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        val rejected = probe.probe(ByteArrayImageByteSource(polyglot), null, policy)
        assertThat((rejected as ImageHeaderProbeResult.Rejected).reason)
            .isEqualTo(HeaderRejectionReason.AMBIGUOUS_POLYGLOT)

        val trailing = probe.probe(ByteArrayImageByteSource(valid + "opaque-tail".encodeToByteArray()), null, policy)
        assertThat((trailing as ImageHeaderProbeResult.Accepted).warnings)
            .contains(HeaderProbeWarning.TRAILING_NON_CONTAINER_DATA)
    }

    @Test
    fun `missing pixel data and dimension bombs do not become accepted headers`() {
        val noPixels = pngChunks(
            chunk("IHDR", ihdr(1, 1)),
            chunk("IEND", byteArrayOf()),
        )
        val noPixelResult = probe.probe(ByteArrayImageByteSource(noPixels), null, policy)
        assertThat((noPixelResult as ImageHeaderProbeResult.Rejected).reason)
            .isEqualTo(HeaderRejectionReason.INCONSISTENT_HEADER)

        val zeroWidth = pngChunks(
            chunk("IHDR", ihdr(0, 1)),
            chunk("IDAT", byteArrayOf()),
            chunk("IEND", byteArrayOf()),
        )
        val dimensions = probe.probe(ByteArrayImageByteSource(zeroWidth), null, policy)
        assertThat((dimensions as ImageHeaderProbeResult.Rejected).reason)
            .isAnyOf(HeaderRejectionReason.MALFORMED_CONTAINER, HeaderRejectionReason.INCONSISTENT_HEADER)
    }

    private fun png(width: Int, height: Int, compression: Int = 0): ByteArray = pngChunks(
        chunk("IHDR", ihdr(width, height, compression)),
        chunk("IDAT", byteArrayOf()),
        chunk("IEND", byteArrayOf()),
    )

    private fun pngChunks(vararg chunks: ByteArray): ByteArray =
        PNG_SIGNATURE + chunks.fold(byteArrayOf()) { accumulated, item -> accumulated + item }

    private fun ihdr(width: Int, height: Int, compression: Int = 0): ByteArray =
        ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            .putInt(width)
            .putInt(height)
            .put(8)
            .put(2)
            .put(compression.toByte())
            .put(0)
            .put(0)
            .array()

    private fun chunk(type: String, payload: ByteArray): ByteArray {
        val typeBytes = type.encodeToByteArray()
        val crc = CRC32().apply { update(typeBytes); update(payload) }.value
        return ByteArrayOutputStream().use { output ->
            output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array())
            output.write(typeBytes)
            output.write(payload)
            output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.toInt()).array())
            output.toByteArray()
        }
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
