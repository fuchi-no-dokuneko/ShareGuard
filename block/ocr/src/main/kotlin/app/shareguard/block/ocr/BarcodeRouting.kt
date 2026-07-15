package app.shareguard.block.ocr

import app.shareguard.core.model.ImageRegionPolicy
import app.shareguard.core.model.ImageRegionType
import app.shareguard.core.model.NormalizedRect
import app.shareguard.core.model.SafeSummary
import java.io.Closeable
import app.shareguard.block.image.PixelImage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class BarcodeObservation(
    val decodedValue: String?,
    val formatCode: Int,
    val valueTypeCode: Int,
    val bounds: NormalizedRect,
) {
    override fun toString(): String =
        "BarcodeObservation(value=<redacted>,decoded=${decodedValue != null},formatCode=$formatCode,valueTypeCode=$valueTypeCode,bounds=$bounds)"
}

interface LocalBarcodeRecognizer : Closeable {
    val engineId: SafeSummary
    val executionMode: OcrExecutionMode
    suspend fun scan(view: TemporaryOcrView): List<BarcodeObservation>
}

/** Must apply the same Unicode controls used by the canonical text pipeline. */
fun interface BarcodeUnicodeGate {
    suspend fun normalizeAndValidate(decodedValue: String): UnicodeGatedBarcodeValue
}

class UnicodeGatedBarcodeValue(val text: String) {
    init { require(text.isNotEmpty()) }
    override fun toString(): String = "UnicodeGatedBarcodeValue(text=<redacted>)"
}

/** Must pass the gated value through canonical text handling and URL parsing/policy when applicable. */
fun interface CanonicalTextAndUrlRouter {
    suspend fun routeTextAndPossibleUrl(value: UnicodeGatedBarcodeValue): BarcodeRoutingReceipt
}

@JvmInline
value class BarcodeRoutingReceipt(val code: String) {
    init { require(code.matches(Regex("[A-Z][A-Z0-9_]{1,63}"))) }
}

data class BarcodeRegionResult(
    val regionType: ImageRegionType,
    val bounds: NormalizedRect,
    val decoded: Boolean,
    val regionPolicy: ImageRegionPolicy,
    val reviewReasons: Set<OcrReviewReason>,
    val routingReceipt: BarcodeRoutingReceipt?,
) {
    init {
        require(regionType in setOf(ImageRegionType.QR_CODE, ImageRegionType.BARCODE))
        if (!decoded) {
            require(regionPolicy != ImageRegionPolicy.RETAIN_SOURCE_PIXELS)
            require(OcrReviewReason.UNDECODABLE_REGION in reviewReasons)
            require(routingReceipt == null)
        } else {
            require(routingReceipt != null)
        }
    }
}

class BarcodeRoutingService(
    private val unicodeGate: BarcodeUnicodeGate,
    private val textAndUrlRouter: CanonicalTextAndUrlRouter,
) {
    suspend fun route(observation: BarcodeObservation): BarcodeRegionResult {
        val regionType = if (observation.formatCode == QR_FORMAT_CODE) ImageRegionType.QR_CODE else ImageRegionType.BARCODE
        val value = observation.decodedValue
        if (value == null) {
            return BarcodeRegionResult(
                regionType,
                observation.bounds,
                decoded = false,
                regionPolicy = ImageRegionPolicy.SOLID_REDACT,
                reviewReasons = setOf(OcrReviewReason.UNDECODABLE_REGION),
                routingReceipt = null,
            )
        }
        val gated = unicodeGate.normalizeAndValidate(value)
        currentCoroutineContext().ensureActive()
        val receipt = textAndUrlRouter.routeTextAndPossibleUrl(gated)
        return BarcodeRegionResult(
            regionType,
            observation.bounds,
            decoded = true,
            regionPolicy = ImageRegionPolicy.REBUILD_FROM_STRUCTURED_DATA,
            reviewReasons = emptySet(),
            routingReceipt = receipt,
        )
    }

    private companion object {
        // Mirrors Barcode.FORMAT_QR_CODE without coupling the domain service to ML Kit.
        const val QR_FORMAT_CODE = 256
    }
}

data class BarcodeConsensusPolicy(
    val minimumIndependentViews: Int,
    val minimumGeometryIntersectionOverUnion: Float,
    val validationReference: SafeSummary,
) {
    init {
        require(minimumIndependentViews > 0)
        require(minimumGeometryIntersectionOverUnion.isFinite() && minimumGeometryIntersectionOverUnion in 0f..1f)
        require(validationReference.value.isNotBlank())
    }
}

class LocalBarcodeOrchestrator(
    private val viewFactory: TemporaryOcrViewFactory = TemporaryOcrViewFactory(),
    private val observer: OcrLifecycleObserver = OcrLifecycleObserver.None,
) {
    suspend fun run(
        source: PixelImage,
        recipes: List<OcrViewRecipe>,
        resourcePolicy: OcrViewResourcePolicy,
        consensusPolicy: BarcodeConsensusPolicy,
        recognizer: LocalBarcodeRecognizer,
        router: BarcodeRoutingService,
    ): List<BarcodeRegionResult> {
        var views: List<TemporaryOcrView> = emptyList()
        val observations = mutableListOf<Pair<SafeSummary, BarcodeObservation>>()
        try {
            require(recognizer.executionMode == OcrExecutionMode.BUNDLED_LOCAL)
            views = viewFactory.create(source, recipes, resourcePolicy)
            for (view in views) {
                currentCoroutineContext().ensureActive()
                observations += recognizer.scan(view).map { view.viewId to it }
                currentCoroutineContext().ensureActive()
            }
            val clusters = mutableListOf<MutableList<Pair<SafeSummary, BarcodeObservation>>>()
            for (observation in observations) {
                val cluster = clusters.firstOrNull { candidate ->
                    candidate.any {
                        barcodeIou(it.second.bounds, observation.second.bounds) >=
                            consensusPolicy.minimumGeometryIntersectionOverUnion
                    }
                }
                if (cluster == null) clusters += mutableListOf(observation) else cluster += observation
            }
            return clusters.map { cluster ->
                val values = cluster.mapNotNull { it.second.decodedValue }.distinct()
                val representative = cluster.first().second
                val independentSupportingViews = if (values.size == 1) {
                    cluster.filter { it.second.decodedValue == values.single() }.map { it.first }.distinct().size
                } else {
                    0
                }
                val allObservationsDecoded = cluster.all { it.second.decodedValue != null }
                val routed = if (values.size == 1 && allObservationsDecoded &&
                    independentSupportingViews >= consensusPolicy.minimumIndependentViews
                ) {
                    BarcodeObservation(
                        values.single(),
                        representative.formatCode,
                        representative.valueTypeCode,
                        representative.bounds,
                    )
                } else {
                    BarcodeObservation(null, representative.formatCode, representative.valueTypeCode, representative.bounds)
                }
                router.route(routed)
            }
        } catch (failure: Throwable) {
            val completed = observations.size
            observations.clear()
            observer.partialResultInvalidated(completed)
            throw failure
        } finally {
            observations.clear()
            views.forEach {
                it.close()
                observer.temporaryViewDeleted(it.viewId)
            }
            runCatching { recognizer.close() }
        }
    }

    private fun barcodeIou(first: NormalizedRect, second: NormalizedRect): Float {
        val intersection = maxOf(0f, minOf(first.right, second.right) - maxOf(first.left, second.left)) *
            maxOf(0f, minOf(first.bottom, second.bottom) - maxOf(first.top, second.top))
        val firstArea = (first.right - first.left) * (first.bottom - first.top)
        val secondArea = (second.right - second.left) * (second.bottom - second.top)
        val union = firstArea + secondArea - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
