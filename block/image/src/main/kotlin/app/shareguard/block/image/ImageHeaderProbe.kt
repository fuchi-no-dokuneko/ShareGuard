package app.shareguard.block.image

import app.shareguard.core.model.MimeType
import app.shareguard.core.model.SafeSummary
import java.io.Closeable
import java.util.zip.CRC32
import kotlin.math.abs

enum class ImageFormat(val canonicalMime: String) {
    PNG("image/png"),
    JPEG("image/jpeg"),
    GIF("image/gif"),
    WEBP("image/webp"),
    BMP("image/bmp"),
    HEIF("image/heif"),
    AVIF("image/avif"),
}

enum class HeaderProbeWarning {
    CLAIMED_MIME_ABSENT,
    CONTAINER_INVENTORY_PROBE_LIMIT,
    TRAILING_NON_CONTAINER_DATA,
    FRAME_COUNT_APPROXIMATE,
    COLOUR_PROFILE_DECLARED,
    EMBEDDED_THUMBNAIL_DECLARED,
}

enum class HeaderRejectionReason {
    EMPTY_SOURCE,
    PROBE_BUDGET_TOO_SMALL,
    UNSUPPORTED_SIGNATURE,
    MALFORMED_CONTAINER,
    INCONSISTENT_HEADER,
    MIME_SIGNATURE_CONFLICT,
    AMBIGUOUS_POLYGLOT,
    DIMENSIONS_UNAVAILABLE,
}

enum class ContainerElementFamily {
    HEADER,
    PIXEL_DATA,
    METADATA,
    COLOUR_PROFILE,
    TEXT,
    THUMBNAIL,
    ANIMATION,
    STRUCTURAL,
    UNKNOWN,
}

data class ContainerElement(
    val typeCode: String,
    val family: ContainerElementFamily,
    val declaredLength: Long,
) {
    init {
        require(typeCode.matches(Regex("[A-Za-z0-9?_-]{1,12}"))) { "Container code must be content-free" }
        require(declaredLength >= 0)
    }
}

data class ContainerStructureInventory(
    val elements: List<ContainerElement>,
    val completeWithinSource: Boolean,
) {
    init { require(elements.size <= MAX_REPRESENTABLE_ELEMENTS) }

    private companion object {
        const val MAX_REPRESENTABLE_ELEMENTS = 16_384
    }
}

data class ImageHeaderModel(
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    val channels: Int,
    val hasAlpha: Boolean,
    val animated: Boolean,
    val frameCount: Int?,
    val container: ContainerStructureInventory,
) {
    init {
        require(width > 0 && height > 0)
        require(bitDepth > 0 && channels > 0)
        require(frameCount == null || frameCount > 0)
    }
}

sealed interface ImageHeaderProbeResult {
    data class Accepted(
        val header: ImageHeaderModel,
        val warnings: Set<HeaderProbeWarning>,
    ) : ImageHeaderProbeResult

    data class Rejected(val reason: HeaderRejectionReason) : ImageHeaderProbeResult
}

data class ImageHeaderProbePolicy(
    val maximumProbeBytes: Int,
    val maximumContainerElements: Int,
    val validationReference: SafeSummary,
) {
    init {
        require(maximumProbeBytes in 64..MAXIMUM_HEADER_PROBE_BYTES)
        require(maximumContainerElements in 1..16_384)
        require(validationReference.value.isNotBlank())
    }

    private companion object {
        const val MAXIMUM_HEADER_PROBE_BYTES = 8 * 1024 * 1024
    }
}

interface ImageByteSource {
    val length: Long

    /** Returns at most [byteCount] bytes beginning at [offset]. */
    fun read(offset: Long, byteCount: Int): ByteArray
}

/** JVM-test source. Production callers should bridge a verified app-private snapshot instead. */
class ByteArrayImageByteSource(bytes: ByteArray) : ImageByteSource, Closeable {
    private val storage = bytes.copyOf()
    private var closed = false

    override val length: Long
        get() = storage.size.toLong()

    override fun read(offset: Long, byteCount: Int): ByteArray {
        check(!closed) { "Image byte source is closed" }
        require(offset >= 0 && byteCount >= 0)
        if (offset >= storage.size) return byteArrayOf()
        val end = minOf(storage.size.toLong(), CheckedImageArithmetic.add(offset, byteCount.toLong())).toInt()
        return storage.copyOfRange(offset.toInt(), end)
    }

    override fun close() {
        if (!closed) {
            storage.fill(0)
            closed = true
        }
    }

    internal fun isZeroizedForTest(): Boolean = storage.all { it == 0.toByte() }
}

class ImageHeaderProbe {
    fun probe(
        source: ImageByteSource,
        claimedMime: MimeType?,
        policy: ImageHeaderProbePolicy,
    ): ImageHeaderProbeResult {
        if (source.length <= 0) return ImageHeaderProbeResult.Rejected(HeaderRejectionReason.EMPTY_SOURCE)
        val prefixSize = minOf(source.length, policy.maximumProbeBytes.toLong()).toInt()
        val bytes = try {
            source.read(0, prefixSize)
        } catch (_: Exception) {
            return ImageHeaderProbeResult.Rejected(HeaderRejectionReason.MALFORMED_CONTAINER)
        }
        if (bytes.size < minOf(prefixSize, 12)) {
            bytes.fill(0)
            return ImageHeaderProbeResult.Rejected(HeaderRejectionReason.PROBE_BUDGET_TOO_SMALL)
        }
        return try {
            val parsed = when {
                bytes.startsWith(PNG_SIGNATURE) -> parsePng(bytes, source.length, policy)
                bytes.startsWith(JPEG_SIGNATURE) -> parseJpeg(bytes, source.length, policy)
                bytes.startsWith(GIF_87) || bytes.startsWith(GIF_89) -> parseGif(bytes, source.length, policy)
                bytes.startsWith(RIFF) && bytes.ascii(8, 4) == "WEBP" -> parseWebp(bytes, source.length, policy)
                bytes.startsWith(BMP_SIGNATURE) -> parseBmp(bytes, source.length, policy)
                bytes.ascii(4, 4) == "ftyp" -> parseIsoBmff(bytes, source.length, policy)
                else -> throw ProbeFailure(HeaderRejectionReason.UNSUPPORTED_SIGNATURE)
            }
            if (claimedMime != null && !mimeMatches(claimedMime.value, parsed.header.format)) {
                return ImageHeaderProbeResult.Rejected(HeaderRejectionReason.MIME_SIGNATURE_CONFLICT)
            }
            val warnings = parsed.warnings.toMutableSet()
            if (claimedMime == null) warnings += HeaderProbeWarning.CLAIMED_MIME_ABSENT
            if (!parsed.header.container.completeWithinSource) {
                warnings += HeaderProbeWarning.CONTAINER_INVENTORY_PROBE_LIMIT
            }
            val trailing = inspectTrailing(source, parsed.logicalEnd)
            if (trailing == TrailingStatus.STRONG_SECOND_FORMAT) {
                ImageHeaderProbeResult.Rejected(HeaderRejectionReason.AMBIGUOUS_POLYGLOT)
            } else {
                if (trailing == TrailingStatus.OTHER_DATA) warnings += HeaderProbeWarning.TRAILING_NON_CONTAINER_DATA
                ImageHeaderProbeResult.Accepted(parsed.header, warnings)
            }
        } catch (failure: ProbeFailure) {
            ImageHeaderProbeResult.Rejected(failure.reason)
        } catch (_: Exception) {
            ImageHeaderProbeResult.Rejected(HeaderRejectionReason.MALFORMED_CONTAINER)
        } finally {
            bytes.fill(0)
        }
    }

    private fun parsePng(bytes: ByteArray, sourceLength: Long, policy: ImageHeaderProbePolicy): ParsedHeader {
        var offset = 8
        var width: Int? = null
        var height: Int? = null
        var bitDepth = 0
        var channels = 0
        var alpha = false
        var frames: Int? = null
        var logicalEnd: Long? = null
        var complete = sourceLength <= bytes.size
        val elements = mutableListOf<ContainerElement>()
        while (offset + 12 <= bytes.size && elements.size < policy.maximumContainerElements) {
            val payloadLength = bytes.u32be(offset)
            val totalLength = CheckedImageArithmetic.add(payloadLength, 12)
            val next = CheckedImageArithmetic.add(offset.toLong(), totalLength)
            if (next > sourceLength) throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
            if (next > bytes.size) {
                complete = false
                break
            }
            val type = bytes.asciiCode(offset + 4, 4, preserveCase = true)
            val dataEnd = offset + 8 + payloadLength.toInt()
            val declaredCrc = bytes.u32be(dataEnd)
            val actualCrc = CRC32().apply { update(bytes, offset + 4, payloadLength.toInt() + 4) }.value
            if (declaredCrc != actualCrc) throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
            elements += ContainerElement(type, pngFamily(type), payloadLength)
            val payload = offset + 8
            when (type) {
                "IHDR" -> {
                    if (elements.size != 1 || payloadLength != 13L) throw ProbeFailure(HeaderRejectionReason.INCONSISTENT_HEADER)
                    width = bytes.u32be(payload).positiveInt()
                    height = bytes.u32be(payload + 4).positiveInt()
                    bitDepth = bytes.u8(payload + 8)
                    val colourType = bytes.u8(payload + 9)
                    channels = when (colourType) {
                        0 -> 1
                        2 -> 3
                        3 -> 1
                        4 -> 2
                        6 -> 4
                        else -> throw ProbeFailure(HeaderRejectionReason.INCONSISTENT_HEADER)
                    }
                    val validDepth = when (colourType) {
                        0 -> bitDepth in setOf(1, 2, 4, 8, 16)
                        2, 4, 6 -> bitDepth in setOf(8, 16)
                        3 -> bitDepth in setOf(1, 2, 4, 8)
                        else -> false
                    }
                    if (!validDepth || bytes.u8(payload + 10) != 0 || bytes.u8(payload + 11) != 0 ||
                        bytes.u8(payload + 12) !in 0..1
                    ) {
                        throw ProbeFailure(HeaderRejectionReason.INCONSISTENT_HEADER)
                    }
                    alpha = colourType == 4 || colourType == 6
                }
                "acTL" -> {
                    if (payloadLength < 8) throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
                    frames = bytes.u32be(payload).positiveInt()
                }
                "IEND" -> {
                    if (payloadLength != 0L) throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
                    logicalEnd = next
                    break
                }
            }
            offset = next.toInt()
        }
        if (elements.size >= policy.maximumContainerElements) complete = false
        val finalWidth = width ?: throw ProbeFailure(HeaderRejectionReason.DIMENSIONS_UNAVAILABLE)
        val finalHeight = height ?: throw ProbeFailure(HeaderRejectionReason.DIMENSIONS_UNAVAILABLE)
        if (logicalEnd != null && elements.none { it.typeCode == "IDAT" }) {
            throw ProbeFailure(HeaderRejectionReason.INCONSISTENT_HEADER)
        }
        val animated = (frames ?: 1) > 1
        return ParsedHeader(
            ImageHeaderModel(
                ImageFormat.PNG,
                finalWidth,
                finalHeight,
                bitDepth,
                channels,
                alpha,
                animated,
                frames ?: 1,
                ContainerStructureInventory(elements.toList(), complete && logicalEnd != null),
            ),
            logicalEnd,
            buildSet {
                if (elements.any { it.family == ContainerElementFamily.COLOUR_PROFILE }) add(HeaderProbeWarning.COLOUR_PROFILE_DECLARED)
            },
        )
    }

    private fun parseJpeg(bytes: ByteArray, sourceLength: Long, policy: ImageHeaderProbePolicy): ParsedHeader {
        var offset = 2
        var width: Int? = null
        var height: Int? = null
        var precision = 0
        var channels = 0
        var complete = sourceLength <= bytes.size
        var logicalEnd: Long? = null
        val elements = mutableListOf(ContainerElement("SOI", ContainerElementFamily.HEADER, 0))
        while (offset + 1 < bytes.size && elements.size < policy.maximumContainerElements) {
            if (bytes.u8(offset) != 0xff) throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
            while (offset < bytes.size && bytes.u8(offset) == 0xff) offset++
            if (offset >= bytes.size) break
            val marker = bytes.u8(offset++)
            if (marker == 0xd9) {
                elements += ContainerElement("EOI", ContainerElementFamily.STRUCTURAL, 0)
                logicalEnd = offset.toLong()
                break
            }
            if (marker == 0xda) {
                val eoi = bytes.lastMarker(0xff, 0xd9)
                if (eoi >= 0 && sourceLength <= bytes.size) {
                    logicalEnd = (eoi + 2).toLong()
                    elements += ContainerElement("SOS", ContainerElementFamily.PIXEL_DATA, logicalEnd - offset)
                } else {
                    complete = false
                    elements += ContainerElement("SOS", ContainerElementFamily.PIXEL_DATA, 0)
                }
                break
            }
            if (marker in setOf(0x01, 0xd0, 0xd1, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7)) continue
            if (offset + 2 > bytes.size) { complete = false; break }
            val segmentLength = bytes.u16be(offset)
            if (segmentLength < 2) throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
            val next = CheckedImageArithmetic.add(offset.toLong(), segmentLength.toLong())
            if (next > sourceLength) throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
            if (next > bytes.size) { complete = false; break }
            val code = "M" + marker.toString(16).uppercase().padStart(2, '0')
            elements += ContainerElement(code, jpegFamily(marker), segmentLength.toLong() - 2)
            if (marker in SOF_MARKERS) {
                if (segmentLength < 8) throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
                precision = bytes.u8(offset + 2)
                height = bytes.u16be(offset + 3).positiveInt()
                width = bytes.u16be(offset + 5).positiveInt()
                channels = bytes.u8(offset + 7)
            }
            offset = next.toInt()
        }
        val finalWidth = width ?: throw ProbeFailure(HeaderRejectionReason.DIMENSIONS_UNAVAILABLE)
        val finalHeight = height ?: throw ProbeFailure(HeaderRejectionReason.DIMENSIONS_UNAVAILABLE)
        return ParsedHeader(
            ImageHeaderModel(
                ImageFormat.JPEG,
                finalWidth,
                finalHeight,
                precision,
                channels,
                false,
                false,
                1,
                ContainerStructureInventory(elements.toList(), complete && logicalEnd != null),
            ),
            logicalEnd,
            buildSet {
                if (elements.any { it.family == ContainerElementFamily.THUMBNAIL }) add(HeaderProbeWarning.EMBEDDED_THUMBNAIL_DECLARED)
                if (elements.any { it.family == ContainerElementFamily.COLOUR_PROFILE }) add(HeaderProbeWarning.COLOUR_PROFILE_DECLARED)
            },
        )
    }

    private fun parseGif(bytes: ByteArray, sourceLength: Long, policy: ImageHeaderProbePolicy): ParsedHeader {
        if (bytes.size < 13) throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
        val width = bytes.u16le(6).positiveInt()
        val height = bytes.u16le(8).positiveInt()
        val packed = bytes.u8(10)
        val bitDepth = (packed and 0x07) + 1
        var offset = 13
        if ((packed and 0x80) != 0) offset += 3 * (1 shl bitDepth)
        var frames = 0
        var logicalEnd: Long? = null
        var complete = sourceLength <= bytes.size
        val elements = mutableListOf(ContainerElement("GIFHDR", ContainerElementFamily.HEADER, 13))
        while (offset < bytes.size && elements.size < policy.maximumContainerElements) {
            when (bytes.u8(offset++)) {
                0x3b -> {
                    elements += ContainerElement("TRAILER", ContainerElementFamily.STRUCTURAL, 1)
                    logicalEnd = offset.toLong()
                    break
                }
                0x21 -> {
                    if (offset >= bytes.size) { complete = false; break }
                    val label = bytes.u8(offset++)
                    val start = offset
                    offset = bytes.skipSubBlocks(offset)
                    elements += ContainerElement(
                        "EXT" + label.toString(16).uppercase().padStart(2, '0'),
                        if (label == 0xff) ContainerElementFamily.METADATA else ContainerElementFamily.STRUCTURAL,
                        (offset - start).toLong(),
                    )
                }
                0x2c -> {
                    if (offset + 9 > bytes.size) { complete = false; break }
                    val imagePacked = bytes.u8(offset + 8)
                    offset += 9
                    if ((imagePacked and 0x80) != 0) {
                        val tableBits = (imagePacked and 0x07) + 1
                        offset += 3 * (1 shl tableBits)
                    }
                    if (offset >= bytes.size) { complete = false; break }
                    offset++ // LZW minimum code size
                    val start = offset
                    offset = bytes.skipSubBlocks(offset)
                    frames++
                    elements += ContainerElement("FRAME", ContainerElementFamily.ANIMATION, (offset - start).toLong())
                }
                else -> throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
            }
        }
        if (frames == 0) throw ProbeFailure(HeaderRejectionReason.INCONSISTENT_HEADER)
        return ParsedHeader(
            ImageHeaderModel(
                ImageFormat.GIF,
                width,
                height,
                bitDepth,
                3,
                false,
                frames > 1,
                frames,
                ContainerStructureInventory(elements.toList(), complete && logicalEnd != null),
            ),
            logicalEnd,
            emptySet(),
        )
    }

    private fun parseWebp(bytes: ByteArray, sourceLength: Long, policy: ImageHeaderProbePolicy): ParsedHeader {
        if (bytes.size < 20) throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
        val declaredEnd = CheckedImageArithmetic.add(bytes.u32le(4), 8)
        if (declaredEnd > sourceLength || declaredEnd < 12) throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
        var offset = 12
        var width: Int? = null
        var height: Int? = null
        var alpha = false
        var animatedFlag = false
        var frames = 0
        var complete = declaredEnd <= bytes.size
        val elements = mutableListOf(ContainerElement("RIFF", ContainerElementFamily.HEADER, declaredEnd - 8))
        while (offset + 8 <= minOf(bytes.size.toLong(), declaredEnd).toInt() && elements.size < policy.maximumContainerElements) {
            val type = bytes.asciiCode(offset, 4, preserveCase = true)
            val length = bytes.u32le(offset + 4)
            val padded = CheckedImageArithmetic.add(length, length and 1)
            val next = CheckedImageArithmetic.add(offset.toLong() + 8, padded)
            if (next > declaredEnd) throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
            if (next > bytes.size) { complete = false; break }
            val data = offset + 8
            elements += ContainerElement(type, webpFamily(type), length)
            when (type) {
                "VP8X" -> {
                    if (length < 10) throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
                    val flags = bytes.u8(data)
                    alpha = (flags and 0x10) != 0
                    animatedFlag = (flags and 0x02) != 0
                    width = bytes.u24le(data + 4) + 1
                    height = bytes.u24le(data + 7) + 1
                }
                "VP8" -> if (width == null && length >= 10 && bytes.matches(data + 3, VP8_FRAME_SIGNATURE)) {
                    width = bytes.u16le(data + 6) and 0x3fff
                    height = bytes.u16le(data + 8) and 0x3fff
                }
                "VP8L" -> if (width == null && length >= 5 && bytes.u8(data) == 0x2f) {
                    val bits = bytes.u32le(data + 1)
                    width = ((bits and 0x3fff) + 1).toInt()
                    height = (((bits shr 14) and 0x3fff) + 1).toInt()
                    alpha = true
                }
                "ANMF" -> frames++
                "ALPH" -> alpha = true
            }
            offset = next.toInt()
        }
        val finalWidth = width ?: throw ProbeFailure(HeaderRejectionReason.DIMENSIONS_UNAVAILABLE)
        val finalHeight = height ?: throw ProbeFailure(HeaderRejectionReason.DIMENSIONS_UNAVAILABLE)
        val animated = animatedFlag || frames > 1
        val knownFrames = if (animated) frames.takeIf { it > 0 } else 1
        return ParsedHeader(
            ImageHeaderModel(
                ImageFormat.WEBP,
                finalWidth,
                finalHeight,
                8,
                if (alpha) 4 else 3,
                alpha,
                animated,
                knownFrames,
                ContainerStructureInventory(elements.toList(), complete && offset.toLong() == declaredEnd),
            ),
            declaredEnd,
            buildSet {
                if (elements.any { it.family == ContainerElementFamily.COLOUR_PROFILE }) add(HeaderProbeWarning.COLOUR_PROFILE_DECLARED)
                if (animated && knownFrames == null) add(HeaderProbeWarning.FRAME_COUNT_APPROXIMATE)
            },
        )
    }

    private fun parseBmp(bytes: ByteArray, sourceLength: Long, policy: ImageHeaderProbePolicy): ParsedHeader {
        if (bytes.size < 30) throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
        val declaredLength = bytes.u32le(2)
        if (declaredLength > sourceLength || declaredLength < 26) throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
        val dibSize = bytes.u32le(14)
        if (dibSize < 12) throw ProbeFailure(HeaderRejectionReason.UNSUPPORTED_SIGNATURE)
        val width: Int
        val height: Int
        val bitDepth: Int
        if (dibSize == 12L) {
            width = bytes.u16le(18).positiveInt()
            height = bytes.u16le(20).positiveInt()
            bitDepth = bytes.u16le(24)
        } else {
            val signedWidth = bytes.i32le(18)
            val signedHeight = bytes.i32le(22)
            if (signedWidth == Int.MIN_VALUE || signedHeight == Int.MIN_VALUE) {
                throw ProbeFailure(HeaderRejectionReason.INCONSISTENT_HEADER)
            }
            width = abs(signedWidth).positiveInt()
            height = abs(signedHeight).positiveInt()
            bitDepth = bytes.u16le(28)
        }
        val elements = listOf(
            ContainerElement("BMPHDR", ContainerElementFamily.HEADER, 14),
            ContainerElement("DIB", ContainerElementFamily.HEADER, dibSize),
            ContainerElement("PIXELS", ContainerElementFamily.PIXEL_DATA, declaredLength - minOf(declaredLength, 14 + dibSize)),
        ).take(policy.maximumContainerElements)
        return ParsedHeader(
            ImageHeaderModel(
                ImageFormat.BMP,
                width,
                height,
                bitDepth,
                if (bitDepth == 32) 4 else 3,
                bitDepth == 32,
                false,
                1,
                ContainerStructureInventory(elements, sourceLength <= bytes.size && elements.size == 3),
            ),
            declaredLength,
            emptySet(),
        )
    }

    private fun parseIsoBmff(bytes: ByteArray, sourceLength: Long, policy: ImageHeaderProbePolicy): ParsedHeader {
        if (bytes.size < 24) throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
        val brands = buildList {
            add(bytes.ascii(8, 4))
            var offset = 16
            val ftypLength = bytes.u32be(0).toInt().coerceAtMost(bytes.size)
            while (offset + 4 <= ftypLength) { add(bytes.ascii(offset, 4)); offset += 4 }
        }
        val format = when {
            brands.any { it in AVIF_BRANDS } -> ImageFormat.AVIF
            brands.any { it in HEIF_BRANDS } -> ImageFormat.HEIF
            else -> throw ProbeFailure(HeaderRejectionReason.UNSUPPORTED_SIGNATURE)
        }
        var width: Int? = null
        var height: Int? = null
        for (index in 4 until bytes.size - 15) {
            if (bytes.ascii(index, 4) == "ispe") {
                width = bytes.u32be(index + 8).positiveInt()
                height = bytes.u32be(index + 12).positiveInt()
                break
            }
        }
        val elements = mutableListOf<ContainerElement>()
        var offset = 0
        var complete = sourceLength <= bytes.size
        while (offset + 8 <= bytes.size && elements.size < policy.maximumContainerElements) {
            val boxLength = bytes.u32be(offset)
            if (boxLength < 8) throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
            val next = CheckedImageArithmetic.add(offset.toLong(), boxLength)
            if (next > sourceLength) throw ProbeFailure(HeaderRejectionReason.MALFORMED_CONTAINER)
            if (next > bytes.size) { complete = false; break }
            val type = bytes.asciiCode(offset + 4, 4)
            elements += ContainerElement(type, isoFamily(type), boxLength - 8)
            offset = next.toInt()
        }
        return ParsedHeader(
            ImageHeaderModel(
                format,
                width ?: throw ProbeFailure(HeaderRejectionReason.DIMENSIONS_UNAVAILABLE),
                height ?: throw ProbeFailure(HeaderRejectionReason.DIMENSIONS_UNAVAILABLE),
                8,
                3,
                false,
                brands.contains("avis"),
                null,
                ContainerStructureInventory(elements, complete && offset.toLong() == sourceLength),
            ),
            if (complete) sourceLength else null,
            emptySet(),
        )
    }

    private fun inspectTrailing(source: ImageByteSource, logicalEnd: Long?): TrailingStatus {
        if (logicalEnd == null || logicalEnd >= source.length) return TrailingStatus.NONE
        val count = minOf(32L, source.length - logicalEnd).toInt()
        val trailing = runCatching { source.read(logicalEnd, count) }.getOrElse { return TrailingStatus.OTHER_DATA }
        return try {
            when {
                trailing.all { it == 0.toByte() || it == '\n'.code.toByte() || it == '\r'.code.toByte() || it == ' '.code.toByte() } ->
                    TrailingStatus.NONE
                STRONG_SIGNATURES.any(trailing::startsWith) ||
                    (trailing.size >= 12 && trailing.ascii(4, 4) == "ftyp") -> TrailingStatus.STRONG_SECOND_FORMAT
                else -> TrailingStatus.OTHER_DATA
            }
        } finally {
            trailing.fill(0)
        }
    }

    private fun mimeMatches(claimed: String, format: ImageFormat): Boolean {
        val normalized = claimed.lowercase()
        return normalized == format.canonicalMime ||
            (format == ImageFormat.JPEG && normalized == "image/jpg") ||
            (format == ImageFormat.HEIF && normalized in setOf("image/heic", "image/heif-sequence"))
    }

    private fun pngFamily(type: String): ContainerElementFamily = when (type) {
        "IHDR", "PLTE" -> ContainerElementFamily.HEADER
        "IDAT" -> ContainerElementFamily.PIXEL_DATA
        "IEND" -> ContainerElementFamily.STRUCTURAL
        "acTL", "fcTL", "fdAT" -> ContainerElementFamily.ANIMATION
        "iCCP", "cHRM", "gAMA", "sRGB" -> ContainerElementFamily.COLOUR_PROFILE
        "tEXt", "zTXt", "iTXt" -> ContainerElementFamily.TEXT
        "eXIf", "tIME", "pHYs", "sPLT", "hIST" -> ContainerElementFamily.METADATA
        else -> ContainerElementFamily.UNKNOWN
    }

    private fun jpegFamily(marker: Int): ContainerElementFamily = when {
        marker in SOF_MARKERS -> ContainerElementFamily.HEADER
        marker == 0xfe -> ContainerElementFamily.TEXT
        marker == 0xe1 -> ContainerElementFamily.THUMBNAIL
        marker == 0xe2 -> ContainerElementFamily.COLOUR_PROFILE
        marker in 0xe0..0xef -> ContainerElementFamily.METADATA
        else -> ContainerElementFamily.STRUCTURAL
    }

    private fun webpFamily(type: String): ContainerElementFamily = when (type) {
        "VP8", "VP8L" -> ContainerElementFamily.PIXEL_DATA
        "VP8X" -> ContainerElementFamily.HEADER
        "ANIM", "ANMF" -> ContainerElementFamily.ANIMATION
        "ICCP" -> ContainerElementFamily.COLOUR_PROFILE
        "EXIF", "XMP" -> ContainerElementFamily.METADATA
        "ALPH" -> ContainerElementFamily.STRUCTURAL
        else -> ContainerElementFamily.UNKNOWN
    }

    private fun isoFamily(type: String): ContainerElementFamily = when (type) {
        "FTYP", "META", "IPRP", "IPCO", "ISPE" -> ContainerElementFamily.HEADER
        "MDAT" -> ContainerElementFamily.PIXEL_DATA
        "COLR" -> ContainerElementFamily.COLOUR_PROFILE
        "EXIF", "MIME" -> ContainerElementFamily.METADATA
        else -> ContainerElementFamily.UNKNOWN
    }

    private data class ParsedHeader(
        val header: ImageHeaderModel,
        val logicalEnd: Long?,
        val warnings: Set<HeaderProbeWarning>,
    )

    private class ProbeFailure(val reason: HeaderRejectionReason) : Exception()
    private enum class TrailingStatus { NONE, OTHER_DATA, STRONG_SECOND_FORMAT }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val JPEG_SIGNATURE = byteArrayOf(0xff.toByte(), 0xd8.toByte())
        val GIF_87 = "GIF87a".encodeToByteArray()
        val GIF_89 = "GIF89a".encodeToByteArray()
        val RIFF = "RIFF".encodeToByteArray()
        val BMP_SIGNATURE = "BM".encodeToByteArray()
        val VP8_FRAME_SIGNATURE = byteArrayOf(0x9d.toByte(), 0x01, 0x2a)
        val STRONG_SIGNATURES = listOf(
            PNG_SIGNATURE,
            JPEG_SIGNATURE,
            GIF_87,
            GIF_89,
            RIFF,
            BMP_SIGNATURE,
            byteArrayOf(0x50, 0x4b, 0x03, 0x04),
            "%PDF".encodeToByteArray(),
        )
        val SOF_MARKERS = setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)
        val AVIF_BRANDS = setOf("avif", "avis")
        val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1")
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = matches(0, prefix)

private fun ByteArray.matches(offset: Int, expected: ByteArray): Boolean {
    if (offset < 0 || offset + expected.size > size) return false
    return expected.indices.all { this[offset + it] == expected[it] }
}

private fun ByteArray.u8(offset: Int): Int {
    if (offset !in indices) throw IndexOutOfBoundsException()
    return this[offset].toInt() and 0xff
}

private fun ByteArray.u16be(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)
private fun ByteArray.u16le(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
private fun ByteArray.u24le(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8) or (u8(offset + 2) shl 16)

private fun ByteArray.u32be(offset: Int): Long =
    (u8(offset).toLong() shl 24) or (u8(offset + 1).toLong() shl 16) or
        (u8(offset + 2).toLong() shl 8) or u8(offset + 3).toLong()

private fun ByteArray.u32le(offset: Int): Long =
    u8(offset).toLong() or (u8(offset + 1).toLong() shl 8) or
        (u8(offset + 2).toLong() shl 16) or (u8(offset + 3).toLong() shl 24)

private fun ByteArray.i32le(offset: Int): Int = u32le(offset).toInt()

private fun ByteArray.ascii(offset: Int, count: Int): String {
    if (offset < 0 || count < 0 || offset + count > size) throw IndexOutOfBoundsException()
    return String(this, offset, count, Charsets.US_ASCII)
}

private fun ByteArray.asciiCode(offset: Int, count: Int, preserveCase: Boolean = false): String {
    val decoded = ascii(offset, count)
    val raw = if (preserveCase) decoded else decoded.uppercase()
    return if (raw.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' || it == ' ' }) {
        raw.trim().ifEmpty { "UNKNOWN" }
    } else {
        "UNKNOWN"
    }
}

private fun Long.positiveInt(): Int {
    if (this <= 0 || this > Int.MAX_VALUE) throw IllegalArgumentException()
    return toInt()
}

private fun Int.positiveInt(): Int {
    if (this <= 0) throw IllegalArgumentException()
    return this
}

private fun ByteArray.lastMarker(first: Int, second: Int): Int {
    for (index in size - 2 downTo 0) {
        if (u8(index) == first && u8(index + 1) == second) return index
    }
    return -1
}

private fun ByteArray.skipSubBlocks(start: Int): Int {
    var offset = start
    while (true) {
        if (offset >= size) throw IndexOutOfBoundsException()
        val length = u8(offset++)
        if (length == 0) return offset
        offset = Math.addExact(offset, length)
        if (offset > size) throw IndexOutOfBoundsException()
    }
}
