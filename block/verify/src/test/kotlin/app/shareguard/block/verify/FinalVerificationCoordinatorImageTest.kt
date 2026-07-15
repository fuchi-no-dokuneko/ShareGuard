package app.shareguard.block.verify

import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.ImageRegion
import app.shareguard.core.model.ImageRegionId
import app.shareguard.core.model.ImageRegionPolicy
import app.shareguard.core.model.ImageRegionType
import app.shareguard.core.model.MimeType
import app.shareguard.core.model.NormalizedRect
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.VerificationStatus
import app.shareguard.core.model.VerificationType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class FinalVerificationCoordinatorImageTest {
    private val coordinator = FinalVerificationCoordinator()

    @Test
    fun `fully injected rebuilt image evidence reaches AS4 without detector claims`() = runBlocking {
        val fixture = VerificationFixtures.image()

        val outcome = coordinator.verify(fixture.request, fixture.providers)

        assertThat(outcome.report.assuranceClass).isEqualTo(AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE)
        assertThat(outcome.canPersistVerifiedResult).isTrue()
        assertThat(outcome.result(VerificationType.FINAL_METADATA).status).isEqualTo(VerificationStatus.PASS)
        assertThat(outcome.result(VerificationType.OCR_ROUND_TRIP).status).isEqualTo(VerificationStatus.PASS)
        assertThat(outcome.result(VerificationType.MACHINE_READABLE_CODE).status).isEqualTo(VerificationStatus.PASS)
        assertThat(outcome.result(VerificationType.VISUAL_REGION_COVERAGE).status).isEqualTo(VerificationStatus.PASS)
        assertThat(outcome.result(VerificationType.SOURCE_PIXEL_DEPENDENCY).status)
            .isEqualTo(VerificationStatus.PASS_WITH_DECLARED_RESIDUAL)
        assertThat(outcome.report.sourcePixelRegionList).isEmpty()
    }

    @Test
    fun `absent image OCR barcode and region adapters remain NOT_RUN and block`() = runBlocking {
        val fixture = VerificationFixtures.image()
        val providers = fixture.providers.copy(
            finalImageInspector = null,
            ocrRoundTripInspector = null,
            barcodeInspector = null,
            regionCoverageInspector = null,
        )

        val outcome = coordinator.verify(fixture.request, providers)

        assertThat(outcome.result(VerificationType.FINAL_METADATA).status).isEqualTo(VerificationStatus.NOT_RUN)
        assertThat(outcome.result(VerificationType.OCR_ROUND_TRIP).status).isEqualTo(VerificationStatus.NOT_RUN)
        assertThat(outcome.result(VerificationType.MACHINE_READABLE_CODE).status).isEqualTo(VerificationStatus.NOT_RUN)
        assertThat(outcome.result(VerificationType.VISUAL_REGION_COVERAGE).status).isEqualTo(VerificationStatus.NOT_RUN)
        assertThat(outcome.result(VerificationType.SOURCE_PIXEL_DEPENDENCY).status).isEqualTo(VerificationStatus.NOT_RUN)
        assertThat(outcome.report.assuranceClass).isEqualTo(AssuranceClass.AS_0_UNVERIFIED)
        assertThat(outcome.canManagedShare).isFalse()
    }

    @Test
    fun `container metadata chunk thumbnail channel alpha profile and decoder policy are all enforced`() = runBlocking {
        val fixture = VerificationFixtures.image()
        val providers = fixture.providers.copy(
            finalImageInspector = FinalImageInspector { artifact, _ ->
                ProviderResult.Completed(
                    FinalImageInspection(
                        artifactRevision = artifact.artifactRevision,
                        detectedMimeType = MimeType("image/jpeg"),
                        independentlyDecodes = false,
                        metadataFieldCodes = setOf("EXIF_SOURCE_NAME"),
                        containerChunkCodes = setOf("PNG_IHDR", "PNG_TEXT"),
                        embeddedThumbnailCount = 1,
                        channelModelCode = "RGBA_16",
                        alphaModelCode = "SOURCE_ALPHA",
                        colourProfileCode = "SOURCE_ICC",
                        freshlyAllocatedCanvas = true,
                        bundledRendererAssetsOnly = true,
                    ),
                )
            },
        )

        val outcome = coordinator.verify(fixture.request, providers)

        val metadata = outcome.result(VerificationType.FINAL_METADATA)
        assertThat(metadata.status).isEqualTo(VerificationStatus.FAIL)
        assertThat(metadata.failures.map { it.code }).containsAtLeast(
            "IMAGE_MIME_NOT_ALLOWED",
            "INDEPENDENT_IMAGE_DECODE_FAILED",
            "UNEXPECTED_IMAGE_METADATA",
            "UNEXPECTED_CONTAINER_CHUNK",
            "EMBEDDED_THUMBNAIL_NOT_ALLOWED",
            "CHANNEL_MODEL_NOT_ALLOWED",
            "ALPHA_MODEL_NOT_ALLOWED",
            "COLOUR_PROFILE_NOT_ALLOWED",
        )
        assertThat(outcome.report.finalMetadataInventory.map { it.fieldName })
            .containsAtLeast("EXIF_SOURCE_NAME", "PNG_TEXT")
        Unit
    }

    @Test
    fun `OCR divergence is classified and blocks render round trip`() = runBlocking {
        val fixture = VerificationFixtures.image()
        val providers = fixture.providers.copy(
            ocrRoundTripInspector = OcrRoundTripInspector { artifact, _, _ ->
                ProviderResult.Completed(
                    OcrRoundTripInspection.create(
                        artifact.artifactRevision,
                        "He11o world",
                        listOf(VerificationFixtures.blockId),
                        listOf("OCR_IDENTIFIER_AMBIGUITY"),
                    ),
                )
            },
        )

        val outcome = coordinator.verify(fixture.request, providers)

        val ocr = outcome.result(VerificationType.OCR_ROUND_TRIP)
        assertThat(ocr.status).isEqualTo(VerificationStatus.FAIL)
        assertThat(ocr.failures.map { it.code })
            .containsAtLeast("OCR_TEXT_DIVERGENCE", "OCR_CLASSIFIED_DIFFERENCE_PRESENT")
        assertThat(outcome.report.ocrRoundTripFindings.single().summary.value)
            .isEqualTo("OCR_IDENTIFIER_AMBIGUITY")
        assertThat(outcome.humanReadableReport.asPlainText()).doesNotContain("He11o world")
    }

    @Test
    fun `unexpected barcode value fails without copying decoded value into report`() = runBlocking {
        val fixture = VerificationFixtures.image()
        val secret = "https://tracking.invalid/private?id=secret"
        val providers = fixture.providers.copy(
            barcodeInspector = BarcodeInspector { artifact ->
                ProviderResult.Completed(
                    BarcodeInspection.create(
                        artifact.artifactRevision,
                        listOf(MachineReadableCode("QR_CODE", secret)),
                    ),
                )
            },
        )

        val outcome = coordinator.verify(fixture.request, providers)

        assertThat(outcome.result(VerificationType.MACHINE_READABLE_CODE).status)
            .isEqualTo(VerificationStatus.FAIL)
        assertThat(outcome.humanReadableReport.asPlainText()).doesNotContain(secret)
        assertThat(outcome.persistableSummary.toString()).doesNotContain(secret)
    }

    @Test
    fun `region coverage requires every canonical region terminal policy`() = runBlocking {
        val region = ImageRegion(
            regionId = ImageRegionId("region-1"),
            regionType = ImageRegionType.AVATAR,
            sourceBounds = NormalizedRect(0f, 0f, 0.5f, 0.5f),
            canonicalBounds = null,
            policy = ImageRegionPolicy.REMOVE,
            sourcePixelRetained = false,
            replacementAssetId = null,
            userApproved = true,
            dependencyReason = SafeSummary("USER_APPROVED_REMOVAL"),
        )
        val fixture = VerificationFixtures.image(imageRegions = listOf(region))
        val providers = fixture.providers.copy(
            regionCoverageInspector = RegionCoverageInspector { artifact ->
                ProviderResult.Completed(RegionCoverageInspection(artifact.artifactRevision, emptyMap(), emptySet()))
            },
        )

        val outcome = coordinator.verify(fixture.request, providers)

        assertThat(outcome.result(VerificationType.VISUAL_REGION_COVERAGE).status)
            .isEqualTo(VerificationStatus.FAIL)
        assertThat(outcome.result(VerificationType.VISUAL_REGION_COVERAGE).failures.map { it.code })
            .contains("REGION_TERMINAL_POLICY_MISMATCH")
        assertThat(outcome.reviewAudits.single { it.type == ReviewAuditType.IMAGE_REGION_POLICY }.status)
            .isEqualTo(VerificationStatus.PASS)
    }

    @Test
    fun `dependency expected scope and explicit platform limitation are mandatory`() = runBlocking {
        val fixture = VerificationFixtures.image()
        val missingExpected = fixture.request.copy(
            dependencyScope = fixture.request.dependencyScope.copy(expectedEntries = emptySet()),
        )
        val missingOutcome = coordinator.verify(missingExpected, fixture.providers)
        assertThat(missingOutcome.result(VerificationType.SOURCE_PIXEL_DEPENDENCY).failures.map { it.code })
            .contains("DEPENDENCY_EXPECTATION_MISMATCH")

        val noLimitation = fixture.request.copy(
            dependencyScope = fixture.request.dependencyScope.copy(platformLimitationCodes = emptySet()),
        )
        val limitationOutcome = coordinator.verify(noLimitation, fixture.providers)
        assertThat(limitationOutcome.result(VerificationType.SOURCE_PIXEL_DEPENDENCY).failures.map { it.code })
            .contains("PLATFORM_DEPENDENCY_LIMITATION_UNDECLARED")
    }

    private fun FinalVerificationOutcome.result(type: VerificationType) =
        if (type == VerificationType.SOURCE_REFERENCE) report.sourceReferenceAudit
        else report.results.single { it.type == type }
}
