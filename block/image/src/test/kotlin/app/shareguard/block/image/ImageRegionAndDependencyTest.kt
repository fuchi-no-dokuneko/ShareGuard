package app.shareguard.block.image

import app.shareguard.core.model.ArtifactReference
import app.shareguard.core.model.CanonicalBlockId
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.DecisionId
import app.shareguard.core.model.DependencyType
import app.shareguard.core.model.ImageRegionId
import app.shareguard.core.model.ImageRegionPolicy
import app.shareguard.core.model.ImageRegionType
import app.shareguard.core.model.NormalizedRect
import app.shareguard.core.model.SafeSummary
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageRegionAndDependencyTest {
    private val policy = ConservativeImageRegionPolicy()

    @Test
    fun `unknown defaults to opaque redaction and can never directly retain source pixels`() {
        val default = policy.decide(request(DetectedRegionClass.UNKNOWN))
        assertThat(default.region.policy).isEqualTo(ImageRegionPolicy.SOLID_REDACT)
        assertThat(default.region.sourcePixelRetained).isFalse()
        assertThat(default.reviewRequired).isTrue()

        assertThrows(IllegalArgumentException::class.java) {
            policy.decide(request(DetectedRegionClass.UNKNOWN, ImageRegionPolicy.RETAIN_SOURCE_PIXELS, DECISION))
        }
    }

    @Test
    fun `non-text region requires explicit terminal disposition and retained pixels require decision`() {
        assertThrows(IllegalArgumentException::class.java) {
            policy.decide(request(DetectedRegionClass.NON_TEXT))
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.decide(request(DetectedRegionClass.NON_TEXT, ImageRegionPolicy.RETAIN_SOURCE_PIXELS))
        }

        val retained = policy.decide(
            request(DetectedRegionClass.NON_TEXT, ImageRegionPolicy.RETAIN_SOURCE_PIXELS, DECISION),
        )
        assertThat(retained.region.sourcePixelRetained).isTrue()
        assertThat(retained.region.userApproved).isTrue()
        assertThat(retained.approvalDecisionId).isEqualTo(DECISION)
    }

    @Test
    fun `structured rebuild remains review-required until semantic data approval exists`() {
        val pending = policy.decide(request(DetectedRegionClass.TEXT))
        assertThat(pending.region.policy).isEqualTo(ImageRegionPolicy.REBUILD_FROM_STRUCTURED_DATA)
        assertThat(pending.region.userApproved).isFalse()
        assertThat(pending.reviewRequired).isTrue()

        val approved = policy.decide(
            request(DetectedRegionClass.TEXT, ImageRegionPolicy.REBUILD_FROM_STRUCTURED_DATA, DECISION),
        )
        assertThat(approved.region.userApproved).isTrue()
        assertThat(approved.reviewRequired).isFalse()
    }

    @Test
    fun `dependency mapper emits every declared app dependency and explicit platform limitation`() {
        val block = CanonicalBlockId("block-image")
        val region = ImageRegionId("region-image")
        val declaration = ImageDependencyDeclaration(
            canonicalRevision = REVISION,
            retainedPixelRegions = listOf(RegionRetentionUse(region, DECISION)),
            ocrDerivedText = listOf(BlockDependencyUse(block)),
            sourceDerivedLayout = listOf(BlockDependencyUse(block)),
            retainedMetadataBlocks = listOf(BlockDependencyUse(block)),
            bundledAssets = listOf(BundledAssetUse(ArtifactReference("placeholder-asset"), block)),
            rendererPrimitives = listOf(RendererPrimitiveUse(SafeSummary("generic-card-v1"), block)),
            userDecisions = listOf(DECISION),
        )

        val coverage = ImageSourceDependencyMapper().build(declaration)

        assertThat(coverage.dependencyMap.entries.map { it.type }).containsExactly(
            DependencyType.RETAINED_SOURCE_PIXELS,
            DependencyType.OCR_DERIVED_TEXT,
            DependencyType.SOURCE_DERIVED_LAYOUT,
            DependencyType.RETAINED_SOURCE_METADATA,
            DependencyType.BUNDLED_ASSET,
            DependencyType.RENDERER_GENERATED_PRIMITIVE,
            DependencyType.USER_DECISION,
            DependencyType.CANONICAL_DOCUMENT_REVISION,
        ).inOrder()
        assertThat(coverage.dependencyMap.retainedRegionIds).containsExactly(region)
        assertThat(coverage.bundledAssetByDependency.values).containsExactly(ArtifactReference("placeholder-asset"))
        assertThat(coverage.scopeLimitation.value).contains("does not claim")
    }

    @Test
    fun `generic rebuild suggestion never contains source pixels or invents a cutoff`() {
        val unavailable = ConservativeImageRebuildSuggester().suggest(false, 0.99f, GenericRebuildKind.GENERIC_CARD)
        assertThat(unavailable.kind).isEqualTo(GenericRebuildKind.PLAIN_TEXT_FLOW)
        assertThat(unavailable.reviewRequired).isTrue()
        assertThat(unavailable.usesSourcePixels).isFalse()

        val supported = ConservativeImageRebuildSuggester().suggest(true, 0.01f, GenericRebuildKind.GENERIC_CARD)
        assertThat(supported.kind).isEqualTo(GenericRebuildKind.GENERIC_CARD)
        assertThat(supported.reviewRequired).isFalse()
    }

    private fun request(
        detectedClass: DetectedRegionClass,
        requestedPolicy: ImageRegionPolicy? = null,
        decisionId: DecisionId? = null,
    ) = RegionDispositionRequest(
        ImageRegionId("region-1"),
        detectedClass,
        ImageRegionType.UNKNOWN,
        NormalizedRect(0f, 0f, 1f, 1f),
        requestedPolicy,
        replacementAsset = if (requestedPolicy == ImageRegionPolicy.REPLACE_WITH_PLACEHOLDER) {
            ArtifactReference("placeholder")
        } else {
            null
        },
        approvalDecisionId = decisionId,
    )

    private companion object {
        val REVISION = CanonicalRevision(1)
        val DECISION = DecisionId("decision-image")
    }
}
