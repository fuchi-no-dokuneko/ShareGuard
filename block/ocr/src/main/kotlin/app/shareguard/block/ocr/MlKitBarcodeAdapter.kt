package app.shareguard.block.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import app.shareguard.core.model.NormalizedRect
import app.shareguard.core.model.SafeSummary
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bundled barcode adapter. The artifact contains its models/JNI and has no cloud or downloadable fallback. */
class MlKitBundledBarcodeRecognizer private constructor(
    private val scanner: BarcodeScanner,
) : LocalBarcodeRecognizer {
    override val engineId: SafeSummary = SafeSummary("mlkit-bundled-barcode-17.3.0")
    override val executionMode: OcrExecutionMode = OcrExecutionMode.BUNDLED_LOCAL
    private var closed = false

    override suspend fun scan(view: TemporaryOcrView): List<BarcodeObservation> {
        check(!closed)
        check(!view.deleted)
        val bitmap = view.pixels.toBarcodeBitmap()
        try {
            val barcodes = scanner.process(InputImage.fromBitmap(bitmap, 0)).awaitNativeBoundary()
            currentCoroutineContext().ensureActive()
            return barcodes.mapNotNull { barcode ->
                val rect = barcode.boundingBox ?: return@mapNotNull null
                BarcodeObservation(
                    decodedValue = barcode.rawValue,
                    formatCode = barcode.format,
                    valueTypeCode = barcode.valueType,
                    bounds = rect.toNormalized(view.width, view.height),
                )
            }
        } finally {
            bitmap.eraseColor(0)
            bitmap.recycle()
        }
    }

    override fun close() {
        if (!closed) {
            scanner.close()
            closed = true
        }
    }

    companion object {
        fun bundled(): MlKitBundledBarcodeRecognizer {
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()
            return MlKitBundledBarcodeRecognizer(BarcodeScanning.getClient(options))
        }
    }
}

private fun app.shareguard.block.image.PixelImage.toBarcodeBitmap(): Bitmap {
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

private fun Rect.toNormalized(width: Int, height: Int): NormalizedRect {
    val normalizedLeft = (left.toFloat() / width).coerceIn(0f, 1f)
    val normalizedTop = (top.toFloat() / height).coerceIn(0f, 1f)
    return NormalizedRect(
        normalizedLeft,
        normalizedTop,
        (right.toFloat() / width).coerceIn(normalizedLeft, 1f),
        (bottom.toFloat() / height).coerceIn(normalizedTop, 1f),
    )
}
