package app.shareguard.block.image

import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A decoded image that owns only pixels. Encoded source bytes are never exposed by this API. */
interface PixelImage : Closeable {
    val width: Int
    val height: Int
    val isClosed: Boolean
    fun argbAt(x: Int, y: Int): Int
}

class ArgbPixelImage(
    override val width: Int,
    override val height: Int,
    pixels: IntArray,
) : PixelImage {
    private val storage = pixels.copyOf()
    override var isClosed: Boolean = false
        private set

    init {
        require(width > 0 && height > 0)
        require(CheckedImageArithmetic.multiply(width.toLong(), height.toLong()) == pixels.size.toLong())
    }

    override fun argbAt(x: Int, y: Int): Int {
        check(!isClosed) { "Pixel image is closed" }
        require(x in 0 until width && y in 0 until height)
        return storage[y * width + x]
    }

    override fun close() {
        if (!isClosed) {
            storage.fill(0)
            isClosed = true
        }
    }

    internal fun storageIsZeroizedForTest(): Boolean = storage.all { it == 0 }
}

enum class ExifOrientation(val exifValue: Int) {
    NORMAL(1),
    FLIP_HORIZONTAL(2),
    ROTATE_180(3),
    FLIP_VERTICAL(4),
    TRANSPOSE(5),
    ROTATE_90_CLOCKWISE(6),
    TRANSVERSE(7),
    ROTATE_270_CLOCKWISE(8),
    ;

    companion object {
        fun fromExifValue(value: Int?): ExifOrientation = entries.firstOrNull { it.exifValue == value } ?: NORMAL
    }
}

object ImageOrientationMaterializer {
    fun materialize(source: PixelImage, orientation: ExifOrientation): ArgbPixelImage {
        check(!source.isClosed)
        val swapsAxes = orientation in setOf(
            ExifOrientation.TRANSPOSE,
            ExifOrientation.ROTATE_90_CLOCKWISE,
            ExifOrientation.TRANSVERSE,
            ExifOrientation.ROTATE_270_CLOCKWISE,
        )
        val outputWidth = if (swapsAxes) source.height else source.width
        val outputHeight = if (swapsAxes) source.width else source.height
        val output = IntArray(Math.multiplyExact(outputWidth, outputHeight))
        for (sourceY in 0 until source.height) {
            for (sourceX in 0 until source.width) {
                val (targetX, targetY) = transform(sourceX, sourceY, source.width, source.height, orientation)
                output[targetY * outputWidth + targetX] = source.argbAt(sourceX, sourceY)
            }
        }
        return ArgbPixelImage(outputWidth, outputHeight, output).also { output.fill(0) }
    }

    private fun transform(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        orientation: ExifOrientation,
    ): Pair<Int, Int> = when (orientation) {
        ExifOrientation.NORMAL -> x to y
        ExifOrientation.FLIP_HORIZONTAL -> (width - 1 - x) to y
        ExifOrientation.ROTATE_180 -> (width - 1 - x) to (height - 1 - y)
        ExifOrientation.FLIP_VERTICAL -> x to (height - 1 - y)
        ExifOrientation.TRANSPOSE -> y to x
        ExifOrientation.ROTATE_90_CLOCKWISE -> (height - 1 - y) to x
        ExifOrientation.TRANSVERSE -> (height - 1 - y) to (width - 1 - x)
        ExifOrientation.ROTATE_270_CLOCKWISE -> y to (width - 1 - x)
    }
}

/** The source must be an app-private verified snapshot and is consumed by one decode attempt. */
interface ImageDecodeSource : Closeable {
    val length: Long
    fun openStream(): InputStream
}

class ByteArrayImageDecodeSource(bytes: ByteArray) : ImageDecodeSource {
    private val storage = bytes.copyOf()
    private var consumed = false
    private var closed = false

    override val length: Long
        get() = storage.size.toLong()

    override fun openStream(): InputStream {
        check(!closed) { "Decode source is closed" }
        check(!consumed) { "Decode source can only be consumed once" }
        consumed = true
        return ByteArrayInputStream(storage)
    }

    override fun close() {
        if (!closed) {
            storage.fill(0)
            closed = true
        }
    }

    internal fun isZeroizedForTest(): Boolean = storage.all { it == 0.toByte() }
}

interface ImageDecoderBackend : Closeable {
    /** Implementations may finish a non-interruptible native call; callers discard its result after cancellation. */
    suspend fun decode(source: ImageDecodeSource, plan: DecodeResourcePlan): PixelImage
    override fun close() = Unit
}

data class ChannelRange(val minimum: Int, val maximum: Int) {
    init { require(minimum in 0..255 && maximum in minimum..255) }
}

data class ChannelAlphaInventory(
    val red: ChannelRange,
    val green: ChannelRange,
    val blue: ChannelRange,
    val alpha: ChannelRange,
    val declaredAlpha: Boolean,
    val observedTransparency: Boolean,
    val colourProfileDeclared: Boolean,
)

data class ControlledDecodeResult(
    val pixels: PixelImage,
    val channels: ChannelAlphaInventory,
    val orientationMaterialized: Boolean,
    val sourceBufferRetained: Boolean = false,
) : Closeable {
    init { require(!sourceBufferRetained) }
    override fun close() = pixels.close()
}

class ControlledImageDecoder(private val backend: ImageDecoderBackend) : Closeable {
    suspend fun decode(
        source: ImageDecodeSource,
        header: ImageHeaderModel,
        plan: DecodeResourcePlan,
        orientation: ExifOrientation,
    ): ControlledDecodeResult {
        require(plan.approved) { "A rejected resource plan cannot be decoded" }
        var decoded: PixelImage? = null
        var oriented: PixelImage? = null
        try {
            currentCoroutineContext().ensureActive()
            decoded = backend.decode(source, plan)
            // A native decoder is allowed to return normally after cancellation. Never publish that result.
            currentCoroutineContext().ensureActive()
            validateDecodedDimensions(decoded, header, plan)
            oriented = ImageOrientationMaterializer.materialize(decoded, orientation)
            currentCoroutineContext().ensureActive()
            val inventory = inspectChannels(oriented, header)
            return ControlledDecodeResult(oriented, inventory, orientation != ExifOrientation.NORMAL).also {
                oriented = null
            }
        } finally {
            oriented?.close()
            decoded?.close()
            source.close()
        }
    }

    override fun close() = backend.close()

    private fun validateDecodedDimensions(decoded: PixelImage, header: ImageHeaderModel, plan: DecodeResourcePlan) {
        val expectedWidth = ceilDivide(header.width, plan.sampleSize)
        val expectedHeight = ceilDivide(header.height, plan.sampleSize)
        require(decoded.width == expectedWidth && decoded.height == expectedHeight) {
            "Decoder dimensions disagree with the approved resource plan"
        }
    }

    private fun inspectChannels(image: PixelImage, header: ImageHeaderModel): ChannelAlphaInventory {
        var minR = 255; var maxR = 0
        var minG = 255; var maxG = 0
        var minB = 255; var maxB = 0
        var minA = 255; var maxA = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val pixel = image.argbAt(x, y)
            val alpha = pixel ushr 24 and 0xff
            val red = pixel ushr 16 and 0xff
            val green = pixel ushr 8 and 0xff
            val blue = pixel and 0xff
            minA = minOf(minA, alpha); maxA = maxOf(maxA, alpha)
            minR = minOf(minR, red); maxR = maxOf(maxR, red)
            minG = minOf(minG, green); maxG = maxOf(maxG, green)
            minB = minOf(minB, blue); maxB = maxOf(maxB, blue)
        }
        return ChannelAlphaInventory(
            ChannelRange(minR, maxR),
            ChannelRange(minG, maxG),
            ChannelRange(minB, maxB),
            ChannelRange(minA, maxA),
            header.hasAlpha,
            minA < 255,
            header.container.elements.any { it.family == ContainerElementFamily.COLOUR_PROFILE },
        )
    }

    private fun ceilDivide(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()
}

/** BitmapFactory is a bounded local adapter; it does not use network-backed decoders. */
class AndroidBitmapDecoderBackend : ImageDecoderBackend {
    override suspend fun decode(source: ImageDecodeSource, plan: DecodeResourcePlan): PixelImage = withContext(Dispatchers.IO) {
        require(plan.strategy in setOf(DecodeStrategy.FULL, DecodeStrategy.SAMPLED)) {
            "BitmapFactory does not implement the separately-audited tiled strategy"
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = plan.sampleSize
            inScaled = false
            inMutable = false
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val bitmap = source.openStream().use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("Native image decode failed")
        try {
            val pixels = IntArray(Math.multiplyExact(bitmap.width, bitmap.height))
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            ArgbPixelImage(bitmap.width, bitmap.height, pixels).also { pixels.fill(0) }
        } finally {
            bitmap.recycle()
        }
    }
}
