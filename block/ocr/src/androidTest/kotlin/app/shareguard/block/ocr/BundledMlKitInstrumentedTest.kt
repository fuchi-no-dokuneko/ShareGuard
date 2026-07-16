package app.shareguard.block.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.shareguard.block.image.ArgbPixelImage
import app.shareguard.core.model.SafeSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledMlKitInstrumentedTest {
    @Test
    fun everyDeclaredScriptCreatesOnlyBundledLocalRecognizer() {
        OcrScript.entries.forEach { script ->
            val recognizer = MlKitTextRecognizerAdapter.bundled(script)
            try {
                assertEquals(script, recognizer.script)
                assertEquals(OcrExecutionMode.BUNDLED_LOCAL, recognizer.executionMode)
            } finally {
                recognizer.close()
            }
        }
    }

    @Test
    fun barcodeRecognizerIsBundledLocal() {
        val recognizer = MlKitBundledBarcodeRecognizer.bundled()
        try {
            assertEquals(OcrExecutionMode.BUNDLED_LOCAL, recognizer.executionMode)
        } finally {
            recognizer.close()
        }
    }

    @Test
    fun bundledLatinModelExecutesOnGeneratedPixelsWithoutDownload() = runBlocking {
        val bitmap = Bitmap.createBitmap(720, 240, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(bitmap.width * bitmap.height)
        val source = try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText(
                    "OFFLINE OCR 123",
                    28f,
                    150f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 76f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    },
                )
            }
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            ArgbPixelImage(bitmap.width, bitmap.height, pixels)
        } finally {
            pixels.fill(0)
            bitmap.eraseColor(Color.TRANSPARENT)
            bitmap.recycle()
        }
        val view = TemporaryOcrViewFactory().create(
            source,
            listOf(OcrViewRecipe.ORIGINAL),
            OcrViewResourcePolicy(
                maximumViews = 1,
                maximumPixelsPerView = source.width.toLong() * source.height,
                maximumAggregatePixels = source.width.toLong() * source.height,
                validationReference = SafeSummary("generated-offline-ocr-canary-v1"),
            ),
        ).single()
        val recognizer = MlKitTextRecognizerAdapter.bundled(OcrScript.LATIN)
        try {
            val recognized = recognizer.recognize(view).observations.joinToString(" ") { it.text }
            assertTrue("Bundled OCR did not recognize its offline canary: $recognized", recognized.contains("OFFLINE"))
        } finally {
            recognizer.close()
            view.close()
            source.close()
        }
    }
}
