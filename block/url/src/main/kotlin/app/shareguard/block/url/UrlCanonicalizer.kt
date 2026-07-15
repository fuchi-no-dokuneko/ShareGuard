package app.shareguard.block.url

import app.shareguard.core.model.BlockVersion
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.ChangeEntry
import app.shareguard.core.model.ChangeId
import app.shareguard.core.model.DecisionAction
import app.shareguard.core.model.DecisionStatus
import app.shareguard.core.model.FindingStatus
import app.shareguard.core.model.ReviewLink
import app.shareguard.core.model.ReviewStatus
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SemanticImpact
import app.shareguard.core.model.UserDecision
import app.shareguard.core.model.UrlToken
import app.shareguard.core.model.toImmutableList
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class UrlCanonicalizer(
    private val policyEngine: UrlPolicyEngine = UrlPolicyEngine(),
) {
    fun canonicalize(
        analysis: UrlAnalysis,
        canonicalRevision: CanonicalRevision,
        approvals: UrlReviewApprovals = UrlReviewApprovals.none(),
        idPrefix: String = analysis.parsed?.candidate?.tokenId?.value ?: "url",
    ): UrlCanonicalizationResult {
        val proposal = policyEngine.propose(analysis, approvals)
        val parsed = analysis.parsed
        val invalidApprovedHost = if (parsed != null &&
            analysis.reviewGates.any { it.code == UrlReviewCode.IDN_CONFUSABLE_HOST_REVIEW }
        ) {
            val approved = approvals.approvedHostByTokenId[parsed.candidate.tokenId]
            approved == null || "https://$approved/".toHttpUrlOrNull() == null
        } else {
            false
        }
        val gates = analysis.reviewGates.map { gate ->
            val approved = approvals.isApproved(gate.code) && when (gate.code) {
                UrlReviewCode.IDN_CONFUSABLE_HOST_REVIEW -> !invalidApprovedHost
                UrlReviewCode.PARSE_FAILURE_REVIEW -> false
                else -> true
            }
            gate.copy(status = if (approved) ReviewStatus.APPROVED else ReviewStatus.PENDING)
        }
        val decisions = gates.map { gate ->
            UserDecision.create(
                decisionId = gate.decisionId,
                findingIds = gate.findingIds,
                action = decisionAction(gate.code),
                status = if (gate.status == ReviewStatus.APPROVED) {
                    DecisionStatus.APPROVED
                } else {
                    DecisionStatus.PENDING
                },
                semanticImpact = SemanticImpact.POSSIBLE,
                rationale = SafeSummary(gate.code.name),
                canonicalRevision = canonicalRevision.takeIf { gate.status == ReviewStatus.APPROVED },
            )
        }
        val failures = analysis.failures.toMutableList()
        gates.filter { it.blocking && it.status != ReviewStatus.APPROVED }.forEach { gate ->
            failures += UrlFailure(
                code = UrlFailureCode.UNRESOLVED_REVIEW,
                blockId = blockForReview(gate.code),
                sourceLocation = parsed?.candidate?.sourceLocation,
            )
        }
        if (invalidApprovedHost) {
            failures += UrlFailure(
                code = UrlFailureCode.INVALID_APPROVED_HOST,
                blockId = app.shareguard.core.model.BlockId("URL-003"),
                sourceLocation = parsed?.candidate?.sourceLocation,
            )
        }
        val canApply = parsed != null && proposal != null && failures.isEmpty()
        val ledger = if (canApply) {
            proposal.changes.mapIndexed { index, change ->
                val gate = change.reviewCode?.let { code -> gates.single { it.code == code } }
                ChangeEntry(
                    changeId = ChangeId("$idPrefix-c-$index"),
                    blockId = change.blockId,
                    blockVersion = BlockVersion(1),
                    canonicalRevision = canonicalRevision,
                    category = app.shareguard.core.model.FindingCategory.URL,
                    sourceLocation = parsed.candidate.sourceLocation,
                    beforeRepresentation = change.before,
                    afterRepresentation = change.after,
                    reason = SafeSummary("${change.blockId.value}_${change.component.name}"),
                    reversibleBeforeExport = true,
                    semanticImpact = if (gate == null) SemanticImpact.NONE else SemanticImpact.POSSIBLE,
                    reviewLink = gate?.let {
                        ReviewLink(
                            decisionId = it.decisionId,
                            findingIds = it.findingIds,
                            status = ReviewStatus.APPROVED,
                        )
                    },
                    verificationId = null,
                )
            }
        } else {
            emptyList()
        }
        val token = if (canApply) {
            UrlToken(
                tokenId = parsed.candidate.tokenId,
                originalReference = null,
                displayText = parsed.candidate.displayText.value,
                parsedComponents = parsed.parsedComponents,
                normalizedComponents = proposal.proposedComponents,
                chosenPolicy = proposal.chosenPolicy,
                finalText = proposal.proposedText,
                functionalityWarning = proposal.functionalityWarning,
                userApproved = gates.isNotEmpty() && gates.all { it.status == ReviewStatus.APPROVED },
            )
        } else {
            null
        }
        val changedFindingIds = ledger.flatMap { it.reviewLink?.findingIds.orEmpty() }.toSet()
        val findings = analysis.findings.map { finding ->
            when {
                finding.findingId in changedFindingIds -> finding.copy(status = FindingStatus.CHANGED)
                gates.any { it.status == ReviewStatus.APPROVED && finding.findingId in it.findingIds } -> {
                    finding.copy(status = FindingStatus.ACCEPTED)
                }
                else -> finding
            }
        }
        return UrlCanonicalizationResult(
            canonicalRevision = canonicalRevision,
            analysis = analysis,
            proposal = proposal,
            urlToken = token,
            findings = findings.toImmutableList(),
            decisions = decisions.toImmutableList(),
            ledgerEntries = ledger.toImmutableList(),
            reviewGates = gates.toImmutableList(),
            failures = failures.distinct().toImmutableList(),
        )
    }

    companion object {
        private fun decisionAction(code: UrlReviewCode): DecisionAction = when (code) {
            UrlReviewCode.UNKNOWN_URL_COMPONENT_REVIEW,
            UrlReviewCode.FRAGMENT_SEMANTICS_REVIEW,
            UrlReviewCode.QR_PAYLOAD_REVIEW,
            UrlReviewCode.URL_LINE_WRAP_RECONSTRUCTION_REVIEW,
            -> DecisionAction.REMOVE_QUERY_AND_FRAGMENT
            UrlReviewCode.PATH_SEMANTICS_REVIEW,
            UrlReviewCode.HOST_COMPONENT_REVIEW,
            UrlReviewCode.ORIGIN_ONLY_FUNCTIONALITY_REVIEW,
            UrlReviewCode.ENCODED_PATH_COMPONENT_REVIEW,
            -> DecisionAction.KEEP_ORIGIN_ONLY
            UrlReviewCode.IDN_CONFUSABLE_HOST_REVIEW -> DecisionAction.MANUAL_EDIT
            UrlReviewCode.UNRESOLVED_REDIRECT_REVIEW,
            UrlReviewCode.FUNCTIONAL_URL_COMPONENT_REVIEW,
            UrlReviewCode.SCHEMELESS_URL_REVIEW,
            UrlReviewCode.PUBLIC_SUFFIX_UNAVAILABLE_REVIEW,
            -> DecisionAction.KEEP_FULL_URL
            UrlReviewCode.DECEPTIVE_HOST_REVIEW,
            UrlReviewCode.VISIBLE_TEXT_LINK_TARGET_REVIEW,
            UrlReviewCode.MALFORMED_PERCENT_ENCODING_REVIEW,
            UrlReviewCode.PARSE_FAILURE_REVIEW,
            -> DecisionAction.ACCEPT_PROPOSED_CHANGE
        }

        private fun blockForReview(code: UrlReviewCode): app.shareguard.core.model.BlockId = when (code) {
            UrlReviewCode.SCHEMELESS_URL_REVIEW,
            UrlReviewCode.VISIBLE_TEXT_LINK_TARGET_REVIEW,
            UrlReviewCode.QR_PAYLOAD_REVIEW,
            UrlReviewCode.URL_LINE_WRAP_RECONSTRUCTION_REVIEW,
            -> app.shareguard.core.model.BlockId("URL-001")
            UrlReviewCode.PARSE_FAILURE_REVIEW,
            UrlReviewCode.MALFORMED_PERCENT_ENCODING_REVIEW,
            -> app.shareguard.core.model.BlockId("URL-002")
            UrlReviewCode.IDN_CONFUSABLE_HOST_REVIEW -> app.shareguard.core.model.BlockId("URL-003")
            UrlReviewCode.PUBLIC_SUFFIX_UNAVAILABLE_REVIEW -> app.shareguard.core.model.BlockId("URL-004")
            UrlReviewCode.UNKNOWN_URL_COMPONENT_REVIEW -> app.shareguard.core.model.BlockId("URL-005")
            UrlReviewCode.PATH_SEMANTICS_REVIEW,
            UrlReviewCode.ORIGIN_ONLY_FUNCTIONALITY_REVIEW,
            UrlReviewCode.ENCODED_PATH_COMPONENT_REVIEW,
            UrlReviewCode.FUNCTIONAL_URL_COMPONENT_REVIEW,
            -> app.shareguard.core.model.BlockId("URL-006")
            UrlReviewCode.HOST_COMPONENT_REVIEW -> app.shareguard.core.model.BlockId("URL-007")
            UrlReviewCode.FRAGMENT_SEMANTICS_REVIEW,
            UrlReviewCode.DECEPTIVE_HOST_REVIEW,
            -> app.shareguard.core.model.BlockId("URL-008")
            UrlReviewCode.UNRESOLVED_REDIRECT_REVIEW -> app.shareguard.core.model.BlockId("URL-009")
        }
    }
}

class UrlProcessingService(
    private val extractor: UrlCandidateExtractor = UrlCandidateExtractor(),
    private val analyzer: StandardsUrlAnalyzer = StandardsUrlAnalyzer(),
    private val canonicalizer: UrlCanonicalizer = UrlCanonicalizer(),
) {
    fun process(
        input: UrlProcessingInput,
        canonicalRevision: CanonicalRevision,
        approvals: UrlReviewApprovals = UrlReviewApprovals.none(),
        idPrefix: String = "url",
    ): UrlProcessingResult {
        val candidates = extractor.extract(input, idPrefix)
        val batch = analyzer.analyzeAll(candidates)
        val canonicalizations = batch.analyses.map { analysis ->
            canonicalizer.canonicalize(analysis, canonicalRevision, approvals)
        }
        val canonicalText = if (canonicalizations.all { it.approved }) {
            rewriteFresh(input.text, candidates, canonicalizations)
        } else {
            null
        }
        return UrlProcessingResult(
            analysisBatch = batch,
            canonicalizations = canonicalizations.toImmutableList(),
            canonicalText = canonicalText,
        )
    }

    private fun rewriteFresh(
        source: String,
        candidates: List<UrlCandidate>,
        results: List<UrlCanonicalizationResult>,
    ): String {
        val replacements = candidates.zip(results).map { (candidate, result) ->
            val token = requireNotNull(result.urlToken)
            val scalarStart = requireNotNull(candidate.sourceLocation.scalarStart)
            val scalarEnd = requireNotNull(candidate.sourceLocation.scalarEndExclusive)
            val utf16Start = source.offsetByCodePoints(0, scalarStart)
            val utf16End = source.offsetByCodePoints(0, scalarEnd)
            val replacement = if (candidate.kind == UrlCandidateKind.MARKDOWN_TARGET &&
                candidate.displayText.value != token.finalText
            ) {
                "${candidate.displayText.value} (${token.finalText})"
            } else {
                token.finalText
            }
            Replacement(utf16Start, utf16End, replacement)
        }.sortedBy { it.start }
        val output = StringBuilder(source.length)
        var cursor = 0
        replacements.forEach { replacement ->
            require(replacement.start >= cursor) { "OVERLAPPING_URL_CANDIDATES" }
            output.append(source, cursor, replacement.start)
            output.append(replacement.value)
            cursor = replacement.end
        }
        output.append(source, cursor, source.length)
        return output.toString()
    }

    private data class Replacement(val start: Int, val end: Int, val value: String)
}
