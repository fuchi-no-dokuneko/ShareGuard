package app.shareguard.block.text

import app.shareguard.core.model.BlockId
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.ChangeEntry
import app.shareguard.core.model.ConfidenceClass
import app.shareguard.core.model.Finding
import app.shareguard.core.model.FindingId
import app.shareguard.core.model.ImmutableList
import app.shareguard.core.model.LanguagePolicy
import app.shareguard.core.model.ReviewStatus
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.ScriptCode
import app.shareguard.core.model.SensitiveRepresentation
import app.shareguard.core.model.SourceLocation
import app.shareguard.core.model.UserDecision
import app.shareguard.core.model.toImmutableList

enum class TextSourceKind {
    PLAIN_TEXT,
    OCR_APPROVED_TEXT,
    CODE_BLOCK,
    IDENTIFIER,
    STYLED_TEXT,
    HTML_CLIPBOARD,
    LONG_TEXT,
}

enum class TextNormalizationForm { NFC, NFD, NFKC, NFKD }

enum class TextWhitespaceProfile { DOCUMENT, MESSAGE, CODE }

enum class TextPunctuationProfile { ASCII_STABLE, PRESERVE_LANGUAGE }

enum class ProtectedSpanKind {
    CODE,
    IDENTIFIER,
    MATHEMATICS,
    EMAIL,
    URL,
    QUOTED_LITERAL,
    CRYPTOGRAPHIC_STRING,
    USER_LOCKED,
}

enum class TextReviewCode {
    MALFORMED_UNICODE_REVIEW,
    UNSUPPORTED_CONTROL_REVIEW,
    BIDI_DISPLAY_REVIEW,
    LANGUAGE_SENSITIVE_CONTROL_REVIEW,
    MIXED_SCRIPT_CONFUSABLE_REVIEW,
    COMPATIBILITY_NORMALIZATION_REVIEW,
    PUNCTUATION_SEMANTICS_REVIEW,
    CODE_REGION_REVIEW,
    MATH_AND_RANGE_PUNCTUATION_REVIEW,
    IDENTIFIER_CONFUSABLE_REVIEW,
    VISIBLE_TEXT_LINK_TARGET_REVIEW,
    VIEWPORT_WRAP_REVIEW,
    LANGUAGE_POLICY_REVIEW,
    NORMALIZATION_COLLISION_REVIEW,
}

enum class TextFailureCode {
    MALFORMED_UTF16,
    UNSUPPORTED_SCALAR,
    INVALID_PROTECTED_SPAN,
    NORMALIZATION_COLLISION,
    UNRESOLVED_REVIEW,
    SERIALIZER_SCALAR_MISMATCH,
    SERIALIZER_UNSUPPORTED_SCALAR,
    CANONICAL_REVISION_MISMATCH,
}

data class ProtectedSpan(
    val startUtf16: Int,
    val endUtf16Exclusive: Int,
    val kind: ProtectedSpanKind,
    val userLocked: Boolean,
) {
    init {
        require(startUtf16 >= 0 && endUtf16Exclusive > startUtf16) { "INVALID_PROTECTED_SPAN" }
    }

    fun contains(index: Int): Boolean = index in startUtf16 until endUtf16Exclusive
}

/**
 * Session-only input. Rich alternatives are accepted solely so the caller can attest that they were
 * discarded; they are never copied into canonical output, findings, failures, or trace-like summaries.
 */
@ConsistentCopyVisibility
data class TextProcessingInput private constructor(
    val visibleText: String,
    val sourceKind: TextSourceKind,
    val explicitProtectedSpans: ImmutableList<ProtectedSpan>,
    val richRepresentationPresent: Boolean,
    val hiddenAlternativePresent: Boolean,
    val visualWrapOffsetsUtf16: ImmutableList<Int>,
) {
    companion object {
        fun create(
            visibleText: String,
            sourceKind: TextSourceKind = TextSourceKind.PLAIN_TEXT,
            explicitProtectedSpans: Iterable<ProtectedSpan> = emptyList(),
            richRepresentationPresent: Boolean = false,
            hiddenAlternativePresent: Boolean = false,
            visualWrapOffsetsUtf16: Iterable<Int> = emptyList(),
        ): TextProcessingInput {
            val spans = explicitProtectedSpans.sortedBy { it.startUtf16 }.toImmutableList()
            require(spans.all { it.endUtf16Exclusive <= visibleText.length }) { "INVALID_PROTECTED_SPAN" }
            require(spans.zipWithNext().all { (left, right) -> left.endUtf16Exclusive <= right.startUtf16 }) {
                "OVERLAPPING_PROTECTED_SPAN"
            }
            val wraps = visualWrapOffsetsUtf16.distinct().sorted().toImmutableList()
            require(wraps.all { it in visibleText.indices && visibleText[it] == '\n' }) { "INVALID_VISUAL_WRAP" }
            return TextProcessingInput(
                visibleText = visibleText,
                sourceKind = sourceKind,
                explicitProtectedSpans = spans,
                richRepresentationPresent = richRepresentationPresent,
                hiddenAlternativePresent = hiddenAlternativePresent,
                visualWrapOffsetsUtf16 = wraps,
            )
        }
    }
}

data class UnicodeScalarRecord(
    val scalarIndex: Int,
    val utf16Start: Int,
    val utf16EndExclusive: Int,
    val codePoint: Int?,
    val unicodeName: String,
    val generalCategory: Int,
    val script: ScriptCode,
    val combiningClass: Int,
    val malformedUtf16: Boolean,
)

data class GraphemeClusterRecord(
    val clusterIndex: Int,
    val utf16Start: Int,
    val utf16EndExclusive: Int,
    val scalarStart: Int,
    val scalarEndExclusive: Int,
)

data class NormalizationDelta(
    val form: TextNormalizationForm,
    val utf16Start: Int,
    val utf16EndExclusive: Int,
    val before: SensitiveRepresentation,
    val after: SensitiveRepresentation,
)

data class InvisibleCharacterRecord(
    val scalarIndex: Int,
    val utf16Start: Int,
    val codePoint: Int,
    val defaultIgnorable: Boolean,
    val bidiControl: Boolean,
    val variationSelector: Boolean,
    val languageSensitive: Boolean,
)

data class WhitespaceRecord(
    val scalarIndex: Int,
    val utf16Start: Int,
    val codePoint: Int,
    val lineTerminator: Boolean,
    val leading: Boolean,
    val trailing: Boolean,
    val repeated: Boolean,
)

data class PunctuationRecord(
    val scalarIndex: Int,
    val utf16Start: Int,
    val codePoint: Int,
    val semanticallySensitive: Boolean,
)

data class LineStructureRecord(
    val lineIndex: Int,
    val utf16Start: Int,
    val utf16EndExclusive: Int,
    val indentationScalars: Int,
    val blank: Boolean,
    val listMarker: String?,
    val visualWrapTerminator: Boolean,
)

data class TokenSecurityRecord(
    val utf16Start: Int,
    val utf16EndExclusive: Int,
    val token: SensitiveRepresentation,
    val scripts: ImmutableList<ScriptCode>,
    val confusableSkeleton: SensitiveRepresentation,
    val spoofChecks: Int,
    val restrictionLevel: String,
    val mixedScript: Boolean,
    val protectedSpan: Boolean,
)

data class LanguageResolution(
    val policy: LanguagePolicy,
    val confidenceClass: ConfidenceClass,
    val requiresReview: Boolean,
)

data class TextInspection(
    val scalarInventory: ImmutableList<UnicodeScalarRecord>,
    val graphemeClusters: ImmutableList<GraphemeClusterRecord>,
    val normalizationDeltas: ImmutableList<NormalizationDelta>,
    val invisibleCharacters: ImmutableList<InvisibleCharacterRecord>,
    val whitespace: ImmutableList<WhitespaceRecord>,
    val punctuation: ImmutableList<PunctuationRecord>,
    val lineStructure: ImmutableList<LineStructureRecord>,
    val tokens: ImmutableList<TokenSecurityRecord>,
    val protectedSpans: ImmutableList<ProtectedSpan>,
    val languageResolution: LanguageResolution,
    val findings: ImmutableList<Finding>,
    val reviewGates: ImmutableList<TextReviewGate>,
    val failures: ImmutableList<TextFailure>,
) {
    val isWellFormed: Boolean get() = failures.none { it.code == TextFailureCode.MALFORMED_UTF16 }
}

data class TextReviewGate(
    val code: TextReviewCode,
    val decisionId: app.shareguard.core.model.DecisionId,
    val findingIds: ImmutableList<FindingId>,
    val blocking: Boolean,
    val status: ReviewStatus,
    val summary: SafeSummary,
)

data class TextFailure(
    val code: TextFailureCode,
    val blockId: BlockId,
    val sourceLocation: SourceLocation?,
)

data class TextChangeProposal(
    val blockId: BlockId,
    val findingIds: ImmutableList<FindingId>,
    val before: SensitiveRepresentation?,
    val after: SensitiveRepresentation?,
    val reviewCode: TextReviewCode?,
    val applied: Boolean,
)

data class TextCanonicalizationPolicy(
    val normalizationForm: TextNormalizationForm = TextNormalizationForm.NFKC,
    val whitespaceProfile: TextWhitespaceProfile = TextWhitespaceProfile.DOCUMENT,
    val punctuationProfile: TextPunctuationProfile = TextPunctuationProfile.ASCII_STABLE,
    val languagePolicy: LanguagePolicy = LanguagePolicy.create(),
    val preserveEmojiJoiners: Boolean = true,
)

class TextReviewApprovals private constructor(
    approvedCodes: Set<TextReviewCode>,
    confusableTokenReplacements: Map<String, String>,
    approvedVisualWrapOffsetsUtf16: Set<Int>,
) {
    val approvedCodes: Set<TextReviewCode> = approvedCodes.toSet()
    val confusableTokenReplacements: Map<String, String> = confusableTokenReplacements.toMap()
    val approvedVisualWrapOffsetsUtf16: Set<Int> = approvedVisualWrapOffsetsUtf16.toSet()

    fun isApproved(code: TextReviewCode): Boolean = code in approvedCodes

    companion object {
        fun none(): TextReviewApprovals = TextReviewApprovals(emptySet(), emptyMap(), emptySet())

        fun create(
            approvedCodes: Set<TextReviewCode> = emptySet(),
            confusableTokenReplacements: Map<String, String> = emptyMap(),
            approvedVisualWrapOffsetsUtf16: Set<Int> = emptySet(),
        ): TextReviewApprovals = TextReviewApprovals(
            approvedCodes = approvedCodes,
            confusableTokenReplacements = confusableTokenReplacements,
            approvedVisualWrapOffsetsUtf16 = approvedVisualWrapOffsetsUtf16,
        )
    }
}

data class LockedCanonicalText(
    val canonicalRevision: CanonicalRevision,
    val canonicalText: String,
)

data class TextCanonicalizationResult(
    val canonicalRevision: CanonicalRevision,
    val canonicalText: String,
    val inspection: TextInspection,
    val findings: ImmutableList<Finding>,
    val decisions: ImmutableList<UserDecision>,
    val proposals: ImmutableList<TextChangeProposal>,
    val ledgerEntries: ImmutableList<ChangeEntry>,
    val reviewGates: ImmutableList<TextReviewGate>,
    val failures: ImmutableList<TextFailure>,
    val locked: LockedCanonicalText?,
) {
    val requiresReview: Boolean get() = reviewGates.any { it.blocking && it.status != ReviewStatus.APPROVED }
}

internal fun textSourceLocation(
    scalarStart: Int,
    scalarEndExclusive: Int,
    code: String,
): SourceLocation = SourceLocation(
    scalarStart = scalarStart,
    scalarEndExclusive = scalarEndExclusive,
    safeDescription = SafeSummary(code),
)
