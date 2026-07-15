package app.shareguard.core.pipeline

import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.BlockId
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.VerificationType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AssuranceAndInvalidationTest {
    @Test
    fun contradictionsAndFailedPreconditionsAlwaysProduceAs0() {
        val contradiction = DeterministicAssuranceClassifier.classify(
            evidence(
                allRequiredVerificationPassed = true,
                verificationFailurePresent = true,
            ),
        )
        assertThat(contradiction.assuranceClass).isEqualTo(AssuranceClass.AS_0_UNVERIFIED)
        assertThat(contradiction.contradictions).contains("VERIFICATION_STATUS_CONTRADICTION")

        val missingDependencyEvidence = DeterministicAssuranceClassifier.classify(
            evidence(sourceDependencyInformationComplete = false),
        )
        assertThat(missingDependencyEvidence.assuranceClass).isEqualTo(AssuranceClass.AS_0_UNVERIFIED)
        assertThat(missingDependencyEvidence.reasonCodes).contains("REQUIRED_ASSURANCE_PRECONDITION_FAILED")
    }

    @Test
    fun classifierDeterministicallyCoversAs1ThroughAs4AndAppliesCeiling() {
        val derivative = DeterministicAssuranceClassifier.classify(
            evidence(
                outputMode = OutputMode.DERIVATIVE_IMAGE,
                assuranceCeiling = AssuranceClass.AS_1_REENCODED_DERIVATIVE,
                finalMetadataPassed = true,
            ),
        )
        val text = DeterministicAssuranceClassifier.classify(
            evidence(
                outputMode = OutputMode.TEXT,
                assuranceCeiling = AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT,
                canonicalTextFromApprovedDocument = true,
                finalUnicodePassed = true,
                finalUrlPassed = true,
                unresolvedTextAmbiguitiesApproved = true,
            ),
        )
        val retainedRegions = DeterministicAssuranceClassifier.classify(
            evidence(
                retainedSourcePixels = true,
                retainedSourceRegionsDeclared = true,
                finalMetadataPassed = true,
                ocrRoundTripPassed = true,
            ),
        )
        val fullRebuild = DeterministicAssuranceClassifier.classify(
            evidence(
                freshlyRenderedTextAndUi = true,
                bundledRendererAssetsOnly = true,
                finalMetadataPassed = true,
                ocrRoundTripPassed = true,
                canonicalTextFromApprovedDocument = true,
                unresolvedTextAmbiguitiesApproved = true,
            ),
        )
        val bounded = DeterministicAssuranceClassifier.classify(
            evidence(
                assuranceCeiling = AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT,
                freshlyRenderedTextAndUi = true,
                bundledRendererAssetsOnly = true,
                finalMetadataPassed = true,
                ocrRoundTripPassed = true,
                canonicalTextFromApprovedDocument = true,
                unresolvedTextAmbiguitiesApproved = true,
            ),
        )

        assertThat(derivative.assuranceClass).isEqualTo(AssuranceClass.AS_1_REENCODED_DERIVATIVE)
        assertThat(text.assuranceClass).isEqualTo(AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT)
        assertThat(retainedRegions.assuranceClass).isEqualTo(AssuranceClass.AS_3_REBUILT_WITH_SOURCE_REGIONS)
        assertThat(fullRebuild.assuranceClass).isEqualTo(AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE)
        assertThat(bounded.assuranceClass).isEqualTo(AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT)
        assertThat(bounded.reasonCodes).contains("ASSURANCE_CEILING_APPLIED")
    }

    @Test
    fun derivativeOutputCannotContradictItsAs1Ceiling() {
        val result = DeterministicAssuranceClassifier.classify(
            evidence(
                outputMode = OutputMode.DERIVATIVE_IMAGE,
                assuranceCeiling = AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE,
                finalMetadataPassed = true,
            ),
        )

        assertThat(result.assuranceClass).isEqualTo(AssuranceClass.AS_0_UNVERIFIED)
        assertThat(result.contradictions).contains("DERIVATIVE_CEILING_CONTRADICTION")
    }

    @Test
    fun invalidationIsScopedAndAlwaysInvalidatesSignaturesForBlockVersions() {
        val planner = InvalidationPlanner()

        val display = planner.plan(ChangeSignal.DISPLAY_METADATA_CHANGED, OutputMode.BOTH)
        assertThat(display.invalidatedBlockIds).isEmpty()
        assertThat(display.invalidatedVerificationTypes).isEmpty()
        assertThat(display.resultingAssurance).isNull()

        val renderer = planner.plan(ChangeSignal.RENDERER_SETTINGS_CHANGED, OutputMode.REBUILT_IMAGE)
        assertThat(renderer.invalidateCanonicalDocument).isFalse()
        assertThat(renderer.invalidateTextArtifact).isFalse()
        assertThat(renderer.invalidateImageArtifact).isTrue()
        assertThat(renderer.invalidatedBlockIds).contains(BlockId("REN-001"))

        val filename = planner.plan(ChangeSignal.SAVE_FILENAME_CHANGED, OutputMode.TEXT)
        assertThat(filename.invalidateTextArtifact).isFalse()
        assertThat(filename.invalidatedVerificationTypes).containsExactly(VerificationType.SOURCE_REFERENCE)

        val migration = planner.plan(ChangeSignal.STORAGE_MIGRATED, OutputMode.TEXT)
        assertThat(migration.requireStoredArtifactRevalidation).isTrue()
        assertThat(migration.invalidatedVerificationTypes)
            .containsExactly(VerificationType.PERSISTENT_REOPEN_AND_DIGEST)

        val version = planner.plan(ChangeSignal.BLOCK_VERSION_CHANGED, OutputMode.BOTH)
        assertThat(version.invalidatePresetSignature).isTrue()
        assertThat(version.invalidatedBlockIds).containsExactlyElementsIn(NormativeBlockCatalog.descriptors.map { it.blockId })
    }

    private fun evidence(
        outputMode: OutputMode = OutputMode.REBUILT_IMAGE,
        assuranceCeiling: AssuranceClass = AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE,
        allRequiredVerificationPassed: Boolean = true,
        verificationFailurePresent: Boolean = false,
        externallyEdited: Boolean = false,
        sourceDependencyInformationComplete: Boolean = true,
        retainedSourcePixels: Boolean = false,
        retainedSourceRegionsDeclared: Boolean = false,
        canonicalTextFromApprovedDocument: Boolean = false,
        finalUnicodePassed: Boolean = false,
        finalUrlPassed: Boolean = false,
        unresolvedTextAmbiguitiesApproved: Boolean = false,
        freshlyRenderedTextAndUi: Boolean = false,
        bundledRendererAssetsOnly: Boolean = false,
        finalMetadataPassed: Boolean = false,
        ocrRoundTripPassed: Boolean = false,
    ) = AssuranceEvidence(
        outputMode = outputMode,
        assuranceCeiling = assuranceCeiling,
        allRequiredVerificationPassed = allRequiredVerificationPassed,
        verificationFailurePresent = verificationFailurePresent,
        externallyEdited = externallyEdited,
        sourceDependencyInformationComplete = sourceDependencyInformationComplete,
        retainedSourcePixels = retainedSourcePixels,
        retainedSourceRegionsDeclared = retainedSourceRegionsDeclared,
        canonicalTextFromApprovedDocument = canonicalTextFromApprovedDocument,
        finalUnicodePassed = finalUnicodePassed,
        finalUrlPassed = finalUrlPassed,
        unresolvedTextAmbiguitiesApproved = unresolvedTextAmbiguitiesApproved,
        freshlyRenderedTextAndUi = freshlyRenderedTextAndUi,
        bundledRendererAssetsOnly = bundledRendererAssetsOnly,
        finalMetadataPassed = finalMetadataPassed,
        ocrRoundTripPassed = ocrRoundTripPassed,
    )
}
