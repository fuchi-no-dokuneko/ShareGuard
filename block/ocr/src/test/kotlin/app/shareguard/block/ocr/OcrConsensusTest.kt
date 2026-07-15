package app.shareguard.block.ocr

import app.shareguard.core.model.NormalizedRect
import app.shareguard.core.model.SafeSummary
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OcrConsensusTest {
    private val consensus = ConservativeOcrConsensus()
    private val policy = OcrConsensusPolicy(2, 0.5f, 0.05f, 0.5f, SafeSummary("ocr-consensus-corpus-v1"))

    @Test
    fun `two independent matching views produce accepted text without treating OCR as infallible`() {
        val outputs = listOf(
            output("ocr-engine", "view-1", "Canonical"),
            output("ocr-engine", "view-2", "Canonical"),
        )

        val report = consensus.reconcile(outputs, 2, policy)

        assertThat(report.regions).hasSize(1)
        assertThat(report.regions.single().accepted).isTrue()
        assertThat(report.regions.single().text).isEqualTo("Canonical")
        assertThat(report.completedObservationSources).isEqualTo(2)
        assertThat(report.toString()).doesNotContain("Canonical")
    }

    @Test
    fun `text script geometry and confidence conflicts all remain mandatory review`() {
        val outputs = listOf(
            output("engine-a", "view-a", "Latin", confidence = 0.9f),
            output(
                "engine-b",
                "view-b",
                "Lаtin",
                script = OcrScript.CHINESE,
                bounds = NormalizedRect(0.3f, 0.1f, 0.9f, 0.3f),
                confidence = 0.2f,
            ),
        )

        val report = consensus.reconcile(outputs, 2, policy.copy(minimumGeometryIntersectionOverUnion = 0.2f))
        val region = report.regions.single()

        assertThat(region.accepted).isFalse()
        assertThat(region.text).isNull()
        assertThat(region.reviewReasons).containsAtLeast(
            OcrReviewReason.ENGINE_DISAGREEMENT,
            OcrReviewReason.SCRIPT_CONFLICT,
            OcrReviewReason.MISSING_ENGINE_OBSERVATION,
        )
    }

    @Test
    fun `overlapping semantic regions cannot silently define reading order`() {
        val first = consensus.reconcile(
            listOf(output("engine", "v1", "First"), output("engine", "v2", "First")),
            2,
            policy,
        ).regions.single()
        val second = consensus.reconcile(
            listOf(
                output("engine", "v3", "Second", bounds = NormalizedRect(0.2f, 0.2f, 0.8f, 0.4f)),
                output("engine", "v4", "Second", bounds = NormalizedRect(0.2f, 0.2f, 0.8f, 0.4f)),
            ),
            2,
            policy,
        ).regions.single()

        val combined = consensus.reconcile(
            listOf(
                output("engine", "v1", "First"),
                output("engine", "v2", "First"),
                output("engine", "v3", "Second", bounds = NormalizedRect(0.2f, 0.2f, 0.8f, 0.4f)),
                output("engine", "v4", "Second", bounds = NormalizedRect(0.2f, 0.2f, 0.8f, 0.4f)),
            ),
            4,
            policy,
        )
        assertThat(combined.regions.all { OcrReviewReason.OVERLAPPING_READING_ORDER in it.reviewReasons }).isTrue()

        val ordered = OcrReadingOrderAssembler().order(
            listOf(first, second),
            OcrLayoutPolicy(OcrReadingDirection.LEFT_TO_RIGHT, 0.05f, SafeSummary("layout-corpus-v1")),
        )
        assertThat(ordered.map { it.readingOrderIndex }).containsExactly(0, 1).inOrder()
    }

    private fun output(
        engine: String,
        view: String,
        text: String,
        script: OcrScript = OcrScript.LATIN,
        bounds: NormalizedRect = NormalizedRect(0.1f, 0.1f, 0.7f, 0.3f),
        confidence: Float? = 0.9f,
    ): OcrEngineOutput {
        val engineId = SafeSummary(engine)
        val viewId = SafeSummary(view)
        val observation = OcrTextObservation(
            text,
            OcrGeometry(bounds, null, null, 0f, false),
            script,
            engineId,
            viewId,
            confidence,
        )
        return OcrEngineOutput(listOf(observation), script, engineId, viewId)
    }
}
