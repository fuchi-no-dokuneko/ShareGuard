package app.shareguard.block.ocr

import app.shareguard.core.model.NormalizedRect
import app.shareguard.core.model.SafeSummary

enum class OcrScript { LATIN, CHINESE, DEVANAGARI, JAPANESE, KOREAN }
enum class OcrReadingDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT, VERTICAL }
enum class OcrReviewReason {
    ENGINE_DISAGREEMENT,
    MISSING_ENGINE_OBSERVATION,
    SCRIPT_CONFLICT,
    GEOMETRY_CONFLICT,
    OVERLAPPING_READING_ORDER,
    LOW_CONFIDENCE,
    UNDECODABLE_REGION,
    CANCELLED_PARTIAL_RESULT,
}

data class OcrPoint(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) }
}

data class OcrGeometry(
    val bounds: NormalizedRect,
    val baselineStart: OcrPoint?,
    val baselineEnd: OcrPoint?,
    val orientationDegrees: Float,
    val geometryUncertain: Boolean,
) {
    init {
        require(orientationDegrees.isFinite() && orientationDegrees in -360f..360f)
        require((baselineStart == null) == (baselineEnd == null))
    }
}

/** Text is available to canonicalization, but deliberately redacted from logs and debugger-friendly toString. */
class OcrTextObservation(
    val text: String,
    val geometry: OcrGeometry,
    val script: OcrScript,
    val engineId: SafeSummary,
    val viewId: SafeSummary,
    val confidence: Float?,
) {
    init {
        require(text.isNotEmpty())
        require(engineId.value.isNotBlank() && viewId.value.isNotBlank())
        require(confidence == null || (confidence.isFinite() && confidence in 0f..1f))
    }

    override fun toString(): String =
        "OcrTextObservation(text=<redacted>,script=$script,engine=<redacted>,view=<redacted>,confidencePresent=${confidence != null})"
}

data class OcrEngineOutput(
    val observations: List<OcrTextObservation>,
    val script: OcrScript,
    val engineId: SafeSummary,
    val viewId: SafeSummary,
) {
    init {
        require(observations.all { it.script == script && it.engineId == engineId && it.viewId == viewId })
    }

    override fun toString(): String =
        "OcrEngineOutput(observationCount=${observations.size},script=$script,engine=<redacted>,view=<redacted>)"
}

class ConsensusTextRegion(
    val text: String?,
    val referenceGeometry: OcrGeometry,
    val scriptsObserved: Set<OcrScript>,
    val agreeingEngineIds: Set<SafeSummary>,
    val agreeingViewIds: Set<SafeSummary>,
    val reviewReasons: Set<OcrReviewReason>,
) {
    val accepted: Boolean get() = text != null && reviewReasons.isEmpty()

    init {
        require(scriptsObserved.isNotEmpty())
        require(agreeingEngineIds.isNotEmpty() && agreeingViewIds.isNotEmpty())
        if (text == null) require(reviewReasons.isNotEmpty())
    }

    override fun toString(): String =
        "ConsensusTextRegion(text=<redacted>,accepted=$accepted,scripts=$scriptsObserved,engineCount=${agreeingEngineIds.size},viewCount=${agreeingViewIds.size},reviewReasons=$reviewReasons)"
}

data class OcrConsensusReport(
    val regions: List<ConsensusTextRegion>,
    val expectedObservationSources: Int,
    val completedObservationSources: Int,
    val partialResultInvalidated: Boolean = false,
) {
    init {
        require(expectedObservationSources >= 0)
        require(completedObservationSources in 0..expectedObservationSources)
        if (partialResultInvalidated) require(regions.isEmpty())
    }
}
