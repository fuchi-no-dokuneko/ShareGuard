package app.shareguard.block.verify

import app.shareguard.core.model.ArtifactRevision
import app.shareguard.core.model.ArtifactReference
import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.BlockId
import app.shareguard.core.model.BlockVersion
import app.shareguard.core.model.CanonicalBlockId
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.ChangeId
import app.shareguard.core.model.ChangeLedger
import app.shareguard.core.model.ContentDigest
import app.shareguard.core.model.DependencyType
import app.shareguard.core.model.ExecutedBlockManifestEntry
import app.shareguard.core.model.ExecutionContext
import app.shareguard.core.model.ImageRegionId
import app.shareguard.core.model.ImageRegionPolicy
import app.shareguard.core.model.ImmutableList
import app.shareguard.core.model.MimeType
import app.shareguard.core.model.OutputArtifact
import app.shareguard.core.model.OutputBundle
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.VerificationReport
import app.shareguard.core.model.VerificationSummary
import app.shareguard.core.model.VerificationType
import app.shareguard.core.model.WallClockInstant
import app.shareguard.core.model.toImmutableList
import app.shareguard.core.pipeline.PipelinePreset

private val CONTENT_FREE_CODE = Regex("[A-Z][A-Z0-9_]{1,63}")

internal fun requireContentFreeCode(value: String, label: String = "code") {
    require(CONTENT_FREE_CODE.matches(value)) { "$label must be a content-free code" }
}

/** Result from an independent or platform-backed verifier. It never treats missing evidence as success. */
sealed interface ProviderResult<out T> {
    data class Completed<T>(val evidence: T) : ProviderResult<T>

    data class NotRun(val reasonCode: String) : ProviderResult<Nothing> {
        init { requireContentFreeCode(reasonCode, "not-run reason") }
    }

    data class Error(val reasonCode: String) : ProviderResult<Nothing> {
        init { requireContentFreeCode(reasonCode, "provider error") }
    }
}

/** Exact bytes reopened from the authoritative managed-artifact location. */
class ReopenedArtifact private constructor(
    val artifactRevision: ArtifactRevision,
    val canonicalRevision: CanonicalRevision,
    val detectedMimeType: MimeType,
    val digest: ContentDigest,
    val appPrivateLocation: Boolean,
    bytes: ByteArray,
) {
    private val snapshot = bytes.copyOf()

    val byteCount: Long get() = snapshot.size.toLong()

    fun bytesCopy(): ByteArray = snapshot.copyOf()

    companion object {
        fun create(
            artifactRevision: ArtifactRevision,
            canonicalRevision: CanonicalRevision,
            detectedMimeType: MimeType,
            digest: ContentDigest,
            appPrivateLocation: Boolean,
            bytes: ByteArray,
        ): ReopenedArtifact = ReopenedArtifact(
            artifactRevision = artifactRevision,
            canonicalRevision = canonicalRevision,
            detectedMimeType = detectedMimeType,
            digest = digest,
            appPrivateLocation = appPrivateLocation,
            bytes = bytes,
        )
    }
}

fun interface ArtifactReopener {
    suspend fun reopen(artifact: OutputArtifact): ProviderResult<ReopenedArtifact>
}

data class ImageArtifactPolicy(
    val allowedMimeTypes: Set<MimeType>,
    val allowedMetadataFieldCodes: Set<String>,
    val allowedContainerChunkCodes: Set<String>,
    val allowedChannelModelCodes: Set<String>,
    val allowedAlphaModelCodes: Set<String>,
    val allowedColourProfileCodes: Set<String>,
    val maximumEmbeddedThumbnails: Int = 0,
    val requireIndependentDecode: Boolean = true,
) {
    init {
        require(allowedMimeTypes.isNotEmpty()) { "At least one image MIME type must be allowed" }
        require(maximumEmbeddedThumbnails >= 0) { "Thumbnail limit cannot be negative" }
        (allowedMetadataFieldCodes + allowedContainerChunkCodes + allowedChannelModelCodes +
            allowedAlphaModelCodes + allowedColourProfileCodes).forEach {
            requireContentFreeCode(it, "image policy code")
        }
    }

    companion object {
        fun strictPng(): ImageArtifactPolicy = ImageArtifactPolicy(
            allowedMimeTypes = setOf(MimeType("image/png")),
            allowedMetadataFieldCodes = emptySet(),
            allowedContainerChunkCodes = setOf("PNG_IHDR", "PNG_IDAT", "PNG_IEND"),
            allowedChannelModelCodes = setOf("RGBA_8", "RGB_8"),
            allowedAlphaModelCodes = setOf("OPAQUE", "FLATTENED"),
            allowedColourProfileCodes = setOf("SRGB_CANONICAL", "NONE"),
        )
    }
}

/** Content-free factual inventory produced by an actual final-image parser. */
data class FinalImageInspection(
    val artifactRevision: ArtifactRevision,
    val detectedMimeType: MimeType,
    val independentlyDecodes: Boolean,
    val metadataFieldCodes: Set<String>,
    val containerChunkCodes: Set<String>,
    val embeddedThumbnailCount: Int,
    val channelModelCode: String,
    val alphaModelCode: String,
    val colourProfileCode: String,
    val freshlyAllocatedCanvas: Boolean,
    val bundledRendererAssetsOnly: Boolean,
) {
    init {
        require(embeddedThumbnailCount >= 0) { "Thumbnail count cannot be negative" }
        (metadataFieldCodes + containerChunkCodes + setOf(
            channelModelCode,
            alphaModelCode,
            colourProfileCode,
        )).forEach { requireContentFreeCode(it, "image inspection code") }
    }
}

fun interface FinalImageInspector {
    suspend fun inspect(
        artifact: ReopenedArtifact,
        policy: ImageArtifactPolicy,
    ): ProviderResult<FinalImageInspection>
}

data class OcrRoundTripInspection(
    val artifactRevision: ArtifactRevision,
    val recognizedText: String,
    val recognizedReadingOrder: ImmutableList<CanonicalBlockId>,
    val differenceCodes: ImmutableList<String> = ImmutableList.empty(),
) {
    init {
        require(!recognizedText.contains('\u0000')) { "OCR text contains NUL" }
        differenceCodes.forEach { requireContentFreeCode(it, "OCR difference code") }
    }

    companion object {
        fun create(
            artifactRevision: ArtifactRevision,
            recognizedText: String,
            recognizedReadingOrder: Iterable<CanonicalBlockId>,
            differenceCodes: Iterable<String> = emptyList(),
        ): OcrRoundTripInspection = OcrRoundTripInspection(
            artifactRevision,
            recognizedText,
            recognizedReadingOrder.toImmutableList(),
            differenceCodes.toImmutableList(),
        )
    }
}

fun interface OcrRoundTripInspector {
    suspend fun inspect(
        artifact: ReopenedArtifact,
        approvedCanonicalText: String,
        approvedReadingOrder: ImmutableList<CanonicalBlockId>,
    ): ProviderResult<OcrRoundTripInspection>
}

data class MachineReadableCode(
    val symbologyCode: String,
    val decodedValue: String,
) {
    init {
        requireContentFreeCode(symbologyCode, "symbology code")
        require(!decodedValue.contains('\u0000')) { "Decoded value contains NUL" }
    }
}

data class BarcodeInspection(
    val artifactRevision: ArtifactRevision,
    val codes: ImmutableList<MachineReadableCode>,
) {
    companion object {
        fun create(
            artifactRevision: ArtifactRevision,
            codes: Iterable<MachineReadableCode>,
        ): BarcodeInspection = BarcodeInspection(artifactRevision, codes.toImmutableList())
    }
}

fun interface BarcodeInspector {
    suspend fun inspect(artifact: ReopenedArtifact): ProviderResult<BarcodeInspection>
}

data class RegionCoverageInspection(
    val artifactRevision: ArtifactRevision,
    val terminalPolicies: Map<ImageRegionId, ImageRegionPolicy>,
    val sourcePixelOperationRegionIds: Set<ImageRegionId>,
) {
    init {
        require(terminalPolicies.keys.size == terminalPolicies.size) { "Region policy IDs must be unique" }
    }
}

fun interface RegionCoverageInspector {
    suspend fun inspect(artifact: ReopenedArtifact): ProviderResult<RegionCoverageInspection>
}

data class IdempotenceInspection(
    val canonicalRevision: CanonicalRevision,
    val secondPassText: String,
    val secondPassChangeCount: Int,
) {
    init {
        require(!secondPassText.contains('\u0000')) { "Second-pass text contains NUL" }
        require(secondPassChangeCount >= 0) { "Second-pass change count cannot be negative" }
    }
}

fun interface IdempotenceInspector {
    suspend fun inspect(
        approvedCanonicalText: String,
        canonicalRevision: CanonicalRevision,
    ): ProviderResult<IdempotenceInspection>
}

/** Runtime facts captured by instrumentation or the Android integration layer. */
data class RuntimePrivacyInspection(
    val networkEvidenceCaptured: Boolean,
    val networkAttemptCount: Int,
    val onDemandModelDownloadCount: Int,
    val declaredPermissionNames: Set<String>,
    val broadStoragePermissionPresent: Boolean,
    val appPrivateArtifactRoot: Boolean,
    val cleanupCompleted: Boolean,
    val outgoingMimeMatchesArtifact: Boolean,
    val outgoingDigestMatchesArtifact: Boolean,
    val outgoingContentUriAppScoped: Boolean,
    val temporaryReadGrantLeastPrivilege: Boolean,
) {
    init {
        require(networkAttemptCount >= 0 && onDemandModelDownloadCount >= 0) {
            "Runtime counts cannot be negative"
        }
        require(declaredPermissionNames.none { it.contains('\u0000') }) {
            "Permission name contains NUL"
        }
    }

    val internetPermissionPresent: Boolean
        get() = declaredPermissionNames.any {
            it == "android.permission.INTERNET" || it.endsWith(".permission.INTERNET")
        }
}

fun interface RuntimePrivacyInspector {
    suspend fun inspect(): ProviderResult<RuntimePrivacyInspection>
}

/** Counts only; implementations must never return the matched source/output content. */
data class SensitiveLoggingInspection(
    val staticScanCompleted: Boolean,
    val dynamicCanarySessionCompleted: Boolean,
    val inspectedEventCount: Int,
    val prohibitedPayloadMatchCount: Int,
    val persistentProductionTracingEnabled: Boolean,
) {
    init {
        require(inspectedEventCount >= 0 && prohibitedPayloadMatchCount >= 0) {
            "Logging audit counts cannot be negative"
        }
    }
}

fun interface SensitiveLoggingInspector {
    suspend fun inspect(): ProviderResult<SensitiveLoggingInspection>
}

data class VerificationProviders(
    val artifactReopener: ArtifactReopener? = null,
    val finalImageInspector: FinalImageInspector? = null,
    val ocrRoundTripInspector: OcrRoundTripInspector? = null,
    val barcodeInspector: BarcodeInspector? = null,
    val regionCoverageInspector: RegionCoverageInspector? = null,
    val idempotenceInspector: IdempotenceInspector? = null,
    val runtimePrivacyInspector: RuntimePrivacyInspector? = null,
    val sensitiveLoggingInspector: SensitiveLoggingInspector? = null,
)

data class AppliedTransformation(
    val blockId: BlockId,
    val blockVersion: BlockVersion,
    val canonicalRevision: CanonicalRevision,
    val changeIds: Set<ChangeId>,
) {
    init { require(changeIds.isNotEmpty()) { "Applied transformations require ledger change IDs" } }
}

data class DependencyExpectation(
    val type: DependencyType,
    val canonicalBlockId: CanonicalBlockId? = null,
    val imageRegionId: ImageRegionId? = null,
    val decisionIdValue: String? = null,
) {
    init {
        require(decisionIdValue == null || Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(decisionIdValue)) {
            "Decision expectation must be an opaque ID"
        }
    }
}

data class DependencyVerificationScope(
    val expectedEntries: Set<DependencyExpectation>,
    val exercisedTypes: Set<DependencyType>,
    val platformLimitationCodes: Set<String>,
) {
    init { platformLimitationCodes.forEach { requireContentFreeCode(it, "platform limitation code") } }
}

enum class SourceCanaryKind {
    SOURCE_URI,
    SOURCE_FILENAME,
    SOURCE_PATH,
    PROVIDER_AUTHORITY,
    SESSION_REFERENCE,
    STABLE_SEED_REFERENCE,
    SOURCE_METADATA,
    URL_REMOVED_COMPONENT,
    OCR_CROP,
}

class SourceCanary private constructor(
    val kind: SourceCanaryKind,
    secret: ByteArray,
) {
    private val snapshot = secret.copyOf()

    internal fun secretCopy(): ByteArray = snapshot.copyOf()

    companion object {
        fun utf8(kind: SourceCanaryKind, secret: String): SourceCanary {
            require(secret.isNotEmpty() && !secret.contains('\u0000')) { "Canary must be non-empty and NUL-free" }
            return SourceCanary(kind, secret.toByteArray(Charsets.UTF_8))
        }

        fun bytes(kind: SourceCanaryKind, secret: ByteArray): SourceCanary {
            require(secret.isNotEmpty()) { "Canary must be non-empty" }
            return SourceCanary(kind, secret)
        }
    }
}

enum class ReferenceSurfaceKind {
    OUTGOING_INTENT,
    OUTPUT_FILENAME,
    ARTIFACT_METADATA,
    RUNTIME_OBJECT_GRAPH,
    PERSISTABLE_SUMMARY,
    PREVIEW,
    SHARE_DESCRIPTOR,
}

class ReferenceSurface private constructor(
    val kind: ReferenceSurfaceKind,
    payload: ByteArray,
) {
    private val snapshot = payload.copyOf()

    internal fun payloadCopy(): ByteArray = snapshot.copyOf()

    companion object {
        fun utf8(kind: ReferenceSurfaceKind, payload: String): ReferenceSurface =
            ReferenceSurface(kind, payload.toByteArray(Charsets.UTF_8))

        fun bytes(kind: ReferenceSurfaceKind, payload: ByteArray): ReferenceSurface =
            ReferenceSurface(kind, payload)
    }
}

data class SemanticDiffApproval(
    val canonicalRevision: CanonicalRevision,
    val approvedChangeIds: Set<ChangeId>,
)

data class AssuranceConsequenceApproval(
    val shownCeiling: AssuranceClass,
    val userAcknowledged: Boolean,
)

data class ReviewEvidence(
    val approvedReadingOrder: ImmutableList<CanonicalBlockId>? = null,
    val approvedRegionPolicies: Map<ImageRegionId, ImageRegionPolicy>? = null,
    val semanticDiffApproval: SemanticDiffApproval? = null,
    val assuranceConsequenceApproval: AssuranceConsequenceApproval? = null,
) {
    companion object {
        fun create(
            approvedReadingOrder: Iterable<CanonicalBlockId>? = null,
            approvedRegionPolicies: Map<ImageRegionId, ImageRegionPolicy>? = null,
            semanticDiffApproval: SemanticDiffApproval? = null,
            assuranceConsequenceApproval: AssuranceConsequenceApproval? = null,
        ): ReviewEvidence = ReviewEvidence(
            approvedReadingOrder = approvedReadingOrder?.toImmutableList(),
            approvedRegionPolicies = approvedRegionPolicies?.toMap(),
            semanticDiffApproval = semanticDiffApproval,
            assuranceConsequenceApproval = assuranceConsequenceApproval,
        )
    }
}

data class FinalVerificationPolicy(
    val strictImageProfile: Boolean = true,
    val requireReleaseControls: Boolean = true,
    val requirePersistentReopen: Boolean = true,
    val requireExplicitPlatformLimitations: Boolean = true,
    val approvedUnicodeFindingCodes: Set<String> = emptySet(),
    val imagePolicy: ImageArtifactPolicy = ImageArtifactPolicy.strictPng(),
    val approvedMachineReadableCodes: Set<MachineReadableCode> = emptySet(),
) {
    init { approvedUnicodeFindingCodes.forEach { requireContentFreeCode(it, "Unicode approval code") } }
}

data class VerificationRequest(
    val preset: PipelinePreset,
    val context: ExecutionContext,
    val outputBundle: OutputBundle,
    val executedBlockManifest: ImmutableList<ExecutedBlockManifestEntry>,
    val changeLedger: ChangeLedger,
    val approvedCanonicalText: String?,
    val appliedTransformations: ImmutableList<AppliedTransformation>,
    val dependencyScope: DependencyVerificationScope,
    val sourceCanaries: ImmutableList<SourceCanary>,
    val referenceSurfaces: ImmutableList<ReferenceSurface>,
    val reviewEvidence: ReviewEvidence,
    val policy: FinalVerificationPolicy,
    val generatedAtSessionTime: WallClockInstant,
    val presentedAssuranceClass: AssuranceClass? = null,
) {
    init {
        require(!approvedCanonicalText.orEmpty().contains('\u0000')) { "Approved canonical text contains NUL" }
        require(outputBundle.verificationReport == null) { "Verification input bundle must not contain an old report" }
    }

    companion object {
        fun create(
            preset: PipelinePreset,
            context: ExecutionContext,
            outputBundle: OutputBundle,
            executedBlockManifest: Iterable<ExecutedBlockManifestEntry>,
            changeLedger: ChangeLedger,
            approvedCanonicalText: String?,
            appliedTransformations: Iterable<AppliedTransformation>,
            dependencyScope: DependencyVerificationScope,
            sourceCanaries: Iterable<SourceCanary> = emptyList(),
            referenceSurfaces: Iterable<ReferenceSurface> = emptyList(),
            reviewEvidence: ReviewEvidence = ReviewEvidence(),
            policy: FinalVerificationPolicy = FinalVerificationPolicy(),
            generatedAtSessionTime: WallClockInstant,
            presentedAssuranceClass: AssuranceClass? = null,
        ): VerificationRequest = VerificationRequest(
            preset = preset,
            context = context,
            outputBundle = outputBundle,
            executedBlockManifest = executedBlockManifest.toImmutableList(),
            changeLedger = changeLedger,
            approvedCanonicalText = approvedCanonicalText,
            appliedTransformations = appliedTransformations.toImmutableList(),
            dependencyScope = dependencyScope,
            sourceCanaries = sourceCanaries.toImmutableList(),
            referenceSurfaces = referenceSurfaces.toImmutableList(),
            reviewEvidence = reviewEvidence,
            policy = policy,
            generatedAtSessionTime = generatedAtSessionTime,
            presentedAssuranceClass = presentedAssuranceClass,
        )
    }
}

enum class ReviewAuditType(val blockId: String) {
    INVISIBLE_CHARACTER("REV-001"),
    CONFUSABLE_CHARACTER("REV-002"),
    OCR_AMBIGUITY("REV-003"),
    URL_POLICY("REV-004"),
    READING_ORDER("REV-005"),
    IMAGE_REGION_POLICY("REV-006"),
    SEMANTIC_DIFF("REV-007"),
    ASSURANCE_CONSEQUENCE("REV-008"),
}

data class ReviewAuditResult(
    val type: ReviewAuditType,
    val status: app.shareguard.core.model.VerificationStatus,
    val summaryCode: String,
    val affectedFindingCount: Int,
) {
    init {
        requireContentFreeCode(summaryCode, "review summary code")
        require(affectedFindingCount >= 0) { "Finding count cannot be negative" }
    }

    val blocksLock: Boolean
        get() = status !in setOf(
            app.shareguard.core.model.VerificationStatus.PASS,
            app.shareguard.core.model.VerificationStatus.PASS_WITH_DECLARED_RESIDUAL,
            app.shareguard.core.model.VerificationStatus.NOT_APPLICABLE,
        )
}

data class HumanReadableVerificationReport(
    val title: String,
    val assuranceLabel: String,
    val statusLines: ImmutableList<String>,
    val limitationLines: ImmutableList<String>,
) {
    fun asPlainText(): String = buildString {
        appendLine(title)
        appendLine(assuranceLabel)
        statusLines.forEach(::appendLine)
        limitationLines.forEach(::appendLine)
    }.trimEnd()
}

data class FinalVerificationOutcome(
    val report: VerificationReport,
    val persistableSummary: VerificationSummary,
    val humanReadableReport: HumanReadableVerificationReport,
    val reviewAudits: ImmutableList<ReviewAuditResult>,
    val canPersistVerifiedResult: Boolean,
    val canManagedShare: Boolean,
    val blockingVerificationTypes: ImmutableList<VerificationType>,
    val reopenedDigests: Map<ArtifactReference, ContentDigest>,
) {
    init {
        if (canPersistVerifiedResult || canManagedShare) {
            require(report.assuranceClass != AssuranceClass.AS_0_UNVERIFIED) {
                "Verified persistence/share requires nonzero assurance"
            }
            require(report.requiredVerificationPassed) {
                "Verified persistence/share requires all mandatory verification"
            }
        }
    }
}
