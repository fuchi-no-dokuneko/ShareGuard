package app.shareguard.block.render

import android.graphics.Bitmap
import android.graphics.Color
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.DecisionId
import app.shareguard.core.model.DependencyId
import app.shareguard.core.model.DependencyOrigin
import app.shareguard.core.model.DependencyType
import app.shareguard.core.model.ImageRegionId
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SourceDependency
import app.shareguard.core.model.SourceDependencyMap
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DerivativeImageRendererTest {
    private val renderer = DerivativeImageRenderer(dispatcher = Dispatchers.Unconfined)

    @Test
    fun `derivative requires current one-export warning acknowledgement and source lineage`() {
        val source = sourceBitmap()
        val missingAcknowledgement = request(source).copy(
            acknowledgement = DerivativeAcknowledgement("warning-v1", false),
        )

        val warningFailure = assertThrows(RenderException::class.java) {
            runBlocking { renderer.render(missingAcknowledgement) }
        }
        assertThat(warningFailure.code).isEqualTo(RenderFailureCode.DERIVATIVE_WARNING_NOT_ACKNOWLEDGED)

        val dependencyFailure = assertThrows(RenderException::class.java) {
            runBlocking {
                renderer.render(
                    request(source).copy(sourceDependencyMap = SourceDependencyMap.create(REVISION)),
                )
            }
        }
        assertThat(dependencyFailure.code).isEqualTo(RenderFailureCode.DERIVATIVE_DEPENDENCY_MISSING)
        source.recycle()
    }

    @Test
    fun `fresh resample canonical channels and serialization do not mutate source`() = runBlocking {
        val source = sourceBitmap()
        val before = source.getPixel(0, 0)

        val rendered = renderer.render(request(source))

        assertThat(source.getPixel(0, 0)).isEqualTo(before)
        assertThat(rendered.pixelSize.width.value).isEqualTo(8)
        assertThat(rendered.pixelSize.height.value).isEqualTo(6)
        assertThat(rendered.operations.map { it.code }).containsAtLeast(
            RenderOperationCode.DERIVATIVE_WARNING_ACKNOWLEDGED,
            RenderOperationCode.DERIVATIVE_RESAMPLED,
            RenderOperationCode.DERIVATIVE_CHANNELS_CANONICALIZED,
            RenderOperationCode.PNG_REOPENED,
        )
        assertThat(rendered.sourceDependencyMap.retainsSourcePixels).isTrue()
        source.recycle()
    }

    @Test
    fun `quantization cannot run without accepted benchmark evidence`() {
        val source = sourceBitmap()
        val rejectedPolicy = policy().copy(
            quantizationBitsPerChannel = 6,
            quantizationApproval = QuantizationBenchmarkApproval("quant-v1", "corpus-v1", false, true),
        )

        val failure = assertThrows(RenderException::class.java) {
            runBlocking { renderer.render(request(source).copy(policy = rejectedPolicy)) }
        }

        assertThat(failure.code).isEqualTo(RenderFailureCode.DERIVATIVE_POLICY_NOT_BENCHMARKED)
        source.recycle()
    }

    @Test
    fun `optional perturbation is off by default and has no stable output pattern`() = runBlocking {
        val source = sourceBitmap()
        val stochastic = policy().copy(stochasticPerturbationAmplitude = 2)

        val first = renderer.render(request(source).copy(policy = stochastic))
        val second = renderer.render(request(source).copy(policy = stochastic))

        assertThat(first.operations.map { it.code }).contains(RenderOperationCode.EPHEMERAL_PERTURBATION_APPLIED)
        assertThat(first.copyBytes()).isNotEqualTo(second.copyBytes())
        assertThat(renderer.render(request(source)).operations.map { it.code })
            .doesNotContain(RenderOperationCode.EPHEMERAL_PERTURBATION_APPLIED)
        source.recycle()
    }

    private fun request(source: Bitmap): DerivativeRenderRequest = DerivativeRenderRequest(
        source,
        REVISION,
        dependencyMap(),
        policy(),
        DerivativeAcknowledgement("warning-v1", true),
    )

    private fun policy(): DerivativePolicy = DerivativePolicy(
        DerivativeResourcePlan(8, 6, 1_000),
        warningVersion = "warning-v1",
    )

    private fun dependencyMap(): SourceDependencyMap = SourceDependencyMap.create(
        REVISION,
        listOf(
            SourceDependency(
                DependencyId("derivative-source"),
                DependencyType.RETAINED_SOURCE_PIXELS,
                DependencyOrigin.SOURCE,
                REVISION,
                imageRegionId = ImageRegionId("whole-source"),
                decisionId = DecisionId("decision-derivative"),
                sourcePixelRetained = true,
                reason = SafeSummary("Derivative remains statistically related to source pixels"),
            ),
        ),
    )

    private fun sourceBitmap(): Bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until height) for (x in 0 until width) {
            setPixel(x, y, Color.rgb(x * 50 + 20, y * 70 + 10, (x + y) * 30 + 5))
        }
    }

    private companion object {
        val REVISION = CanonicalRevision(1)
    }
}
