package app.shareguard.block.url

import app.shareguard.core.model.BlockId
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.ChangeEntry
import app.shareguard.core.model.DecisionId
import app.shareguard.core.model.Finding
import app.shareguard.core.model.FindingId
import app.shareguard.core.model.ImmutableList
import app.shareguard.core.model.ReviewStatus
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SensitiveRepresentation
import app.shareguard.core.model.SourceLocation
import app.shareguard.core.model.UrlComponents
import app.shareguard.core.model.UrlPolicy
import app.shareguard.core.model.UrlToken
import app.shareguard.core.model.UrlTokenId
import app.shareguard.core.model.UserDecision
import app.shareguard.core.model.toImmutableList

enum class UrlSourceKind {
    PLAIN_TEXT_URL,
    PLAIN_TEXT,
    MARKDOWN,
    QR_PAYLOAD,
    WRAPPED_TEXT,
    OCR_TEXT,
}

enum class UrlCandidateKind { ABSOLUTE, SCHEMELESS, MARKDOWN_TARGET, QR_PAYLOAD, EMAIL_LIKE }

enum class UrlReviewCode {
    PARSE_FAILURE_REVIEW,
    MALFORMED_PERCENT_ENCODING_REVIEW,
    UNKNOWN_URL_COMPONENT_REVIEW,
    PATH_SEMANTICS_REVIEW,
    HOST_COMPONENT_REVIEW,
    FRAGMENT_SEMANTICS_REVIEW,
    DECEPTIVE_HOST_REVIEW,
    IDN_CONFUSABLE_HOST_REVIEW,
    PUBLIC_SUFFIX_UNAVAILABLE_REVIEW,
    ORIGIN_ONLY_FUNCTIONALITY_REVIEW,
    ENCODED_PATH_COMPONENT_REVIEW,
    UNRESOLVED_REDIRECT_REVIEW,
    FUNCTIONAL_URL_COMPONENT_REVIEW,
    VISIBLE_TEXT_LINK_TARGET_REVIEW,
    QR_PAYLOAD_REVIEW,
    URL_LINE_WRAP_RECONSTRUCTION_REVIEW,
    SCHEMELESS_URL_REVIEW,
}

enum class UrlFailureCode {
    PARSE_FAILED,
    UNSUPPORTED_SCHEME,
    PUBLIC_SUFFIX_UNAVAILABLE,
    INVALID_APPROVED_HOST,
    UNRESOLVED_REVIEW,
    SERIALIZER_COMPONENT_MISMATCH,
    SERIALIZER_PARSE_FAILED,
}

enum class QueryRisk { KNOWN_TRACKING, LIKELY_IDENTIFIER, UNKNOWN, FUNCTIONAL }

enum class PathRisk { EMPTY, ORDINARY, LIKELY_IDENTIFIER, FUNCTIONAL_INVITE, PERCENT_ENCODED_IDENTIFIER }

enum class SubdomainRisk { NONE, ORDINARY, LIKELY_PERSONALIZED, DOMAIN_BOUNDARY_UNAVAILABLE }

enum class UrlComponentKind {
    LINE_WRAP,
    USERINFO,
    HOST,
    SUBDOMAIN,
    PATH,
    QUERY_PARAMETER,
    QUERY,
    FRAGMENT,
    DISPLAY_MODE,
    SERIALIZATION,
}

@ConsistentCopyVisibility
data class UrlProcessingInput private constructor(
    val text: String,
    val sourceKind: UrlSourceKind,
    val visualWrapOffsetsUtf16: ImmutableList<Int>,
) {
    companion object {
        fun create(
            text: String,
            sourceKind: UrlSourceKind,
            visualWrapOffsetsUtf16: Iterable<Int> = emptyList(),
        ): UrlProcessingInput {
            val wraps = visualWrapOffsetsUtf16.distinct().sorted().toImmutableList()
            require(wraps.all { it in text.indices && text[it] == '\n' }) { "INVALID_URL_VISUAL_WRAP" }
            return UrlProcessingInput(text, sourceKind, wraps)
        }
    }
}

data class UrlCandidate(
    val tokenId: UrlTokenId,
    val kind: UrlCandidateKind,
    val sourceLocation: SourceLocation,
    val originalReference: SensitiveRepresentation,
    val displayText: SensitiveRepresentation,
    val parseTarget: SensitiveRepresentation,
    val schemeWasImplicit: Boolean,
    val reconstructedVisualWrap: Boolean,
)

data class QueryInventoryEntry(
    val index: Int,
    val name: SensitiveRepresentation,
    val value: SensitiveRepresentation?,
    val risk: QueryRisk,
    val malformedPercentEncoding: Boolean,
)

data class PathInventoryEntry(
    val index: Int,
    val decodedSegment: SensitiveRepresentation,
    val risk: PathRisk,
)

data class UrlSecurityInventory(
    val queryParameters: ImmutableList<QueryInventoryEntry>,
    val pathSegments: ImmutableList<PathInventoryEntry>,
    val subdomainRisk: SubdomainRisk,
    val hasFragment: Boolean,
    val hasUserInfo: Boolean,
    val unresolvedRedirect: Boolean,
    val hostSpoofChecks: Int,
    val hostRestrictionLevel: String,
    val unicodeHost: SensitiveRepresentation,
    val hostSkeleton: SensitiveRepresentation,
    val publicSuffixDataAvailable: Boolean,
)

data class ParsedUrlCandidate(
    val candidate: UrlCandidate,
    val parsedComponents: UrlComponents,
    val inventory: UrlSecurityInventory,
)

data class UrlReviewGate(
    val code: UrlReviewCode,
    val decisionId: DecisionId,
    val findingIds: ImmutableList<FindingId>,
    val blocking: Boolean,
    val status: ReviewStatus,
    val summary: SafeSummary,
)

data class UrlFailure(
    val code: UrlFailureCode,
    val blockId: BlockId,
    val sourceLocation: SourceLocation?,
)

data class UrlAnalysis(
    val parsed: ParsedUrlCandidate?,
    val findings: ImmutableList<Finding>,
    val reviewGates: ImmutableList<UrlReviewGate>,
    val failures: ImmutableList<UrlFailure>,
)

data class UrlAnalysisBatch(
    val candidates: ImmutableList<UrlCandidate>,
    val analyses: ImmutableList<UrlAnalysis>,
    val findings: ImmutableList<Finding>,
    val reviewGates: ImmutableList<UrlReviewGate>,
    val failures: ImmutableList<UrlFailure>,
)

data class UrlComponentChange(
    val component: UrlComponentKind,
    val blockId: BlockId,
    val before: SensitiveRepresentation?,
    val after: SensitiveRepresentation?,
    val reviewCode: UrlReviewCode?,
)

data class UrlTransformationProposal(
    val tokenId: UrlTokenId,
    val chosenPolicy: UrlPolicy,
    val proposedComponents: UrlComponents,
    val proposedText: String,
    val clickable: Boolean,
    val requiredReviews: ImmutableList<UrlReviewCode>,
    val changes: ImmutableList<UrlComponentChange>,
    val functionalityWarning: SafeSummary?,
)

class UrlReviewApprovals private constructor(
    approvedCodes: Set<UrlReviewCode>,
    approvedHostByTokenId: Map<UrlTokenId, String>,
) {
    val approvedCodes: Set<UrlReviewCode> = approvedCodes.toSet()
    val approvedHostByTokenId: Map<UrlTokenId, String> = approvedHostByTokenId.toMap()

    fun isApproved(code: UrlReviewCode): Boolean = code in approvedCodes

    companion object {
        fun none(): UrlReviewApprovals = UrlReviewApprovals(emptySet(), emptyMap())

        fun create(
            approvedCodes: Set<UrlReviewCode> = emptySet(),
            approvedHostByTokenId: Map<UrlTokenId, String> = emptyMap(),
        ): UrlReviewApprovals = UrlReviewApprovals(approvedCodes, approvedHostByTokenId)
    }
}

data class UrlCanonicalizationResult(
    val canonicalRevision: CanonicalRevision,
    val analysis: UrlAnalysis,
    val proposal: UrlTransformationProposal?,
    val urlToken: UrlToken?,
    val findings: ImmutableList<Finding>,
    val decisions: ImmutableList<UserDecision>,
    val ledgerEntries: ImmutableList<ChangeEntry>,
    val reviewGates: ImmutableList<UrlReviewGate>,
    val failures: ImmutableList<UrlFailure>,
) {
    val approved: Boolean get() = urlToken != null && failures.isEmpty()
}

data class UrlProcessingResult(
    val analysisBatch: UrlAnalysisBatch,
    val canonicalizations: ImmutableList<UrlCanonicalizationResult>,
    val canonicalText: String?,
)
