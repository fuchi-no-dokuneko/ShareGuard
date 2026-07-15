package app.shareguard.block.render

import android.graphics.Bitmap
import android.graphics.Color
import app.shareguard.core.model.ArtifactReference
import app.shareguard.core.model.CanonicalBlock
import app.shareguard.core.model.CanonicalBlockId
import app.shareguard.core.model.CanonicalDocument
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.ChangeLedgerId
import app.shareguard.core.model.ConfidenceClass
import app.shareguard.core.model.DecisionAction
import app.shareguard.core.model.DecisionId
import app.shareguard.core.model.DecisionStatus
import app.shareguard.core.model.DependencyId
import app.shareguard.core.model.DependencyOrigin
import app.shareguard.core.model.DependencyType
import app.shareguard.core.model.FindingId
import app.shareguard.core.model.ImagePlaceholderBlock
import app.shareguard.core.model.ImageRegion
import app.shareguard.core.model.ImageRegionId
import app.shareguard.core.model.ImageRegionPolicy
import app.shareguard.core.model.ImageRegionType
import app.shareguard.core.model.LanguagePolicy
import app.shareguard.core.model.LayoutElement
import app.shareguard.core.model.LayoutKind
import app.shareguard.core.model.LayoutModel
import app.shareguard.core.model.MimeType
import app.shareguard.core.model.NormalizedRect
import app.shareguard.core.model.ParagraphBlock
import app.shareguard.core.model.ReadingOrder
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SchemaVersion
import app.shareguard.core.model.ScriptCode
import app.shareguard.core.model.SemanticImpact
import app.shareguard.core.model.SemanticRole
import app.shareguard.core.model.SourceDependency
import app.shareguard.core.model.SourceDependencyMap
import app.shareguard.core.model.TextRun
import app.shareguard.core.model.UnknownRegionBlock
import app.shareguard.core.model.UserDecision
import app.shareguard.core.model.immutableListOf
import app.shareguard.core.model.toImmutableList
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CanonicalImageRendererTest {
    private val renderer by lazy {
        CanonicalImageRenderer(
            defaultBundledFontRegistry(RuntimeEnvironment.getApplication()),
            Dispatchers.Unconfined,
            GlyphCoverageChecker { _, _ -> true },
        )
    }

    @Test
    fun `same canonical document deterministically renders fresh strict bytes`() = runBlocking {
        val document = paragraphDocument("Canonical text")

        val first = renderer.render(CanonicalRenderRequest(document, policy()))
        val second = renderer.render(CanonicalRenderRequest(document, policy()))

        assertThat(first.copyBytes()).isEqualTo(second.copyBytes())
        assertThat(first.operations.map { it.code }).containsAtLeast(
            RenderOperationCode.FRESH_CANVAS_ALLOCATED,
            RenderOperationCode.BUNDLED_FONT_RESOLVED,
            RenderOperationCode.TEXT_SHAPED,
            RenderOperationCode.ALPHA_FLATTENED,
            RenderOperationCode.PNG_REOPENED,
        )
        assertThat(first.sourceDependencyMap.retainsSourcePixels).isFalse()
        assertThat(first.sourceDependencyMap.entries.map { it.type }).containsAtLeast(
            DependencyType.BUNDLED_ASSET,
            DependencyType.RENDERER_GENERATED_PRIMITIVE,
            DependencyType.CANONICAL_DOCUMENT_REVISION,
        )
        val callerCopy = first.copyBytes()
        callerCopy.fill(0)
        assertThat(first.copyBytes()).isNotEqualTo(callerCopy)
    }

    @Test
    fun `unsupported script and invisible control never fall back to device font`() = runBlocking {
        val arabic = paragraphDocument("ሀ", script = ScriptCode.OTHER)
        val missing = assertThrows(RenderException::class.java) {
            runBlocking { renderer.render(CanonicalRenderRequest(arabic, policy())) }
        }
        assertThat(missing.code).isEqualTo(RenderFailureCode.MISSING_BUNDLED_FONT)

        val invisible = paragraphDocument("a\u200Bb")
        val control = assertThrows(RenderException::class.java) {
            runBlocking { renderer.render(CanonicalRenderRequest(invisible, policy())) }
        }
        assertThat(control.code).isEqualTo(RenderFailureCode.INVALID_CANONICAL_CONTROL)
    }

    @Test
    fun `retained source region requires exact approval transform bounds and dependency`() = runBlocking {
        val fixture = retainedRegionDocument(includeDependency = true)
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
            setHasAlpha(false)
        }
        val provider = ApprovedSourceRegionProvider {
            ApprovedSourceRegionPixels(
                regionId = fixture.region.regionId,
                decisionId = fixture.decision.decisionId,
                sourceBounds = fixture.region.sourceBounds,
                declaredDerivativePolicyId = "region-resample-v1",
                transformedUnderDeclaredDerivativePolicy = true,
                bitmap = source,
            )
        }

        val rendered = renderer.render(CanonicalRenderRequest(fixture.document, policy(), provider))

        assertThat(rendered.operations.map { it.code })
            .contains(RenderOperationCode.APPROVED_SOURCE_REGION_IMPORTED)
        assertThat(rendered.sourceDependencyMap.retainedRegionIds).contains(fixture.region.regionId)
        source.recycle()

        val missingDependency = retainedRegionDocument(includeDependency = false)
        val rejected = assertThrows(RenderException::class.java) {
            runBlocking {
                renderer.render(
                    CanonicalRenderRequest(
                        missingDependency.document,
                        policy(),
                        ApprovedSourceRegionProvider {
                            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).let { bitmap ->
                                bitmap.eraseColor(Color.BLACK)
                                ApprovedSourceRegionPixels(
                                    missingDependency.region.regionId,
                                    missingDependency.decision.decisionId,
                                    missingDependency.region.sourceBounds,
                                    "region-resample-v1",
                                    true,
                                    bitmap,
                                )
                            }
                        },
                    ),
                )
            }
        }
        assertThat(rejected.code).isEqualTo(RenderFailureCode.SOURCE_REGION_DEPENDENCY_MISSING)
    }

    @Test
    fun `unknown region can only use explicit placeholder or redaction policy`() = runBlocking {
        val regionId = ImageRegionId("region-placeholder")
        val region = ImageRegion(
            regionId,
            ImageRegionType.UNKNOWN,
            NormalizedRect(0f, 0f, 1f, 1f),
            null,
            ImageRegionPolicy.REPLACE_WITH_PLACEHOLDER,
            false,
            ArtifactReference("bundled-placeholder"),
            false,
            SafeSummary("Generic placeholder selected"),
        )
        val block = ImagePlaceholderBlock(CanonicalBlockId("block-placeholder"), regionId, "Image region")
        val rendered = renderer.render(CanonicalRenderRequest(document(listOf(block), listOf(region)), policy()))

        assertThat(rendered.operations.map { it.code }).contains(RenderOperationCode.PLACEHOLDER_RENDERED)
        assertThat(rendered.sourceDependencyMap.retainsSourcePixels).isFalse()
    }

    @Test
    fun `document resource ceiling fails before canvas allocation`() = runBlocking {
        val constrained = policy().copy(
            resourcePlan = policy().resourcePlan.copy(maximumTextScalars = 3),
        )

        val failure = assertThrows(RenderException::class.java) {
            runBlocking { renderer.render(CanonicalRenderRequest(paragraphDocument("long"), constrained)) }
        }

        assertThat(failure.code).isEqualTo(RenderFailureCode.INVALID_RESOURCE_PLAN)
    }

    private fun paragraphDocument(text: String, script: ScriptCode = ScriptCode.LATIN): CanonicalDocument {
        val block = ParagraphBlock(
            CanonicalBlockId("block-paragraph"),
            immutableListOf(
                TextRun.create(
                    canonicalText = text,
                    scriptSet = listOf(script),
                    semanticRole = SemanticRole.BODY,
                    confidenceClass = ConfidenceClass.CERTAIN_BY_PARSER,
                ),
            ),
        )
        return document(listOf(block))
    }

    private data class RetainedFixture(
        val document: CanonicalDocument,
        val region: ImageRegion,
        val decision: UserDecision,
    )

    private fun retainedRegionDocument(includeDependency: Boolean): RetainedFixture {
        val regionId = ImageRegionId("region-retained")
        val decisionId = DecisionId("decision-retained")
        val block = UnknownRegionBlock(CanonicalBlockId("block-region"), regionId, SafeSummary("Reviewed region"))
        val region = ImageRegion(
            regionId,
            ImageRegionType.PHOTOGRAPH,
            NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f),
            NormalizedRect(0f, 0f, 1f, 1f),
            ImageRegionPolicy.RETAIN_SOURCE_PIXELS,
            true,
            null,
            true,
            SafeSummary("Lower assurance accepted"),
        )
        val decision = UserDecision.create(
            decisionId,
            listOf(FindingId("finding-retained")),
            DecisionAction.ACCEPT_LOWER_ASSURANCE,
            DecisionStatus.APPROVED,
            SemanticImpact.NONE,
            SafeSummary("Retain this source region"),
            REVISION,
        )
        val dependencies = if (includeDependency) {
            listOf(
                SourceDependency(
                    DependencyId("dependency-retained"),
                    DependencyType.RETAINED_SOURCE_PIXELS,
                    DependencyOrigin.SOURCE,
                    REVISION,
                    block.id,
                    regionId,
                    decisionId,
                    true,
                    SafeSummary("Exact approved source region"),
                ),
            )
        } else {
            emptyList()
        }
        return RetainedFixture(document(listOf(block), listOf(region), listOf(decision), dependencies), region, decision)
    }

    private fun document(
        blocks: List<CanonicalBlock>,
        regions: List<ImageRegion> = emptyList(),
        decisions: List<UserDecision> = emptyList(),
        dependencies: List<SourceDependency> = emptyList(),
    ): CanonicalDocument = CanonicalDocument(
        schemaVersion = SchemaVersion(1),
        revision = REVISION,
        declaredLanguagePolicy = LanguagePolicy.create(allowedScripts = listOf(ScriptCode.LATIN)),
        rootBlocks = blocks.toImmutableList(),
        urlTokens = immutableListOf(),
        imageRegions = regions.toImmutableList(),
        layoutModel = LayoutModel(
            LayoutKind.PLAIN_DOCUMENT,
            blocks.mapIndexed { index, block -> LayoutElement(block.id, null, index) }.toImmutableList(),
        ),
        readingOrder = ReadingOrder(blocks.map { it.id }.toImmutableList()),
        userDecisions = decisions.toImmutableList(),
        sourceDependencyMap = SourceDependencyMap.create(REVISION, dependencies),
        changeLedgerReference = ChangeLedgerId("ledger-render"),
    )

    private fun policy(): CanonicalRenderPolicy = CanonicalRenderPolicy(
        CanonicalRenderTheme.DOCUMENT,
        RenderResourcePlan(320, 1_024, 1_000_000, 16, 16, 8, 18f),
        Color.WHITE,
        Color.BLACK,
        Color.rgb(230, 230, 230),
        Color.BLACK,
        MimeType("image/png"),
    )

    private companion object {
        val REVISION = CanonicalRevision(1)
    }
}
