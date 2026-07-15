package app.shareguard.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ExecutedBlockManifestEntry(
    val blockId: BlockId,
    val blockVersion: BlockVersion,
    val executionRevision: ExecutionRevision,
    val order: Int,
) {
    init { require(order >= 0) { "Block manifest order cannot be negative" } }
}

@Serializable
data class DeclaredResidual(
    val code: String,
    val summary: SafeSummary,
    val findingIds: ImmutableList<FindingId> = ImmutableList.empty(),
) {
    init { require(CONTENT_FREE_CODE.matches(code)) { "Residual code must be content-free" } }
}

@Serializable
data class VerificationFailure(
    val verificationId: VerificationId,
    val code: String,
    val summary: SafeSummary,
) {
    init { require(CONTENT_FREE_CODE.matches(code)) { "Failure code must be content-free" } }
}

@Serializable
data class VerificationResult(
    val verificationId: VerificationId,
    val type: VerificationType,
    val status: VerificationStatus,
    val artifactRevision: ArtifactRevision,
    val required: Boolean,
    val summary: SafeSummary,
    val residuals: ImmutableList<DeclaredResidual> = ImmutableList.empty(),
    val failures: ImmutableList<VerificationFailure> = ImmutableList.empty(),
) {
    init {
        if (status == VerificationStatus.PASS_WITH_DECLARED_RESIDUAL) {
            require(residuals.isNotEmpty()) { "PASS_WITH_DECLARED_RESIDUAL requires a declared residual" }
        } else {
            require(residuals.isEmpty()) { "Residuals require PASS_WITH_DECLARED_RESIDUAL" }
        }
        if (status == VerificationStatus.FAIL || status == VerificationStatus.ERROR) {
            require(failures.isNotEmpty()) { "Failed/error verification requires failure details" }
        }
        require(failures.all { it.verificationId == verificationId }) {
            "Verification failures must link to their result"
        }
    }

    val satisfiesRequirement: Boolean
        get() = !required || status in setOf(
            VerificationStatus.PASS,
            VerificationStatus.PASS_WITH_DECLARED_RESIDUAL,
            VerificationStatus.NOT_APPLICABLE,
        )
}

@Serializable
data class MetadataInventoryEntry(
    val fieldName: String,
    val allowed: Boolean,
    val contentFreeValueClass: String? = null,
) {
    init {
        require(fieldName.isNotBlank()) { "Metadata field name cannot be blank" }
        require(contentFreeValueClass?.contains('\u0000') != true) { "Metadata value class contains NUL" }
    }
}

@Serializable
data class VerificationFinding(
    val findingId: FindingId,
    val category: FindingCategory,
    val status: FindingStatus,
    val summary: SafeSummary,
)

@Serializable
data class VerificationReport(
    val reportVersion: SchemaVersion,
    val artifactRevision: ArtifactRevision,
    val canonicalRevision: CanonicalRevision,
    val executedBlockManifest: ImmutableList<ExecutedBlockManifestEntry>,
    val results: ImmutableList<VerificationResult>,
    val finalMetadataInventory: ImmutableList<MetadataInventoryEntry>,
    val finalUnicodeFindings: ImmutableList<VerificationFinding>,
    val finalUrlFindings: ImmutableList<VerificationFinding>,
    val ocrRoundTripFindings: ImmutableList<VerificationFinding>,
    val sourceReferenceAudit: VerificationResult,
    val sourcePixelRegionList: ImmutableList<ImageRegionId>,
    val unresolvedFindingList: ImmutableList<FindingId>,
    val assuranceClass: AssuranceClass,
    val assuranceRationale: SafeSummary,
    val verificationFailures: ImmutableList<VerificationFailure>,
    val generatedAtSessionTime: WallClockInstant,
) {
    init {
        require(executedBlockManifest.map { it.order }.distinct().size == executedBlockManifest.size) {
            "Executed block manifest orders must be unique"
        }
        require(executedBlockManifest.zipWithNext().all { (a, b) -> a.order < b.order }) {
            "Executed block manifest must be stored in execution order"
        }
        require(results.map { it.verificationId }.distinct().size == results.size) {
            "Verification result IDs must be unique"
        }
        require(results.map { it.type }.distinct().size == results.size) {
            "Verification result types must be unique"
        }
        require(sourceReferenceAudit.type == VerificationType.SOURCE_REFERENCE) {
            "sourceReferenceAudit must be SOURCE_REFERENCE verification"
        }
        require((results + sourceReferenceAudit).all { it.artifactRevision == artifactRevision }) {
            "Verification result revision mismatch"
        }
        require(verificationFailures.all { failure ->
            (results + sourceReferenceAudit).any { it.verificationId == failure.verificationId }
        }) { "Report failure references an unknown verifier" }
        require(sourcePixelRegionList.distinct().size == sourcePixelRegionList.size) {
            "Source pixel region list cannot contain duplicates"
        }
        require(unresolvedFindingList.distinct().size == unresolvedFindingList.size) {
            "Unresolved finding list cannot contain duplicates"
        }
        if (assuranceClass != AssuranceClass.AS_0_UNVERIFIED) {
            require((results + sourceReferenceAudit).all { it.satisfiesRequirement }) {
                "Verified assurance requires every required verifier to pass"
            }
            require(verificationFailures.isEmpty()) { "Verified assurance cannot contain verification failures" }
        }
        if (assuranceClass == AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE) {
            require(sourcePixelRegionList.isEmpty()) { "AS-4 cannot retain source pixel regions" }
        }
        if (assuranceClass == AssuranceClass.AS_3_REBUILT_WITH_SOURCE_REGIONS) {
            require(sourcePixelRegionList.isNotEmpty()) { "AS-3 requires declared source pixel regions" }
        }
    }

    val requiredVerificationPassed: Boolean
        get() = (results + sourceReferenceAudit).all { it.satisfiesRequirement }

    fun compactSummary(): VerificationSummary = VerificationSummary(
        reportVersion = reportVersion,
        artifactRevision = artifactRevision,
        canonicalRevision = canonicalRevision,
        assuranceClass = assuranceClass,
        assuranceRationale = assuranceRationale,
        resultStatuses = (results + sourceReferenceAudit).map {
            VerificationStatusSummary(it.type, it.status)
        }.toImmutableList(),
        unresolvedFindingCount = unresolvedFindingList.size,
        retainedSourceRegionCount = sourcePixelRegionList.size,
    )
}

@Serializable
data class VerificationStatusSummary(
    val type: VerificationType,
    val status: VerificationStatus,
)

/** Safe, persistable subset of [VerificationReport]; it contains no source values or ledger before-values. */
@Serializable
data class VerificationSummary(
    val reportVersion: SchemaVersion,
    val artifactRevision: ArtifactRevision,
    val canonicalRevision: CanonicalRevision,
    val assuranceClass: AssuranceClass,
    val assuranceRationale: SafeSummary,
    val resultStatuses: ImmutableList<VerificationStatusSummary>,
    val unresolvedFindingCount: Int,
    val retainedSourceRegionCount: Int,
) {
    init {
        require(unresolvedFindingCount >= 0 && retainedSourceRegionCount >= 0) {
            "Verification summary counts cannot be negative"
        }
    }
}

private val CONTENT_FREE_CODE = Regex("[A-Z][A-Z0-9_]{1,63}")
