package app.shareguard.block.render

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StrictPngSerializerTest {
    private val serializer = StrictPngSerializer()

    @Test
    fun `opaque bitmap becomes minimal truecolour PNG and independently reopens`() {
        val bitmap = bitmap(Color.rgb(20, 40, 60))

        val (bytes, evidence) = serializer.serializeOpaque(bitmap)

        assertThat(bytes.copyOfRange(0, 8)).isEqualTo(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
        )
        assertThat(evidence.bitDepth).isEqualTo(8)
        assertThat(evidence.colorType).isAnyOf(2, 6)
        assertThat(evidence.chunkTypes.toSet()).containsExactly("IHDR", "IDAT", "IEND")
        assertThat(evidence.opaqueDecodedPixels).isTrue()
        assertThat(serializer.reopenAndInspect(bytes)).isEqualTo(evidence)
        bitmap.recycle()
    }

    @Test
    fun `unexpected alpha is fatal before serialization`() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.argb(120, 20, 40, 60))

        val failure = assertThrows(RenderException::class.java) {
            serializer.serializeOpaque(bitmap)
        }

        assertThat(failure.code).isEqualTo(RenderFailureCode.UNEXPECTED_ALPHA)
        bitmap.recycle()
    }

    @Test
    fun `trailing bytes and CRC mutation cannot reopen`() {
        val bitmap = bitmap(Color.BLACK)
        val (bytes, _) = serializer.serializeOpaque(bitmap)
        val trailing = bytes + byteArrayOf(1)
        val corrupted = bytes.copyOf().also { it[it.lastIndex - 1] = (it[it.lastIndex - 1].toInt() xor 1).toByte() }

        assertThat(assertThrows(RenderException::class.java) { serializer.reopenAndInspect(trailing) }.code)
            .isEqualTo(RenderFailureCode.CONTAINER_REOPEN_FAILED)
        assertThat(assertThrows(RenderException::class.java) { serializer.reopenAndInspect(corrupted) }.code)
            .isEqualTo(RenderFailureCode.CONTAINER_REOPEN_FAILED)
        bitmap.recycle()
    }

    private fun bitmap(color: Int): Bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color)
        setHasAlpha(false)
    }
}
