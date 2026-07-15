package app.shareguard.block.ocr

import app.shareguard.block.image.ArgbPixelImage
import app.shareguard.core.model.SafeSummary
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TemporaryOcrViewsTest {
    @Test
    fun `all recipes create independent bounded pixels and erase on close`() {
        val source = ArgbPixelImage(2, 1, intArrayOf(0xff102030.toInt(), 0xff8090a0.toInt()))
        val views = TemporaryOcrViewFactory().create(
            source,
            OcrViewRecipe.entries,
            policy(maximumViews = 4, maximumAggregatePixels = 8),
        )

        assertThat(views.map { it.recipe }).containsExactlyElementsIn(OcrViewRecipe.entries).inOrder()
        assertThat(views.single { it.recipe == OcrViewRecipe.ROTATE_CLOCKWISE }.width).isEqualTo(1)
        assertThat(views.single { it.recipe == OcrViewRecipe.ROTATE_CLOCKWISE }.height).isEqualTo(2)
        val gray = views.single { it.recipe == OcrViewRecipe.GRAYSCALE }.pixels.argbAt(0, 0)
        assertThat(gray ushr 16 and 0xff).isEqualTo(gray ushr 8 and 0xff)
        assertThat(source.argbAt(0, 0)).isEqualTo(0xff102030.toInt())

        views.forEach { view ->
            view.close()
            assertThat(view.deleted).isTrue()
            assertThrows(IllegalStateException::class.java) { view.pixels.argbAt(0, 0) }
        }
        source.close()
    }

    @Test
    fun `view count per-view pixels and aggregate pixels are hard preallocation guards`() {
        val source = ArgbPixelImage(2, 2, IntArray(4))

        assertThrows(IllegalArgumentException::class.java) {
            TemporaryOcrViewFactory().create(
                source,
                listOf(OcrViewRecipe.ORIGINAL, OcrViewRecipe.GRAYSCALE),
                policy(maximumViews = 1, maximumAggregatePixels = 8),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TemporaryOcrViewFactory().create(
                source,
                listOf(OcrViewRecipe.ORIGINAL),
                policy(maximumViews = 1, maximumAggregatePixels = 3),
            )
        }
        source.close()
    }

    private fun policy(maximumViews: Int, maximumAggregatePixels: Long) = OcrViewResourcePolicy(
        maximumViews,
        4,
        maximumAggregatePixels,
        SafeSummary("ocr-view-corpus-v1"),
    )
}
