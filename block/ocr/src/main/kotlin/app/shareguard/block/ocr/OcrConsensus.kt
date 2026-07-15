package app.shareguard.block.ocr

import app.shareguard.core.model.NormalizedRect
import app.shareguard.core.model.SafeSummary
import java.text.Normalizer
import kotlin.math.abs

data class OcrConsensusPolicy(
    val minimumIndependentObservations: Int,
    val minimumGeometryIntersectionOverUnion: Float,
    val maximumNormalizedEdgeDelta: Float,
    val minimumConfidence: Float?,
    val calibrationReference: SafeSummary,
) {
    init {
        require(minimumIndependentObservations >= 2)
        require(minimumGeometryIntersectionOverUnion.isFinite() && minimumGeometryIntersectionOverUnion in 0f..1f)
        require(maximumNormalizedEdgeDelta.isFinite() && maximumNormalizedEdgeDelta in 0f..1f)
        require(minimumConfidence == null || (minimumConfidence.isFinite() && minimumConfidence in 0f..1f))
        require(calibrationReference.value.isNotBlank())
    }
}

class ConservativeOcrConsensus {
    fun reconcile(
        outputs: List<OcrEngineOutput>,
        expectedObservationSources: Int,
        policy: OcrConsensusPolicy,
    ): OcrConsensusReport {
        require(expectedObservationSources >= outputs.size)
        val observations = outputs.flatMap { it.observations }
        val clusters = mutableListOf<MutableList<OcrTextObservation>>()
        for (observation in observations) {
            val cluster = clusters.firstOrNull {
                it.any { candidate -> intersectionOverUnion(candidate.geometry.bounds, observation.geometry.bounds) >=
                    policy.minimumGeometryIntersectionOverUnion }
            }
            if (cluster == null) clusters += mutableListOf(observation) else cluster += observation
        }

        var regions = clusters.map { cluster -> reconcileCluster(cluster, expectedObservationSources, policy) }
        val overlapping = mutableSetOf<Int>()
        for (left in regions.indices) for (right in left + 1 until regions.size) {
            if (intersectionArea(regions[left].referenceGeometry.bounds, regions[right].referenceGeometry.bounds) > 0f) {
                overlapping += left
                overlapping += right
            }
        }
        if (overlapping.isNotEmpty()) {
            regions = regions.mapIndexed { index, region ->
                if (index !in overlapping) region else ConsensusTextRegion(
                    text = null,
                    referenceGeometry = region.referenceGeometry.copy(geometryUncertain = true),
                    scriptsObserved = region.scriptsObserved,
                    agreeingEngineIds = region.agreeingEngineIds,
                    agreeingViewIds = region.agreeingViewIds,
                    reviewReasons = region.reviewReasons + OcrReviewReason.OVERLAPPING_READING_ORDER,
                )
            }
        }
        return OcrConsensusReport(regions, expectedObservationSources, outputs.size)
    }

    private fun reconcileCluster(
        cluster: List<OcrTextObservation>,
        expectedSources: Int,
        policy: OcrConsensusPolicy,
    ): ConsensusTextRegion {
        val byText = cluster.groupBy { normalizedForComparison(it.text) }
        val ranked = byText.values.sortedByDescending { independentSourceCount(it) }
        val winner = ranked.first()
        val winnerCount = independentSourceCount(winner)
        val tied = ranked.size > 1 && independentSourceCount(ranked[1]) == winnerCount
        val scripts = cluster.map { it.script }.toSet()
        val reasons = mutableSetOf<OcrReviewReason>()
        if (byText.size > 1 || tied || winnerCount < policy.minimumIndependentObservations) {
            reasons += OcrReviewReason.ENGINE_DISAGREEMENT
        }
        if (winnerCount < expectedSources) reasons += OcrReviewReason.MISSING_ENGINE_OBSERVATION
        if (scripts.size > 1) reasons += OcrReviewReason.SCRIPT_CONFLICT
        val reference = winner.first()
        if (winner.any { edgeDelta(reference.geometry.bounds, it.geometry.bounds) > policy.maximumNormalizedEdgeDelta }) {
            reasons += OcrReviewReason.GEOMETRY_CONFLICT
        }
        if (policy.minimumConfidence != null && winner.any { it.confidence == null || it.confidence < policy.minimumConfidence }) {
            reasons += OcrReviewReason.LOW_CONFIDENCE
        }
        val acceptedText = if (reasons.isEmpty()) reference.text else null
        return ConsensusTextRegion(
            text = acceptedText,
            referenceGeometry = reference.geometry.copy(geometryUncertain = reference.geometry.geometryUncertain || reasons.isNotEmpty()),
            scriptsObserved = scripts,
            agreeingEngineIds = winner.map { it.engineId }.toSet(),
            agreeingViewIds = winner.map { it.viewId }.toSet(),
            reviewReasons = reasons,
        )
    }

    private fun independentSourceCount(observations: List<OcrTextObservation>): Int =
        observations.map { it.engineId to it.viewId }.distinct().size

    private fun normalizedForComparison(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).trim().replace(Regex("\\s+"), " ")

    private fun edgeDelta(first: NormalizedRect, second: NormalizedRect): Float = maxOf(
        abs(first.left - second.left),
        abs(first.top - second.top),
        abs(first.right - second.right),
        abs(first.bottom - second.bottom),
    )

    private fun intersectionOverUnion(first: NormalizedRect, second: NormalizedRect): Float {
        val intersection = intersectionArea(first, second)
        val firstArea = (first.right - first.left) * (first.bottom - first.top)
        val secondArea = (second.right - second.left) * (second.bottom - second.top)
        val union = firstArea + secondArea - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun intersectionArea(first: NormalizedRect, second: NormalizedRect): Float =
        maxOf(0f, minOf(first.right, second.right) - maxOf(first.left, second.left)) *
            maxOf(0f, minOf(first.bottom, second.bottom) - maxOf(first.top, second.top))
}

data class OcrLayoutPolicy(
    val direction: OcrReadingDirection,
    val normalizedLineTolerance: Float,
    val calibrationReference: SafeSummary,
) {
    init {
        require(normalizedLineTolerance.isFinite() && normalizedLineTolerance in 0f..1f)
        require(calibrationReference.value.isNotBlank())
    }
}

data class OcrReadingOrderEntry(
    val region: ConsensusTextRegion,
    val readingOrderIndex: Int,
) {
    init { require(readingOrderIndex >= 0) }
}

class OcrReadingOrderAssembler {
    fun order(regions: List<ConsensusTextRegion>, policy: OcrLayoutPolicy): List<OcrReadingOrderEntry> {
        val comparator = when (policy.direction) {
            OcrReadingDirection.LEFT_TO_RIGHT -> compareBy<ConsensusTextRegion>(
                { quantize(it.referenceGeometry.bounds.top, policy.normalizedLineTolerance) },
                { it.referenceGeometry.bounds.left },
            )
            OcrReadingDirection.RIGHT_TO_LEFT -> compareBy<ConsensusTextRegion>(
                { quantize(it.referenceGeometry.bounds.top, policy.normalizedLineTolerance) },
                { -it.referenceGeometry.bounds.right },
            )
            OcrReadingDirection.VERTICAL -> compareBy<ConsensusTextRegion>(
                { -quantize(it.referenceGeometry.bounds.right, policy.normalizedLineTolerance) },
                { it.referenceGeometry.bounds.top },
            )
        }
        return regions.sortedWith(comparator).mapIndexed { index, region -> OcrReadingOrderEntry(region, index) }
    }

    private fun quantize(value: Float, tolerance: Float): Int =
        if (tolerance == 0f) value.toBits() else (value / tolerance).toInt()
}
