package app.shareguard.core.pipeline

import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.OutputMode
import kotlinx.serialization.Serializable

@Serializable
data class AssuranceEvidence(
    val outputMode: OutputMode,
    val assuranceCeiling: AssuranceClass,
    val allRequiredVerificationPassed: Boolean,
    val verificationFailurePresent: Boolean,
    val externallyEdited: Boolean,
    val sourceDependencyInformationComplete: Boolean,
    val retainedSourcePixels: Boolean,
    val retainedSourceRegionsDeclared: Boolean,
    val canonicalTextFromApprovedDocument: Boolean,
    val finalUnicodePassed: Boolean,
    val finalUrlPassed: Boolean,
    val unresolvedTextAmbiguitiesApproved: Boolean,
    val freshlyRenderedTextAndUi: Boolean,
    val bundledRendererAssetsOnly: Boolean,
    val finalMetadataPassed: Boolean,
    val ocrRoundTripPassed: Boolean,
)

@Serializable
data class AssuranceDecision(
    val assuranceClass: AssuranceClass,
    val reasonCodes: List<String>,
    val contradictions: List<String>,
)

object DeterministicAssuranceClassifier {
    fun classify(evidence: AssuranceEvidence): AssuranceDecision {
        val contradictions = contradictions(evidence)
        if (contradictions.isNotEmpty()) {
            return AssuranceDecision(
                AssuranceClass.AS_0_UNVERIFIED,
                listOf("CONTRADICTORY_ASSURANCE_EVIDENCE"),
                contradictions,
            )
        }
        if (!evidence.allRequiredVerificationPassed ||
            evidence.verificationFailurePresent ||
            evidence.externallyEdited ||
            !evidence.sourceDependencyInformationComplete
        ) {
            return AssuranceDecision(
                AssuranceClass.AS_0_UNVERIFIED,
                listOf("REQUIRED_ASSURANCE_PRECONDITION_FAILED"),
                emptyList(),
            )
        }

        val candidate = when (evidence.outputMode) {
            OutputMode.DERIVATIVE_IMAGE -> {
                if (evidence.finalMetadataPassed) AssuranceClass.AS_1_REENCODED_DERIVATIVE
                else AssuranceClass.AS_0_UNVERIFIED
            }
            OutputMode.TEXT -> {
                if (evidence.canonicalTextFromApprovedDocument &&
                    evidence.finalUnicodePassed &&
                    evidence.finalUrlPassed &&
                    evidence.unresolvedTextAmbiguitiesApproved
                ) {
                    AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT
                } else {
                    AssuranceClass.AS_0_UNVERIFIED
                }
            }
            OutputMode.REBUILT_IMAGE, OutputMode.BOTH -> when {
                evidence.retainedSourcePixels &&
                    evidence.retainedSourceRegionsDeclared &&
                    evidence.finalMetadataPassed &&
                    evidence.ocrRoundTripPassed -> AssuranceClass.AS_3_REBUILT_WITH_SOURCE_REGIONS
                !evidence.retainedSourcePixels &&
                    evidence.freshlyRenderedTextAndUi &&
                    evidence.bundledRendererAssetsOnly &&
                    evidence.finalMetadataPassed &&
                    evidence.ocrRoundTripPassed &&
                    evidence.canonicalTextFromApprovedDocument &&
                    evidence.unresolvedTextAmbiguitiesApproved ->
                    AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE
                else -> AssuranceClass.AS_0_UNVERIFIED
            }
        }

        val bounded = candidate.lowerOf(evidence.assuranceCeiling)
        val reasons = buildList {
            add("DETERMINISTIC_ASSURANCE_CLASSIFICATION")
            if (bounded != candidate) add("ASSURANCE_CEILING_APPLIED")
            if (evidence.retainedSourcePixels) add("DECLARED_SOURCE_PIXELS_RETAINED")
        }
        return AssuranceDecision(bounded, reasons, emptyList())
    }

    private fun contradictions(evidence: AssuranceEvidence): List<String> = buildList {
        if (evidence.retainedSourcePixels != evidence.retainedSourceRegionsDeclared) {
            add("SOURCE_PIXEL_DECLARATION_CONTRADICTION")
        }
        if (evidence.outputMode == OutputMode.DERIVATIVE_IMAGE &&
            !evidence.assuranceCeiling.isAtMost(AssuranceClass.AS_1_REENCODED_DERIVATIVE)
        ) {
            add("DERIVATIVE_CEILING_CONTRADICTION")
        }
        if (evidence.outputMode == OutputMode.TEXT && evidence.retainedSourcePixels) {
            add("TEXT_OUTPUT_SOURCE_PIXEL_CONTRADICTION")
        }
        if (evidence.verificationFailurePresent && evidence.allRequiredVerificationPassed) {
            add("VERIFICATION_STATUS_CONTRADICTION")
        }
    }
}
