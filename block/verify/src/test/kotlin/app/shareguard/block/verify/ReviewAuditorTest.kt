package app.shareguard.block.verify

import app.shareguard.core.model.BlockId
import app.shareguard.core.model.ConfidenceClass
import app.shareguard.core.model.DecisionAction
import app.shareguard.core.model.DecisionId
import app.shareguard.core.model.DecisionStatus
import app.shareguard.core.model.Finding
import app.shareguard.core.model.FindingCategory
import app.shareguard.core.model.FindingId
import app.shareguard.core.model.FindingStatus
import app.shareguard.core.model.ImageRegion
import app.shareguard.core.model.ImageRegionId
import app.shareguard.core.model.ImageRegionPolicy
import app.shareguard.core.model.ImageRegionType
import app.shareguard.core.model.ImmutableList
import app.shareguard.core.model.NormalizedRect
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SemanticImpact
import app.shareguard.core.model.SemanticRisk
import app.shareguard.core.model.Severity
import app.shareguard.core.model.UserDecision
import app.shareguard.core.model.VerificationStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReviewAuditorTest {
    @Test
    fun `audit always emits REV-001 through REV-008 in normative order`() {
        val bundle = ReviewAuditor.audit(VerificationFixtures.text().request)

        assertThat(bundle.results.map { it.type }).containsExactlyElementsIn(ReviewAuditType.entries).inOrder()
        assertThat(bundle.results.map { it.type.blockId })
            .containsExactly("REV-001", "REV-002", "REV-003", "REV-004", "REV-005", "REV-006", "REV-007", "REV-008")
            .inOrder()
    }

    @Test
    fun `REV-001 invisible character with no approved linked decision remains review required`() {
        val finding = finding("invisible", BlockId("TXT-003"), FindingCategory.UNICODE)
        val audit = ReviewAuditor.audit(VerificationFixtures.text(findings = listOf(finding)).request)

        assertThat(audit.result(ReviewAuditType.INVISIBLE_CHARACTER).status)
            .isEqualTo(VerificationStatus.REVIEW_REQUIRED)
    }

    @Test
    fun `REV-002 confusable with no approved linked decision remains review required`() {
        val finding = finding("confusable", BlockId("TXT-005"), FindingCategory.CONFUSABLE)
        val audit = ReviewAuditor.audit(VerificationFixtures.text(findings = listOf(finding)).request)

        assertThat(audit.result(ReviewAuditType.CONFUSABLE_CHARACTER).status)
            .isEqualTo(VerificationStatus.REVIEW_REQUIRED)
    }

    @Test
    fun `REV-003 OCR ambiguity with no approved linked decision remains review required`() {
        val finding = finding(
            "ocr",
            BlockId("IMG-012"),
            FindingCategory.SEMANTIC,
            ConfidenceClass.OCR_DISAGREEMENT,
        )
        val audit = ReviewAuditor.audit(VerificationFixtures.text(findings = listOf(finding)).request)

        assertThat(audit.result(ReviewAuditType.OCR_AMBIGUITY).status)
            .isEqualTo(VerificationStatus.REVIEW_REQUIRED)
    }

    @Test
    fun `REV-004 changed URL finding with no decision blocks URL policy approval`() {
        val finding = finding("url", BlockId("URL-015"), FindingCategory.URL)
        val audit = ReviewAuditor.audit(VerificationFixtures.text(findings = listOf(finding)).request)

        assertThat(audit.result(ReviewAuditType.URL_POLICY).status)
            .isEqualTo(VerificationStatus.REVIEW_REQUIRED)
    }

    @Test
    fun `REV-005 uncertain reading order requires exact reviewed order evidence and decision`() {
        val finding = finding("layout", BlockId("IMG-014"), FindingCategory.LAYOUT)
        val pending = VerificationFixtures.text(findings = listOf(finding))
        assertThat(ReviewAuditor.audit(pending.request).result(ReviewAuditType.READING_ORDER).status)
            .isEqualTo(VerificationStatus.REVIEW_REQUIRED)

        val decision = decision(finding)
        val approved = VerificationFixtures.text(
            findings = listOf(finding),
            decisions = listOf(decision),
            reviewEvidence = ReviewEvidence.create(
                approvedReadingOrder = listOf(VerificationFixtures.blockId),
                semanticDiffApproval = SemanticDiffApproval(VerificationFixtures.revision, emptySet()),
                assuranceConsequenceApproval = AssuranceConsequenceApproval(
                    app.shareguard.core.model.AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT,
                    true,
                ),
            ),
        )
        assertThat(ReviewAuditor.audit(approved.request).result(ReviewAuditType.READING_ORDER).status)
            .isEqualTo(VerificationStatus.PASS)
    }

    @Test
    fun `REV-006 image region cannot be silently accepted without explicit approval`() {
        val region = ImageRegion(
            regionId = ImageRegionId("region-unapproved"),
            regionType = ImageRegionType.UNKNOWN,
            sourceBounds = NormalizedRect(0f, 0f, 1f, 1f),
            canonicalBounds = null,
            policy = ImageRegionPolicy.REMOVE,
            sourcePixelRetained = false,
            replacementAssetId = null,
            userApproved = false,
            dependencyReason = SafeSummary("UNKNOWN_REGION_POLICY"),
        )
        val fixture = VerificationFixtures.image(imageRegions = listOf(region))

        assertThat(ReviewAuditor.audit(fixture.request).result(ReviewAuditType.IMAGE_REGION_POLICY).status)
            .isEqualTo(VerificationStatus.REVIEW_REQUIRED)
    }

    @Test
    fun `REV-007 semantic diff requires revision-linked complete approved change set`() {
        val fixture = VerificationFixtures.text(
            reviewEvidence = ReviewEvidence.create(
                assuranceConsequenceApproval = AssuranceConsequenceApproval(
                    app.shareguard.core.model.AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT,
                    true,
                ),
            ),
        )

        assertThat(ReviewAuditor.audit(fixture.request).result(ReviewAuditType.SEMANTIC_DIFF).status)
            .isEqualTo(VerificationStatus.REVIEW_REQUIRED)
    }

    @Test
    fun `REV-008 assurance consequence must show exact preset ceiling and be acknowledged`() {
        val noGate = VerificationFixtures.text(
            reviewEvidence = ReviewEvidence.create(
                semanticDiffApproval = SemanticDiffApproval(VerificationFixtures.revision, emptySet()),
            ),
        )
        assertThat(ReviewAuditor.audit(noGate.request).result(ReviewAuditType.ASSURANCE_CONSEQUENCE).status)
            .isEqualTo(VerificationStatus.REVIEW_REQUIRED)

        val wrongCeiling = noGate.request.copy(
            reviewEvidence = ReviewEvidence.create(
                semanticDiffApproval = SemanticDiffApproval(VerificationFixtures.revision, emptySet()),
                assuranceConsequenceApproval = AssuranceConsequenceApproval(
                    app.shareguard.core.model.AssuranceClass.AS_1_REENCODED_DERIVATIVE,
                    true,
                ),
            ),
        )
        assertThat(ReviewAuditor.audit(wrongCeiling).result(ReviewAuditType.ASSURANCE_CONSEQUENCE).status)
            .isEqualTo(VerificationStatus.REVIEW_REQUIRED)
    }

    @Test
    fun `approved decision must link finding and active canonical revision`() {
        val finding = finding("invisible-approved", BlockId("TXT-003"), FindingCategory.UNICODE)
        val fixture = VerificationFixtures.text(
            findings = listOf(finding),
            decisions = listOf(decision(finding)),
        )

        assertThat(ReviewAuditor.audit(fixture.request).result(ReviewAuditType.INVISIBLE_CHARACTER).status)
            .isEqualTo(VerificationStatus.PASS)
    }

    private fun finding(
        id: String,
        blockId: BlockId,
        category: FindingCategory,
        confidence: ConfidenceClass = ConfidenceClass.CERTAIN_BY_PARSER,
    ): Finding = Finding(
        findingId = FindingId("finding-$id"),
        blockId = blockId,
        category = category,
        severity = Severity.HIGH,
        confidenceClass = confidence,
        sourceLocation = null,
        canonicalLocation = null,
        title = SafeSummary("REVIEW_FIXTURE"),
        explanation = SafeSummary("REVIEW_REQUIRED_FIXTURE"),
        suggestedAction = DecisionAction.ACCEPT_PROPOSED_CHANGE,
        semanticRisk = SemanticRisk.HIGH_IMPACT,
        requiresUserDecision = true,
        status = FindingStatus.REVIEW_REQUIRED,
        evidenceSummary = SafeSummary("CONTENT_FREE_EVIDENCE"),
    )

    private fun decision(finding: Finding): UserDecision = UserDecision(
        decisionId = DecisionId("decision-${finding.findingId.value}"),
        findingIds = ImmutableList.of(finding.findingId),
        action = DecisionAction.ACCEPT_PROPOSED_CHANGE,
        status = DecisionStatus.APPROVED,
        semanticImpact = SemanticImpact.POSSIBLE,
        rationale = SafeSummary("USER_APPROVED_REVIEW"),
        canonicalRevision = VerificationFixtures.revision,
    )

    private fun ReviewAuditBundle.result(type: ReviewAuditType): ReviewAuditResult =
        results.single { it.type == type }
}
