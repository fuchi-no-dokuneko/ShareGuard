package app.shareguard.block.ocr

import app.shareguard.block.image.ArgbPixelImage
import app.shareguard.block.image.CheckedImageArithmetic
import app.shareguard.block.image.PixelImage
import app.shareguard.core.model.SafeSummary
import java.io.Closeable

enum class OcrViewRecipe { ORIGINAL, GRAYSCALE, INVERTED_GRAYSCALE, ROTATE_CLOCKWISE }

data class OcrViewResourcePolicy(
    val maximumViews: Int,
    val maximumPixelsPerView: Long,
    val maximumAggregatePixels: Long,
    val validationReference: SafeSummary,
) {
    init {
        require(maximumViews in 1..32)
        require(maximumPixelsPerView > 0 && maximumAggregatePixels > 0)
        require(validationReference.value.isNotBlank())
    }
}

class TemporaryOcrView internal constructor(
    val viewId: SafeSummary,
    val recipe: OcrViewRecipe,
    internal val pixels: PixelImage,
) : Closeable {
    var deleted: Boolean = false
        private set
    val width: Int get() = pixels.width
    val height: Int get() = pixels.height

    override fun close() {
        if (!deleted) {
            pixels.close()
            deleted = true
        }
    }

    override fun toString(): String =
        "TemporaryOcrView(view=<redacted>,recipe=$recipe,width=$width,height=$height,deleted=$deleted)"
}

class TemporaryOcrViewFactory {
    fun create(
        source: PixelImage,
        recipes: List<OcrViewRecipe>,
        policy: OcrViewResourcePolicy,
    ): List<TemporaryOcrView> {
        check(!source.isClosed)
        require(recipes.isNotEmpty() && recipes.distinct().size == recipes.size)
        require(recipes.size <= policy.maximumViews)
        val sourcePixels = CheckedImageArithmetic.multiply(source.width.toLong(), source.height.toLong())
        require(sourcePixels <= policy.maximumPixelsPerView)
        require(CheckedImageArithmetic.multiply(sourcePixels, recipes.size.toLong()) <= policy.maximumAggregatePixels)
        val views = mutableListOf<TemporaryOcrView>()
        try {
            recipes.forEachIndexed { index, recipe ->
                views += TemporaryOcrView(
                    SafeSummary("ocr-view-${index + 1}"),
                    recipe,
                    transform(source, recipe),
                )
            }
            return views
        } catch (failure: Throwable) {
            views.forEach(TemporaryOcrView::close)
            throw failure
        }
    }

    private fun transform(source: PixelImage, recipe: OcrViewRecipe): ArgbPixelImage {
        val rotates = recipe == OcrViewRecipe.ROTATE_CLOCKWISE
        val width = if (rotates) source.height else source.width
        val height = if (rotates) source.width else source.height
        val output = IntArray(Math.multiplyExact(width, height))
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val original = source.argbAt(x, y)
            val transformed = when (recipe) {
                OcrViewRecipe.ORIGINAL, OcrViewRecipe.ROTATE_CLOCKWISE -> original
                OcrViewRecipe.GRAYSCALE, OcrViewRecipe.INVERTED_GRAYSCALE -> {
                    val alpha = original ushr 24 and 0xff
                    var luma = (((original ushr 16 and 0xff) * 77 + (original ushr 8 and 0xff) * 150 +
                        (original and 0xff) * 29) ushr 8)
                    if (recipe == OcrViewRecipe.INVERTED_GRAYSCALE) luma = 255 - luma
                    (alpha shl 24) or (luma shl 16) or (luma shl 8) or luma
                }
            }
            val targetX = if (rotates) source.height - 1 - y else x
            val targetY = if (rotates) x else y
            output[targetY * width + targetX] = transformed
        }
        return ArgbPixelImage(width, height, output).also { output.fill(0) }
    }
}
