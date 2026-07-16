package app.shareguard.canonical

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import app.shareguard.block.image.AndroidBitmapDecoderBackend
import app.shareguard.block.image.AnimatedInputPolicy
import app.shareguard.block.image.ByteArrayImageByteSource
import app.shareguard.block.image.ByteArrayImageDecodeSource
import app.shareguard.block.image.ByteArrayMetadataInputSource
import app.shareguard.block.image.ControlledImageDecoder
import app.shareguard.block.image.ControlledDecodeResult
import app.shareguard.block.image.ImageDiagnosticPolicy
import app.shareguard.block.image.ImageDiagnosticScanner
import app.shareguard.block.image.ImageHeaderProbe
import app.shareguard.block.image.ImageHeaderProbePolicy
import app.shareguard.block.image.ImageHeaderProbeResult
import app.shareguard.block.image.ImageHeaderModel
import app.shareguard.block.image.ImageResourcePlanner
import app.shareguard.block.image.ImageResourcePolicy
import app.shareguard.block.image.MaintainedMetadataInventory
import app.shareguard.block.image.MetadataInventoryResult
import app.shareguard.block.image.MetadataInventoryPolicy
import app.shareguard.block.ocr.MlKitTextRecognizerAdapter
import app.shareguard.block.ocr.MlKitBundledBarcodeRecognizer
import app.shareguard.block.ocr.BarcodeRoutingReceipt
import app.shareguard.block.ocr.BarcodeRoutingService
import app.shareguard.block.ocr.BarcodeUnicodeGate
import app.shareguard.block.ocr.CanonicalTextAndUrlRouter
import app.shareguard.block.ocr.OcrScript
import app.shareguard.block.ocr.OcrViewRecipe
import app.shareguard.block.ocr.OcrViewResourcePolicy
import app.shareguard.block.ocr.TemporaryOcrViewFactory
import app.shareguard.block.ocr.UnicodeGatedBarcodeValue
import app.shareguard.block.text.TextCanonicalizer
import app.shareguard.block.text.TextProcessingInput
import app.shareguard.block.text.TextSourceKind
import app.shareguard.block.url.UrlProcessingInput
import app.shareguard.block.url.UrlProcessingService
import app.shareguard.block.url.UrlSourceKind
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.MimeType
import app.shareguard.core.model.SafeSummary
import app.shareguard.feature.entry.AcceptedImageSummary

data class LocalImageImportResult(
    val summary: AcceptedImageSummary,
    val provisionalOcrText: String,
    val ocrViewsAgreed: Boolean,
    val machineReadableRegionCount: Int,
    val machineReadableReviewRequired: Boolean,
    val transientPreview: Bitmap,
) {
    override fun toString(): String =
        "LocalImageImportResult(summary=$summary,text=redacted,ocrViewsAgreed=$ocrViewsAgreed," +
            "machineReadableRegions=$machineReadableRegionCount,codeReview=$machineReadableReviewRequired)"
}

class LocalDerivativeSource internal constructor(
    val orientationAppliedBitmap: Bitmap,
    val diagnosticLimitationCodes: Set<String>,
) : AutoCloseable {
    override fun close() {
        if (!orientationAppliedBitmap.isRecycled) {
            orientationAppliedBitmap.eraseColor(0)
            orientationAppliedBitmap.recycle()
        }
    }

    override fun toString(): String =
        "LocalDerivativeSource(bitmap=redacted,limitations=${diagnosticLimitationCodes.size})"
}

/** Inspects only the sealed app-private snapshot and treats all OCR text as provisional user input. */
class LocalImageImportWorkflow {
    suspend fun inspect(bytes: ByteArray, claimedMime: String?): LocalImageImportResult {
        return withDecoded(bytes, claimedMime) { header, metadata, decoded ->
            val pixelCount = decoded.pixels.width.toLong() * decoded.pixels.height.toLong()
            val recipes = listOf(OcrViewRecipe.ORIGINAL, OcrViewRecipe.GRAYSCALE)
            val views = TemporaryOcrViewFactory().create(
                decoded.pixels,
                recipes,
                OcrViewResourcePolicy(
                    maximumViews = recipes.size,
                    maximumPixelsPerView = pixelCount,
                    maximumAggregatePixels = pixelCount * recipes.size,
                    validationReference = SafeSummary("manual-review-ocr-runtime-bounds-v1"),
                ),
            )
            val textRecognizer = MlKitTextRecognizerAdapter.bundled(OcrScript.LATIN)
            val barcodeRecognizer = MlKitBundledBarcodeRecognizer.bundled()
            var barcodeReviewRequired = false
            val barcodeRouter = BarcodeRoutingService(
                unicodeGate = BarcodeUnicodeGate { decoded ->
                    val inspection = TextCanonicalizer().canonicalize(
                        TextProcessingInput.create(decoded, TextSourceKind.IDENTIFIER),
                        CanonicalRevision(1),
                        idPrefix = "barcode-unicode",
                    )
                    barcodeReviewRequired = barcodeReviewRequired || inspection.requiresReview ||
                        inspection.failures.isNotEmpty()
                    UnicodeGatedBarcodeValue(inspection.canonicalText)
                },
                textAndUrlRouter = CanonicalTextAndUrlRouter { gated ->
                    val routed = UrlProcessingService().process(
                        UrlProcessingInput.create(gated.text, UrlSourceKind.QR_PAYLOAD),
                        CanonicalRevision(1),
                        idPrefix = "barcode-url",
                    )
                    val review = routed.analysisBatch.reviewGates.any { it.blocking } ||
                        routed.analysisBatch.failures.isNotEmpty()
                    barcodeReviewRequired = barcodeReviewRequired || review
                    BarcodeRoutingReceipt(
                        if (review) "BARCODE_ROUTE_REVIEW_REQUIRED" else "BARCODE_ROUTE_INSPECTED",
                    )
                },
            )
            val (candidates, machineReadableRegions) = try {
                val textCandidates = views.map { view ->
                    textRecognizer.recognize(view).observations
                        .sortedWith(compareBy({ it.geometry.bounds.top }, { it.geometry.bounds.left }))
                        .joinToString("\n") { it.text }
                }
                val codeRegions = barcodeRecognizer.scan(views.first()).map { barcodeRouter.route(it) }
                textCandidates to codeRegions
            } finally {
                textRecognizer.close()
                barcodeRecognizer.close()
                views.forEach { it.close() }
            }
            LocalImageImportResult(
                summary = AcceptedImageSummary(
                    detectedFormat = header.format.name,
                    pixelWidth = header.width,
                    pixelHeight = header.height,
                    metadataEntryCount = metadata.directories.sumOf { it.tags.size },
                    animated = header.animated,
                ),
                provisionalOcrText = candidates.firstOrNull().orEmpty(),
                ocrViewsAgreed = candidates.distinct().size == 1,
                machineReadableRegionCount = machineReadableRegions.size,
                machineReadableReviewRequired = barcodeReviewRequired ||
                    machineReadableRegions.any { it.reviewReasons.isNotEmpty() },
                transientPreview = decoded.pixels.toBoundedPreview(),
            )
        }
    }

    /** Reopens the sealed snapshot and materializes the orientation before any derivative transform. */
    suspend fun materializeDerivativeSource(bytes: ByteArray, claimedMime: String?): LocalDerivativeSource =
        withDecoded(bytes, claimedMime) { _, _, decoded ->
            val maximumExamined = minOf(
                decoded.pixels.width.toLong() * decoded.pixels.height.toLong(),
                1_000_000L,
            ).toInt().coerceAtLeast(1)
            val diagnostics = ImageDiagnosticScanner().scan(
                decoded.pixels,
                ImageDiagnosticPolicy(
                    maximumPixelsExamined = maximumExamined,
                    resourceValidationReference = SafeSummary("diagnostics-disabled-until-corpus-calibration-v1"),
                ),
            )
            check(!diagnostics.canCertifyAbsence)
            LocalDerivativeSource(
                orientationAppliedBitmap = decoded.pixels.toOwnedBitmap(),
                diagnosticLimitationCodes = setOf(
                    "SOURCE_DIAGNOSTICS_NOT_CORPUS_CALIBRATED",
                    "DERIVATIVE_DIAGNOSTIC_COMPARISON_NOT_RUN",
                ),
            )
        }

    private suspend fun <T> withDecoded(
        bytes: ByteArray,
        claimedMime: String?,
        block: suspend (ImageHeaderModel, MetadataInventoryResult, ControlledDecodeResult) -> T,
    ): T {
        val header = ByteArrayImageByteSource(bytes).use { source ->
            when (
                val probe = ImageHeaderProbe().probe(
                    source,
                    claimedMime?.takeIf { it.startsWith("image/") }?.let(::MimeType),
                    ImageHeaderProbePolicy(
                        maximumProbeBytes = minOf(bytes.size.coerceAtLeast(64), 8 * 1024 * 1024),
                        maximumContainerElements = 4_096,
                        validationReference = SafeSummary("bounded-header-inventory-v1"),
                    ),
                )
            ) {
                is ImageHeaderProbeResult.Accepted -> probe.header
                is ImageHeaderProbeResult.Rejected -> error("IMAGE_${probe.reason.name}")
            }
        }
        check(!header.animated) { "ANIMATED_IMAGE_NOT_SUPPORTED" }
        val metadata = MaintainedMetadataInventory().inspect(
            ByteArrayMetadataInputSource(bytes),
            MetadataInventoryPolicy(
                maximumDirectories = 1_024,
                maximumTags = 32_768,
                validationReference = SafeSummary("bounded-metadata-inventory-v1"),
            ),
        )
        check(metadata.complete && !metadata.parserFailed) { "IMAGE_METADATA_INVENTORY_INCOMPLETE" }
        val managedHeapBudget = (Runtime.getRuntime().maxMemory() / 8L).coerceAtLeast(8L * 1024L * 1024L)
        val maximumPixels = managedHeapBudget / 4L
        val plan = ImageResourcePlanner().plan(
            header,
            ImageResourcePolicy(
                maximumWidth = Int.MAX_VALUE,
                maximumHeight = Int.MAX_VALUE,
                maximumPixels = maximumPixels,
                maximumDecodedBytes = managedHeapBudget,
                maximumFrames = 1,
                allowSampling = true,
                allowTiling = false,
                animatedInputPolicy = AnimatedInputPolicy.REJECT,
                validationReference = SafeSummary("android-managed-heap-fraction-v1"),
            ),
        )
        check(plan.approved) { "IMAGE_RESOURCE_${plan.reason.name}" }
        val decoded = ControlledImageDecoder(AndroidBitmapDecoderBackend()).use { decoder ->
            decoder.decode(ByteArrayImageDecodeSource(bytes), header, plan, metadata.orientation)
        }
        return decoded.use { block(header, metadata, it) }
    }

    private fun app.shareguard.block.image.PixelImage.toOwnedBitmap(): Bitmap {
        val storage = IntArray(Math.multiplyExact(width, height))
        val bitmap = createBitmap(width, height)
        try {
            for (y in 0 until height) for (x in 0 until width) storage[y * width + x] = argbAt(x, y)
            bitmap.setPixels(storage, 0, width, 0, 0, width, height)
            return bitmap
        } catch (failure: Throwable) {
            bitmap.eraseColor(0)
            bitmap.recycle()
            throw failure
        } finally {
            storage.fill(0)
        }
    }

    private fun app.shareguard.block.image.PixelImage.toBoundedPreview(): Bitmap {
        val full = toOwnedBitmap()
        val scale = minOf(1f, MAXIMUM_PREVIEW_DIMENSION.toFloat() / maxOf(width, height))
        if (scale >= 1f) return full
        return try {
            full.scale(
                maxOf(1, (width * scale).toInt()),
                maxOf(1, (height * scale).toInt()),
            )
        } finally {
            full.eraseColor(0)
            full.recycle()
        }
    }

    private companion object {
        const val MAXIMUM_PREVIEW_DIMENSION = 1_024
    }
}
