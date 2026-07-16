package app.shareguard.block.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.shareguard.core.model.PixelDimension
import app.shareguard.core.model.PixelSize
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

data class PngContainerEvidence(
    val pixelSize: PixelSize,
    val bitDepth: Int,
    val colorType: Int,
    val chunkTypes: List<String>,
    val opaqueDecodedPixels: Boolean,
)

class StrictPngSerializer {
    fun serializeOpaque(bitmap: Bitmap): Pair<ByteArray, PngContainerEvidence> {
        requireAllPixelsOpaque(bitmap)
        bitmap.setHasAlpha(false)
        val encoderBytes = ByteArrayOutputStream(estimatedCapacity(bitmap)).use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw RenderException(RenderFailureCode.SERIALIZATION_FAILED)
            }
            output.toByteArray()
        }
        val bytes = try {
            stripEncoderAncillaryChunks(encoderBytes)
        } finally {
            encoderBytes.fill(0)
        }
        return try {
            bytes to reopenAndInspect(bytes)
        } catch (failure: Throwable) {
            bytes.fill(0)
            throw failure
        }
    }

    fun reopenAndInspect(bytes: ByteArray): PngContainerEvidence {
        val chunks = parseChunks(bytes, permitAncillaryChunks = false)
        val header = chunks.firstOrNull { it.type == "IHDR" }
            ?: throw RenderException(RenderFailureCode.CONTAINER_REOPEN_FAILED)
        if (header.data.size != 13) throw RenderException(RenderFailureCode.CONTAINER_REOPEN_FAILED)
        val headerBuffer = ByteBuffer.wrap(header.data).order(ByteOrder.BIG_ENDIAN)
        val width = headerBuffer.int
        val height = headerBuffer.int
        val bitDepth = headerBuffer.get().toInt() and 0xff
        val colorType = headerBuffer.get().toInt() and 0xff
        val compression = headerBuffer.get().toInt() and 0xff
        val filter = headerBuffer.get().toInt() and 0xff
        val interlace = headerBuffer.get().toInt() and 0xff
        if (width <= 0 || height <= 0 || bitDepth != 8 || colorType !in ALLOWED_COLOUR_TYPES ||
            compression != 0 || filter != 0 || interlace != 0
        ) {
            throw RenderException(RenderFailureCode.UNEXPECTED_PNG_PROFILE)
        }
        if (chunks.none { it.type == "IDAT" } || chunks.lastOrNull()?.type != "IEND") {
            throw RenderException(RenderFailureCode.CONTAINER_REOPEN_FAILED)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw RenderException(RenderFailureCode.CONTAINER_REOPEN_FAILED)
        return try {
            if (decoded.width != width || decoded.height != height) {
                throw RenderException(RenderFailureCode.CONTAINER_REOPEN_FAILED)
            }
            val opaque = pixelsAreOpaque(decoded)
            if (!opaque) throw RenderException(RenderFailureCode.UNEXPECTED_ALPHA)
            PngContainerEvidence(
                pixelSize = PixelSize(PixelDimension(width), PixelDimension(height)),
                bitDepth = bitDepth,
                colorType = colorType,
                chunkTypes = chunks.map { it.type },
                opaqueDecodedPixels = true,
            )
        } finally {
            decoded.recycle()
        }
    }

    private fun stripEncoderAncillaryChunks(bytes: ByteArray): ByteArray {
        val chunks = parseChunks(bytes, permitAncillaryChunks = true)
        return ByteArrayOutputStream(bytes.size).use { output ->
            output.write(PNG_SIGNATURE)
            chunks.filter { it.type in ALLOWED_CHUNK_TYPES }.forEach { chunk ->
                output.writeIntBigEndian(chunk.data.size)
                val typeBytes = chunk.type.encodeToByteArray()
                output.write(typeBytes)
                output.write(chunk.data)
                val crc = CRC32().apply {
                    update(typeBytes)
                    update(chunk.data)
                }.value
                output.writeIntBigEndian(crc.toInt())
                typeBytes.fill(0)
            }
            output.toByteArray()
        }
    }

    private fun parseChunks(bytes: ByteArray, permitAncillaryChunks: Boolean): List<PngChunk> {
        if (bytes.size < PNG_SIGNATURE.size + 12 ||
            !bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
        ) {
            throw RenderException(RenderFailureCode.CONTAINER_REOPEN_FAILED)
        }
        val chunks = mutableListOf<PngChunk>()
        var offset = PNG_SIGNATURE.size
        var sawIend = false
        while (offset < bytes.size) {
            if (bytes.size - offset < 12) throw RenderException(RenderFailureCode.CONTAINER_REOPEN_FAILED)
            val length = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            if (length < 0 || length > bytes.size - offset - 12) {
                throw RenderException(RenderFailureCode.CONTAINER_REOPEN_FAILED)
            }
            val type = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            if (!PNG_CHUNK_TYPE.matches(type)) throw RenderException(RenderFailureCode.CONTAINER_REOPEN_FAILED)
            if (sawIend) throw RenderException(RenderFailureCode.CONTAINER_REOPEN_FAILED)
            val dataStart = offset + 8
            val dataEnd = dataStart + length
            val declaredCrc = ByteBuffer.wrap(bytes, dataEnd, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
            val actualCrc = CRC32().apply { update(bytes, offset + 4, length + 4) }.value
            if (declaredCrc != actualCrc) throw RenderException(RenderFailureCode.CONTAINER_REOPEN_FAILED)
            if (type !in ALLOWED_CHUNK_TYPES && (!permitAncillaryChunks || type.first() !in 'a'..'z')) {
                throw RenderException(RenderFailureCode.UNEXPECTED_PNG_CHUNK)
            }
            chunks += PngChunk(type, bytes.copyOfRange(dataStart, dataEnd))
            offset = dataEnd + 4
            if (type == "IEND") sawIend = true
        }
        if (!sawIend || offset != bytes.size || chunks.firstOrNull()?.type != "IHDR") {
            throw RenderException(RenderFailureCode.CONTAINER_REOPEN_FAILED)
        }
        return chunks
    }

    private fun requireAllPixelsOpaque(bitmap: Bitmap) {
        if (!pixelsAreOpaque(bitmap)) throw RenderException(RenderFailureCode.UNEXPECTED_ALPHA)
    }

    private fun pixelsAreOpaque(bitmap: Bitmap): Boolean {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { pixel -> (pixel ushr 24) != 0xff }) return false
        }
        row.fill(0)
        return true
    }

    private fun estimatedCapacity(bitmap: Bitmap): Int {
        val raw = bitmap.width.toLong() * bitmap.height.toLong() * 4L
        return raw.coerceIn(MIN_INITIAL_CAPACITY.toLong(), MAX_INITIAL_CAPACITY.toLong()).toInt()
    }

    private data class PngChunk(val type: String, val data: ByteArray)

    private fun ByteArrayOutputStream.writeIntBigEndian(value: Int) {
        write(value ushr 24 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 8 and 0xff)
        write(value and 0xff)
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val PNG_CHUNK_TYPE = Regex("[A-Za-z]{4}")
        val ALLOWED_CHUNK_TYPES = setOf("IHDR", "IDAT", "IEND")
        val ALLOWED_COLOUR_TYPES = setOf(PNG_TRUECOLOR, PNG_TRUECOLOR_WITH_ALPHA)
        const val PNG_TRUECOLOR = 2
        const val PNG_TRUECOLOR_WITH_ALPHA = 6
        const val MIN_INITIAL_CAPACITY = 8 * 1024
        const val MAX_INITIAL_CAPACITY = 4 * 1024 * 1024
    }
}
