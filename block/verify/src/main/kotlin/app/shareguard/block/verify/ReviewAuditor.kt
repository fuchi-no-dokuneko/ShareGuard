package app.shareguard.block.verify

import app.shareguard.core.model.BlockId
import app.shareguard.core.model.ConfidenceClass
import app.shareguard.core.model.DecisionStatus
import app.shareguard.core.model.Finding
import app.shareguard.core.model.FindingCategory
import app.shareguard.core.model.ImageRegionPolicy
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.ReviewStatus
import app.shareguard.core.model.SemanticImpact
import app.shareguard.core.model.SemanticRisk
import app.shareguard.core.model.VerificationStatus
import app.shareguard.core.model.toImmutableList

internal data class ReviewAuditBundle(
    val results: List<ReviewAuditResult>,
    val structuralFailureCodes: List<String>,
)

internal object ReviewAuditor {
    private val TXT_003 = BlockId("TXT-003")
    private val TXT_005 = BlockId("TXT-005")

    fun audit(request: VerificationRequest): ReviewAuditBundle {
        val context = request.context
        val document = requireNotNull(context.canonicalDocument) { "Verification requires a Canonical Document" }
        val approvedDecisions = context.decisions.filter {
            it.status == DecisionStatus.APPROVED && it.canonicalRevision == document.revision
        }
        fun isApproved(finding: Finding): Boolean = approvedDecisions.any { finding.findingId in it.findingIds }
        fun decisionAudit(
            type: ReviewAuditType,
            findings: List<Finding>,
            passCode: String,
            pendingCode: String,
        ): ReviewAuditResult {
            if (findings.isEmpty()) {
                return ReviewAuditResult(type, VerificationStatus.NOT_APPLICABLE, "NO_REVIEW_FINDINGS", 0)
            }
            val complete = findings.all(::isApproved)
            return ReviewAuditResult(
                type = type,
                status = if (complete) VerificationStatus.PASS else VerificationStatus.REVIEW_REQUIRED,
                summaryCode = if (complete) passCode else pendingCode,
                affectedFindingCount = findings.size,
            )
        }

        val invisibleFindings = context.findings.filter {
            it.blockId == TXT_003 ||
                (it.category == FindingCategory.UNICODE &&
                    (it.title.value.contains("INVISIBLE") || it.title.value.contains("IGNORABLE") ||
                        it.title.value.contains("BIDI")))
        }.filter(::meaningfulReviewFinding)
        val confusableFindings = context.findings.filter {
            it.blockId == TXT_005 || it.category == FindingCategory.CONFUSABLE
        }.filter(::meaningfulReviewFinding)
        val ocrFindings = context.findings.filter {
            it.confidenceClass == ConfidenceClass.OCR_DISAGREEMENT
        }.filter(::meaningfulReviewFinding)
        val urlFindings = context.findings.filter {
            it.category == FindingCategory.URL
        }.filter(::meaningfulReviewFinding)
        val layoutFindings = context.findings.filter {
            it.category == FindingCategory.LAYOUT
        }.filter(::meaningfulReviewFinding)

        val audits = mutableListOf<ReviewAuditResult>()
        audits += decisionAudit(
            ReviewAuditType.INVISIBLE_CHARACTER,
            invisibleFindings,
            "INVISIBLE_REVIEW_APPROVED",
            "INVISIBLE_REVIEW_PENDING",
        )
        audits += decisionAudit(
            ReviewAuditType.CONFUSABLE_CHARACTER,
            confusableFindings,
            "CONFUSABLE_REVIEW_APPROVED",
            "CONFUSABLE_REVIEW_PENDING",
        )
        audits += decisionAudit(
            ReviewAuditType.OCR_AMBIGUITY,
            ocrFindings,
            "OCR_AMBIGUITY_APPROVED",
            "OCR_AMBIGUITY_PENDING",
        )

        val changedUrls = document.urlTokens.filter {
            it.displayText != it.finalText || it.parsedComponents != it.normalizedComponents
        }
        val urlApplicable = document.urlTokens.isNotEmpty() || urlFindings.isNotEmpty()
        val urlComplete = urlFindings.all(::isApproved) && changedUrls.all { it.userApproved }
        audits += when {
            !urlApplicable -> ReviewAuditResult(
                ReviewAuditType.URL_POLICY,
                VerificationStatus.NOT_APPLICABLE,
                "NO_URL_REVIEW_REQUIRED",
                0,
            )
            urlComplete -> ReviewAuditResult(
                ReviewAuditType.URL_POLICY,
                VerificationStatus.PASS,
                "URL_POLICY_APPROVED",
                urlFindings.size,
            )
            else -> ReviewAuditResult(
                ReviewAuditType.URL_POLICY,
                VerificationStatus.REVIEW_REQUIRED,
                "URL_POLICY_REVIEW_PENDING",
                urlFindings.size,
            )
        }

        val readingOrderEvidence = request.reviewEvidence.approvedReadingOrder
        val readingOrderComplete = readingOrderEvidence != null &&
            readingOrderEvidence == document.readingOrder.blockIds && layoutFindings.all(::isApproved)
        audits += when {
            layoutFindings.isEmpty() -> ReviewAuditResult(
                ReviewAuditType.READING_ORDER,
                VerificationStatus.NOT_APPLICABLE,
                "READING_ORDER_CONFIDENT",
                0,
            )
            readingOrderComplete -> ReviewAuditResult(
                ReviewAuditType.READING_ORDER,
                VerificationStatus.PASS,
                "READING_ORDER_APPROVED",
                layoutFindings.size,
            )
            else -> ReviewAuditResult(
                ReviewAuditType.READING_ORDER,
                VerificationStatus.REVIEW_REQUIRED,
                "READING_ORDER_REVIEW_PENDING",
                layoutFindings.size,
            )
        }

        val rebuilt = request.outputBundle.outputMode in setOf(OutputMode.REBUILT_IMAGE, OutputMode.BOTH)
        val regionEvidence = request.reviewEvidence.approvedRegionPolicies
        val documentRegionPolicies = document.imageRegions.associate { it.regionId to it.policy }
        val regionComplete = regionEvidence != null &&
            regionEvidence == documentRegionPolicies &&
            document.imageRegions.all { region ->
                region.userApproved &&
                    (region.policy != ImageRegionPolicy.RETAIN_SOURCE_PIXELS || region.sourcePixelRetained)
            }
        audits += when {
            !rebuilt || document.imageRegions.isEmpty() -> ReviewAuditResult(
                ReviewAuditType.IMAGE_REGION_POLICY,
                VerificationStatus.NOT_APPLICABLE,
                "NO_IMAGE_REGION_REVIEW_REQUIRED",
                0,
            )
            regionComplete -> ReviewAuditResult(
                ReviewAuditType.IMAGE_REGION_POLICY,
                VerificationStatus.PASS,
                "IMAGE_REGION_POLICY_APPROVED",
                document.imageRegions.size,
            )
            else -> ReviewAuditResult(
                ReviewAuditType.IMAGE_REGION_POLICY,
                VerificationStatus.REVIEW_REQUIRED,
                "IMAGE_REGION_REVIEW_PENDING",
                document.imageRegions.size,
            )
        }

        val ledgerIds = request.changeLedger.entries.map { it.changeId }.toSet()
        val transformationIds = request.appliedTransformations.flatMap { it.changeIds }.toSet()
        val semanticApproval = request.reviewEvidence.semanticDiffApproval
        val semanticEntriesApproved = request.changeLedger.entries.all { entry ->
            if (entry.semanticImpact == SemanticImpact.NONE) {
                true
            } else {
                val link = entry.reviewLink
                link != null && link.status == ReviewStatus.APPROVED && approvedDecisions.any { decision ->
                    decision.decisionId == link.decisionId &&
                        decision.findingIds.containsAll(link.findingIds) &&
                        decision.semanticImpact != SemanticImpact.NONE
                }
            }
        }
        val semanticDiffComplete = semanticApproval != null &&
            semanticApproval.canonicalRevision == document.revision &&
            semanticApproval.approvedChangeIds == ledgerIds &&
            transformationIds == ledgerIds &&
            semanticEntriesApproved
        audits += ReviewAuditResult(
            type = ReviewAuditType.SEMANTIC_DIFF,
            status = if (semanticDiffComplete) VerificationStatus.PASS else VerificationStatus.REVIEW_REQUIRED,
            summaryCode = if (semanticDiffComplete) "SEMANTIC_DIFF_APPROVED" else "SEMANTIC_DIFF_INCOMPLETE",
            affectedFindingCount = context.findings.count { it.category == FindingCategory.SEMANTIC },
        )

        val assuranceApproval = request.reviewEvidence.assuranceConsequenceApproval
        val assuranceComplete = assuranceApproval != null &&
            assuranceApproval.userAcknowledged &&
            assuranceApproval.shownCeiling == context.assuranceCeiling &&
            context.assuranceCeiling == request.preset.assuranceCeiling
        audits += ReviewAuditResult(
            type = ReviewAuditType.ASSURANCE_CONSEQUENCE,
            status = if (assuranceComplete) VerificationStatus.PASS else VerificationStatus.REVIEW_REQUIRED,
            summaryCode = if (assuranceComplete) {
                "ASSURANCE_CONSEQUENCE_APPROVED"
            } else {
                "ASSURANCE_CONSEQUENCE_NOT_APPROVED"
            },
            affectedFindingCount = context.findings.count { it.requiresUserDecision },
        )

        val failures = buildList {
            if (request.changeLedger.canonicalRevision != document.revision) add("CHANGE_LEDGER_REVISION_MISMATCH")
            if (request.changeLedger.ledgerId != document.changeLedgerReference) add("CHANGE_LEDGER_REFERENCE_MISMATCH")
            if (request.appliedTransformations.any { it.canonicalRevision != document.revision }) {
                add("TRANSFORMATION_REVISION_MISMATCH")
            }
            val ledgerById = request.changeLedger.entries.associateBy { it.changeId }
            val manifestVersions = request.executedBlockManifest.associate { it.blockId to it.blockVersion }
            request.appliedTransformations.forEach { transformation ->
                if (manifestVersions[transformation.blockId] != transformation.blockVersion) {
                    add("TRANSFORMATION_MANIFEST_LINK_MISMATCH")
                }
                transformation.changeIds.forEach { changeId ->
                    val entry = ledgerById[changeId]
                    if (entry == null) {
                        add("CHANGE_LEDGER_ENTRY_MISSING")
                    } else if (entry.blockId != transformation.blockId ||
                        entry.blockVersion != transformation.blockVersion
                    ) {
                        add("CHANGE_LEDGER_BLOCK_LINK_MISMATCH")
                    }
                }
            }
            if (ledgerIds != transformationIds) add("CHANGE_LEDGER_SCOPE_MISMATCH")
        }.distinct()

        return ReviewAuditBundle(audits.toImmutableList(), failures)
    }

    private fun meaningfulReviewFinding(finding: Finding): Boolean =
        finding.requiresUserDecision || finding.semanticRisk in setOf(
            SemanticRisk.POSSIBLE_MEANING_CHANGE,
            SemanticRisk.HIGH_IMPACT,
        )
}
