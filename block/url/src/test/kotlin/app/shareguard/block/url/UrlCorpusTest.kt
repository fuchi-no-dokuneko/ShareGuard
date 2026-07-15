package app.shareguard.block.url

import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.SemanticImpact
import app.shareguard.core.model.UrlTokenId
import app.shareguard.testcorpus.CorpusCase
import app.shareguard.testcorpus.CorpusLoader
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class UrlCorpusTest {
    private val corpus = CorpusLoader.loadDefault()
    private val extractor = UrlCandidateExtractor()
    private val service = UrlProcessingService()

    @Test
    fun allRequiredUrlCases_produceExpectedReviewedOutput() {
        assertThat(corpus.requiredUrlCases.map { it.caseId })
            .containsExactlyElementsIn((1..15).map { "TC-URL-%03d".format(it) })
            .inOrder()

        corpus.requiredUrlCases.forEach { case ->
            val input = case.toInput()
            val prefix = case.caseId.lowercase()
            val candidates = extractor.extract(input, prefix)
            assertWithMessage(case.caseId).that(candidates).hasSize(1)
            val approvals = case.approvals(candidates.single().tokenId)
            val result = service.process(
                input = input,
                canonicalRevision = CanonicalRevision(1),
                approvals = approvals,
                idPrefix = prefix,
            )
            val canonicalization = result.canonicalizations.single()

            assertWithMessage(case.caseId).that(canonicalization.failures).isEmpty()
            assertWithMessage(case.caseId).that(canonicalization.approved).isTrue()
            assertWithMessage(case.caseId).that(result.canonicalText).isEqualTo(case.expectedCanonicalText)
            assertWithMessage(case.caseId).that(canonicalization.urlToken?.finalText)
                .isEqualTo(case.expectedUrlDecisions.single().expectedOutput)
            assertWithMessage(case.caseId).that(canonicalization.reviewGates.map { it.code.name }.toSet())
                .containsExactlyElementsIn(case.expectedMandatoryReviews.toSet())
            canonicalization.ledgerEntries.filter { it.semanticImpact != SemanticImpact.NONE }.forEach { entry ->
                assertWithMessage("${case.caseId}:${entry.changeId.value}").that(entry.reviewLink).isNotNull()
            }

            val secondInput = UrlProcessingInput.create(
                text = requireNotNull(result.canonicalText),
                sourceKind = UrlSourceKind.PLAIN_TEXT,
            )
            val secondCandidates = extractor.extract(secondInput, "$prefix-second")
            val secondApprovals = UrlReviewApprovals.create(
                approvedCodes = approvals.approvedCodes,
                approvedHostByTokenId = secondCandidates.associate { it.tokenId to "paypal.com" }
                    .takeIf { case.caseId == "TC-URL-007" }
                    .orEmpty(),
            )
            val second = service.process(
                secondInput,
                CanonicalRevision(2),
                secondApprovals,
                "$prefix-second",
            )
            assertWithMessage("${case.caseId}:idempotence").that(second.canonicalText)
                .isEqualTo(result.canonicalText)
        }
    }

    @Test
    fun knownTrackingRemoval_hasOneLedgerEntryPerRemovedParameter() {
        val case = corpus.requiredUrlCases.single { it.caseId == "TC-URL-001" }
        val input = case.toInput()
        val result = service.process(input, CanonicalRevision(1), idPrefix = "tracking")
        val canonicalization = result.canonicalizations.single()

        assertThat(canonicalization.ledgerEntries.filter { it.blockId.value == "URL-010" }).hasSize(2)
        assertThat(canonicalization.ledgerEntries.all { it.semanticImpact == SemanticImpact.NONE }).isTrue()
        assertThat(result.canonicalText).isEqualTo(case.expectedCanonicalText)
    }

    @Test
    fun semanticUrlRewrite_isProposedButNeverAppliedWithoutApproval() {
        val case = corpus.requiredUrlCases.single { it.caseId == "TC-URL-002" }
        val result = service.process(case.toInput(), CanonicalRevision(1), idPrefix = "blocked")
        val canonicalization = result.canonicalizations.single()

        assertThat(canonicalization.proposal?.proposedText).isEqualTo(case.expectedCanonicalText)
        assertThat(canonicalization.urlToken).isNull()
        assertThat(canonicalization.ledgerEntries).isEmpty()
        assertThat(canonicalization.failures.map { it.code }).contains(UrlFailureCode.UNRESOLVED_REVIEW)
    }

    @Test
    fun publicSuffixResolution_usesMultiLabelBoundary() {
        val case = corpus.requiredUrlCases.single { it.caseId == "TC-URL-008" }
        val candidate = extractor.extract(case.toInput(), "suffix").single()
        val analysis = StandardsUrlAnalyzer().analyze(candidate)

        assertThat(analysis.parsed?.parsedComponents?.registrableDomain).isEqualTo("example.co.uk")
        assertThat(analysis.parsed?.parsedComponents?.subdomain).isEqualTo("shop")
    }

    @Test
    fun lineWrapConvergenceFamily_convergesAfterExplicitWrapReview() {
        val family = corpus.convergenceFamilies.single { it.familyId == "CF-URL-WRAP-001" }
        val outputs = family.variants.map { variant ->
            val text = variant.sourceRepresentation.value
            val wrapOffsets = text.indices.filter { text[it] == '\n' }
            val input = UrlProcessingInput.create(
                text = text,
                sourceKind = if (wrapOffsets.isEmpty()) UrlSourceKind.PLAIN_TEXT_URL else UrlSourceKind.WRAPPED_TEXT,
                visualWrapOffsetsUtf16 = wrapOffsets,
            )
            val prefix = variant.variantId
            val approvals = UrlReviewApprovals.create(
                approvedCodes = if (wrapOffsets.isEmpty()) {
                    emptySet()
                } else {
                    setOf(UrlReviewCode.URL_LINE_WRAP_RECONSTRUCTION_REVIEW)
                },
            )
            service.process(input, CanonicalRevision(1), approvals, prefix).canonicalText
        }

        assertThat(outputs.toSet()).containsExactly(family.expectedCanonicalText)
    }

    private fun CorpusCase.toInput(): UrlProcessingInput {
        val value = sourceRepresentation.value
        val format = sourceRepresentation.format
        val wraps = if (format == "WRAPPED_TEXT") value.indices.filter { value[it] == '\n' } else emptyList()
        return UrlProcessingInput.create(
            text = value,
            sourceKind = when (format) {
                "PLAIN_TEXT_URL" -> UrlSourceKind.PLAIN_TEXT_URL
                "MARKDOWN" -> UrlSourceKind.MARKDOWN
                "QR_PAYLOAD" -> UrlSourceKind.QR_PAYLOAD
                "WRAPPED_TEXT" -> UrlSourceKind.WRAPPED_TEXT
                else -> UrlSourceKind.PLAIN_TEXT
            },
            visualWrapOffsetsUtf16 = wraps,
        )
    }

    private fun CorpusCase.approvals(tokenId: UrlTokenId): UrlReviewApprovals = UrlReviewApprovals.create(
        approvedCodes = expectedMandatoryReviews.map(UrlReviewCode::valueOf).toSet(),
        approvedHostByTokenId = if (caseId == "TC-URL-007") mapOf(tokenId to "paypal.com") else emptyMap(),
    )
}
