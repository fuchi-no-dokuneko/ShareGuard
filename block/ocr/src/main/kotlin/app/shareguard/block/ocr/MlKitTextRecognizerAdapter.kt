package app.shareguard.block.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import app.shareguard.core.model.NormalizedRect
import app.shareguard.core.model.SafeSummary
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Adapter for the five model AARs packaged into the APK; it has no download or cloud fallback. */
class MlKitTextRecognizerAdapter private constructor(
    override val script: OcrScript,
    override val engineId: SafeSummary,
    private val recognizer: TextRecognizer,
) : LocalTextRecognizer {
    override val executionMode: OcrExecutionMode = OcrExecutionMode.BUNDLED_LOCAL
    private var closed = false

    override suspend fun recognize(view: TemporaryOcrView): OcrEngineOutput {
        check(!closed)
        check(!view.deleted)
        val bitmap = view.pixels.toBitmap()
        try {
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitNativeBoundary()
            currentCoroutineContext().ensureActive()
            val observations = result.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line -> line.toObservation(view, script, engineId) }
            }
            return OcrEngineOutput(observations, script, engineId, view.viewId)
        } finally {
            bitmap.eraseColor(0)
            bitmap.recycle()
        }
    }

    override fun close() {
        if (!closed) {
            recognizer.close()
            closed = true
        }
    }

    companion object {
        fun bundled(script: OcrScript): MlKitTextRecognizerAdapter {
            val recognizer = when (script) {
                OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                OcrScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            }
            return MlKitTextRecognizerAdapter(
                script,
                SafeSummary("mlkit-bundled-${script.name.lowercase()}-16.0.1"),
                recognizer,
            )
        }
    }
}

private fun Text.Line.toObservation(
    view: TemporaryOcrView,
    script: OcrScript,
    engineId: SafeSummary,
): OcrTextObservation? {
    if (text.isEmpty()) return null
    val rect = boundingBox ?: return null
    val bounds = rect.normalized(view.width, view.height)
    val bottom = bounds.bottom
    return OcrTextObservation(
        text = text,
        geometry = OcrGeometry(
            bounds = bounds,
            baselineStart = OcrPoint(bounds.left, bottom),
            baselineEnd = OcrPoint(bounds.right, bottom),
            orientationDegrees = angle,
            geometryUncertain = boundingBox == null || cornerPoints == null,
        ),
        script = script,
        engineId = engineId,
        viewId = view.viewId,
        confidence = confidence.takeIf { it.isFinite() && it in 0f..1f },
    )
}

private fun Rect.normalized(width: Int, height: Int): NormalizedRect {
    require(width > 0 && height > 0)
    val left = (left.toFloat() / width).coerceIn(0f, 1f)
    val top = (top.toFloat() / height).coerceIn(0f, 1f)
    val right = (right.toFloat() / width).coerceIn(left, 1f)
    val bottom = (bottom.toFloat() / height).coerceIn(top, 1f)
    return NormalizedRect(left, top, right, bottom)
}

private fun app.shareguard.block.image.PixelImage.toBitmap(): Bitmap {
    val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val storage = IntArray(Math.multiplyExact(width, height))
    try {
        for (y in 0 until height) for (x in 0 until width) storage[y * width + x] = argbAt(x, y)
        bitmap.setPixels(storage, 0, width, 0, 0, width, height)
        return bitmap
    } catch (failure: Throwable) {
        bitmap.recycle()
        throw failure
    } finally {
        storage.fill(0)
    }
}

/** suspendCoroutine intentionally defers cancellation until the non-interruptible native Task completes. */
internal suspend fun <T> Task<T>.awaitNativeBoundary(): T = suspendCoroutine { continuation ->
    addOnCompleteListener { task ->
        when {
            task.isSuccessful -> continuation.resume(task.result)
            task.isCanceled -> continuation.resumeWithException(kotlinx.coroutines.CancellationException("Native ML task cancelled"))
            else -> continuation.resumeWithException(task.exception ?: IllegalStateException("Native ML task failed"))
        }
    }
}
