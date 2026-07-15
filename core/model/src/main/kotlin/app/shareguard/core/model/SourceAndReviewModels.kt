package app.shareguard.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Session-only source descriptions. They are deliberately absent from [SavedResult]. */
@Serializable
sealed interface SourceModel {
    val internalId: SourceId
    val importMethod: ImportMethod
    val importWarnings: ImmutableList<SafeSummary>
}

@Serializable
@SerialName("text_source")
data class TextSource(
    override val internalId: SourceId,
    val sourceMime: MimeType,
    override val importMethod: ImportMethod,
    val plainText: String,
    val originalLengthMetadata: Int,
    val sourceStylePresent: Boolean,
    override val importWarnings: ImmutableList<SafeSummary> = ImmutableList.empty(),
) : SourceModel {
    init {
        require(sourceMime.value.startsWith("text/")) { "TextSource requires a text MIME type" }
        require(originalLengthMetadata >= 0) { "Original length cannot be negative" }
    }

    companion object {
        fun snapshot(
            internalId: SourceId,
            sourceMime: MimeType,
            importMethod: ImportMethod,
            plainText: String,
            originalLengthMetadata: Int = plainText.length,
            sourceStylePresent: Boolean = false,
            importWarnings: Iterable<SafeSummary> = emptyList(),
        ): TextSource = TextSource(
            internalId = internalId,
            sourceMime = sourceMime,
            importMethod = importMethod,
            plainText = plainText,
            originalLengthMetadata = originalLengthMetadata,
            sourceStylePresent = sourceStylePresent,
            importWarnings = importWarnings.toImmutableList(),
        )
    }
}

@Serializable
@SerialName("image_source")
data class ImageSource(
    override val internalId: SourceId,
    val sourceMimeClaim: MimeType?,
    val detectedFormat: MimeType,
    val internalSourceHandle: SourceHandle,
    override val importMethod: ImportMethod,
    val byteLengthMetadata: ByteCount,
    val decoderWarnings: ImmutableList<SafeSummary> = ImmutableList.empty(),
    val untrustedFilenameDigest: ContentDigest? = null,
    override val importWarnings: ImmutableList<SafeSummary> = ImmutableList.empty(),
) : SourceModel {
    init {
        require(detectedFormat.value.startsWith("image/")) { "ImageSource requires a detected image format" }
    }

    companion object {
        fun snapshot(
            internalId: SourceId,
            sourceMimeClaim: MimeType?,
            detectedFormat: MimeType,
            internalSourceHandle: SourceHandle,
            importMethod: ImportMethod,
            byteLengthMetadata: ByteCount,
            decoderWarnings: Iterable<SafeSummary> = emptyList(),
            untrustedFilenameDigest: ContentDigest? = null,
            importWarnings: Iterable<SafeSummary> = emptyList(),
        ): ImageSource = ImageSource(
            internalId = internalId,
            sourceMimeClaim = sourceMimeClaim,
            detectedFormat = detectedFormat,
            internalSourceHandle = internalSourceHandle,
            importMethod = importMethod,
            byteLengthMetadata = byteLengthMetadata,
            decoderWarnings = decoderWarnings.toImmutableList(),
            untrustedFilenameDigest = untrustedFilenameDigest,
            importWarnings = importWarnings.toImmutableList(),
        )
    }
}

@Serializable
data class SourceLocation(
    val scalarStart: Int? = null,
    val scalarEndExclusive: Int? = null,
    val regionId: ImageRegionId? = null,
    val safeDescription: SafeSummary,
) {
    init {
        require((scalarStart == null) == (scalarEndExclusive == null)) {
            "Scalar range requires both endpoints"
        }
        if (scalarStart != null && scalarEndExclusive != null) {
            require(scalarStart >= 0 && scalarEndExclusive >= scalarStart) { "Invalid scalar range" }
        }
    }
}

@Serializable
data class CanonicalLocation(
    val blockId: CanonicalBlockId,
    val textRunIndex: Int? = null,
) {
    init { require(textRunIndex == null || textRunIndex >= 0) { "Text run index cannot be negative" } }
}

@Serializable
data class Finding(
    val findingId: FindingId,
    val blockId: BlockId,
    val category: FindingCategory,
    val severity: Severity,
    val confidenceClass: ConfidenceClass,
    val sourceLocation: SourceLocation?,
    val canonicalLocation: CanonicalLocation?,
    val title: SafeSummary,
    val explanation: SafeSummary,
    val suggestedAction: DecisionAction?,
    val semanticRisk: SemanticRisk,
    val requiresUserDecision: Boolean,
    val status: FindingStatus,
    val evidenceSummary: SafeSummary,
) {
    init {
        if (requiresUserDecision) {
            require(status != FindingStatus.RESOLVED || suggestedAction != null) {
                "Resolved decision findings require a recorded action"
            }
        }
    }
}

@Serializable
data class UserDecision(
    val decisionId: DecisionId,
    val findingIds: ImmutableList<FindingId>,
    val action: DecisionAction,
    val status: DecisionStatus,
    val semanticImpact: SemanticImpact,
    val rationale: SafeSummary,
    val canonicalRevision: CanonicalRevision?,
) {
    init {
        require(findingIds.isNotEmpty()) { "A decision must address at least one finding" }
        require(findingIds.distinct().size == findingIds.size) { "Decision finding references must be unique" }
        if (status == DecisionStatus.APPROVED) {
            require(canonicalRevision != null) { "Approved decisions require revision linkage" }
        }
    }

    companion object {
        fun create(
            decisionId: DecisionId,
            findingIds: Iterable<FindingId>,
            action: DecisionAction,
            status: DecisionStatus,
            semanticImpact: SemanticImpact,
            rationale: SafeSummary,
            canonicalRevision: CanonicalRevision? = null,
        ): UserDecision = UserDecision(
            decisionId = decisionId,
            findingIds = findingIds.toImmutableList(),
            action = action,
            status = status,
            semanticImpact = semanticImpact,
            rationale = rationale,
            canonicalRevision = canonicalRevision,
        )
    }
}

@Serializable
data class ReviewLink(
    val decisionId: DecisionId,
    val findingIds: ImmutableList<FindingId>,
    val status: ReviewStatus,
) {
    init {
        require(findingIds.isNotEmpty()) { "Review link requires findings" }
        require(findingIds.distinct().size == findingIds.size) { "Review finding references must be unique" }
    }
}

@Serializable
data class PipelineWarning(
    val blockId: BlockId?,
    val code: String,
    val summary: SafeSummary,
) {
    init { require(OPAQUE_WARNING_CODE.matches(code)) { "Warning code must be content-free" } }
}

private val OPAQUE_WARNING_CODE = Regex("[A-Z][A-Z0-9_]{1,63}")
