package app.shareguard.block.text

import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.SemanticImpact
import app.shareguard.testcorpus.CorpusCase
import app.shareguard.testcorpus.CorpusLoader
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class TextCorpusTest {
    private val canonicalizer = TextCanonicalizer()
    private val corpus = CorpusLoader.loadDefault()

    @Test
    fun allRequiredTextCases_produceReviewedExpectedCanonicalText() {
        assertThat(corpus.requiredTextCases.map { it.caseId })
            .containsExactlyElementsIn((1..20).map { "TC-TXT-%03d".format(it) })
            .inOrder()

        corpus.requiredTextCases.forEach { case ->
            val input = case.toInput()
            val approvals = case.approvals(input)
            val result = canonicalizer.canonicalize(
                input = input,
                canonicalRevision = CanonicalRevision(1),
                approvals = approvals,
                idPrefix = case.caseId.lowercase(),
            )

            assertWithMessage(case.caseId).that(result.failures).isEmpty()
            assertWithMessage(case.caseId).that(result.locked).isNotNull()
            assertWithMessage(case.caseId).that(result.canonicalText).isEqualTo(case.expectedCanonicalText)
            assertWithMessage(case.caseId).that(result.proposals.all { it.applied }).isTrue()
            assertWithMessage(case.caseId).that(
                result.reviewGates.filter { it.blocking }.map { it.code.name }.toSet(),
            ).containsExactlyElementsIn(case.expectedMandatoryReviews.toSet())
            result.ledgerEntries.filter { it.semanticImpact != SemanticImpact.NONE }.forEach { entry ->
                assertWithMessage("${case.caseId}:${entry.changeId.value}").that(entry.reviewLink).isNotNull()
            }

            val secondInput = TextProcessingInput.create(
                visibleText = result.canonicalText,
                sourceKind = when (input.sourceKind) {
                    TextSourceKind.CODE_BLOCK -> TextSourceKind.CODE_BLOCK
                    TextSourceKind.IDENTIFIER -> TextSourceKind.IDENTIFIER
                    else -> TextSourceKind.PLAIN_TEXT
                },
            )
            val second = canonicalizer.canonicalize(
                input = secondInput,
                canonicalRevision = CanonicalRevision(2),
                approvals = approvals,
                idPrefix = "${case.caseId.lowercase()}-second",
            )
            assertWithMessage("${case.caseId}:idempotence").that(second.canonicalText)
                .isEqualTo(result.canonicalText)
        }
    }

    @Test
    fun exactTextConvergenceFamilies_convergeAndRemainIdempotent() {
        corpus.convergenceFamilies
            .filter { it.familyId.startsWith("CF-TXT-") && it.canonicalComparison == "EXACT" }
            .forEach { family ->
                val outputs = family.variants.map { variant ->
                    val input = TextProcessingInput.create(
                        visibleText = variant.sourceRepresentation.value,
                        sourceKind = TextSourceKind.PLAIN_TEXT,
                    )
                    val approvalCodes = if (family.familyId.contains("PUNCTUATION")) {
                        setOf(TextReviewCode.PUNCTUATION_SEMANTICS_REVIEW)
                    } else {
                        emptySet()
                    }
                    canonicalizer.canonicalize(
                        input = input,
                        canonicalRevision = CanonicalRevision(1),
                        approvals = TextReviewApprovals.create(approvedCodes = approvalCodes),
                        idPrefix = variant.variantId,
                    ).canonicalText
                }
                assertWithMessage(family.familyId).that(outputs.toSet())
                    .containsExactly(family.expectedCanonicalText)
            }
    }

    @Test
    fun protectedCodeAndIdentifierSpans_neverReceiveAutomaticRewrites() {
        val text = "prefix `a  −  b` user_раypal suffix"
        val result = canonicalizer.canonicalize(
            input = TextProcessingInput.create(text),
            canonicalRevision = CanonicalRevision(1),
            approvals = TextReviewApprovals.create(
                approvedCodes = setOf(TextReviewCode.MIXED_SCRIPT_CONFUSABLE_REVIEW),
                confusableTokenReplacements = mapOf("раypal" to "paypal"),
            ),
        )

        assertThat(result.canonicalText).contains("`a  −  b`")
        assertThat(result.canonicalText).contains("user_раypal")
        assertThat(result.inspection.protectedSpans.map { it.kind }).contains(ProtectedSpanKind.CODE)
        assertThat(result.inspection.protectedSpans.map { it.kind }).contains(ProtectedSpanKind.IDENTIFIER)
    }

    @Test
    fun semanticPunctuationChange_isProposedButNotAppliedWithoutReview() {
        val result = canonicalizer.canonicalize(
            input = TextProcessingInput.create("wait—now"),
            canonicalRevision = CanonicalRevision(1),
        )

        assertThat(result.canonicalText).isEqualTo("wait—now")
        assertThat(result.locked).isNull()
        assertThat(result.reviewGates.map { it.code }).contains(TextReviewCode.PUNCTUATION_SEMANTICS_REVIEW)
        assertThat(result.proposals.single { it.blockId.value == "TXT-014" }.applied).isFalse()
        assertThat(result.ledgerEntries.none { it.blockId.value == "TXT-014" }).isTrue()
    }

    @Test
    fun malformedUtf16_isInventoryEvidenceAndContentFreeFailure() {
        val source = "\uD800private-canary"
        val inspection = UnicodeTextInspector().inspect(TextProcessingInput.create(source))
        val result = canonicalizer.canonicalize(
            TextProcessingInput.create(source),
            CanonicalRevision(1),
            approvals = TextReviewApprovals.create(
                approvedCodes = setOf(TextReviewCode.MALFORMED_UNICODE_REVIEW),
            ),
        )

        assertThat(inspection.scalarInventory.first().malformedUtf16).isTrue()
        assertThat(result.locked).isNull()
        assertThat(result.failures.map { it.code }).contains(TextFailureCode.MALFORMED_UTF16)
        assertThat(result.failures.toString()).doesNotContain("private-canary")
    }

    @Test
    fun unsupportedControl_blocksCanonicalLockWithoutEchoingContent() {
        val result = canonicalizer.canonicalize(
            TextProcessingInput.create("private\u0000canary"),
            CanonicalRevision(1),
        )

        assertThat(result.locked).isNull()
        assertThat(result.failures.map { it.code }).contains(TextFailureCode.UNSUPPORTED_SCALAR)
        assertThat(result.failures.toString()).doesNotContain("private")
    }

    @Test
    fun scalarInventory_roundTripsSupplementaryAndCombiningScalars() {
        val source = "A😀e\u0301"
        val inspector = UnicodeTextInspector()
        val inspection = inspector.inspect(TextProcessingInput.create(source))

        assertThat(inspection.scalarInventory).hasSize(4)
        assertThat(inspection.graphemeClusters).hasSize(3)
        assertThat(inspector.reserializeScalars(inspection)).isEqualTo(source)
    }

    @Test
    fun inspection_recordsAllNormalizationFormsAndLineStructure() {
        val input = TextProcessingInput.create("  1. Café\r\n\r\nnext")
        val inspection = UnicodeTextInspector().inspect(input)

        assertThat(inspection.normalizationDeltas.map { it.form }.toSet()).containsAtLeast(
            TextNormalizationForm.NFC,
            TextNormalizationForm.NFKC,
        )
        assertThat(inspection.lineStructure).hasSize(3)
        assertThat(inspection.lineStructure.first().indentationScalars).isEqualTo(2)
        assertThat(inspection.lineStructure.first().listMarker).isEqualTo("  1.")
        assertThat(inspection.lineStructure[1].blank).isTrue()
    }

    private fun CorpusCase.toInput(): TextProcessingInput {
        val format = sourceRepresentation.format
        val visible = if (format == "HTML_CLIPBOARD") {
            sourceRepresentation.attributes.getValue("plainTextAlternative")
        } else {
            sourceRepresentation.value
        }
        return TextProcessingInput.create(
            visibleText = visible,
            sourceKind = when (format) {
                "CODE_BLOCK" -> TextSourceKind.CODE_BLOCK
                "IDENTIFIER" -> TextSourceKind.IDENTIFIER
                "STYLED_ANDROID_SPAN" -> TextSourceKind.STYLED_TEXT
                "HTML_CLIPBOARD" -> TextSourceKind.HTML_CLIPBOARD
                "LONG_TEXT" -> TextSourceKind.LONG_TEXT
                else -> TextSourceKind.PLAIN_TEXT
            },
            richRepresentationPresent = format in setOf("STYLED_ANDROID_SPAN", "HTML_CLIPBOARD"),
            hiddenAlternativePresent = format == "HTML_CLIPBOARD",
        )
    }

    private fun CorpusCase.approvals(input: TextProcessingInput): TextReviewApprovals {
        val codes = expectedMandatoryReviews.map(TextReviewCode::valueOf).toSet()
        val replacements = when (caseId) {
            "TC-TXT-006" -> mapOf("pаypal" to "paypal")
            "TC-TXT-007" -> mapOf("gοogle" to "google")
            else -> emptyMap()
        }
        return TextReviewApprovals.create(
            approvedCodes = codes,
            confusableTokenReplacements = replacements,
            approvedVisualWrapOffsetsUtf16 = input.visualWrapOffsetsUtf16.toSet(),
        )
    }
}
