package app.shareguard.core.pipeline

import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.BlockId
import app.shareguard.core.model.BlockVersion
import app.shareguard.core.model.ExecutionContext
import app.shareguard.core.model.InputKind
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SchemaVersion
import app.shareguard.core.model.TransformationCategory
import app.shareguard.core.model.VerificationType
import kotlinx.serialization.Serializable

@Serializable
enum class PipelineStage {
    SESSION,
    INPUT,
    INSPECTION,
    NORMALIZATION,
    CANONICALIZATION,
    REVIEW,
    RENDERING,
    SERIALIZATION,
    DERIVATIVE,
    VERIFICATION,
    PERSISTENCE,
    EXPORT,
    LIFECYCLE,
}

@Serializable
enum class ResourceClass { LIGHT, CPU, MEMORY, IO, USER_INTERACTION, PLATFORM }

@Serializable
enum class OfflineCapability { OFFLINE, PLATFORM_MEDIATED }

@Serializable
enum class InvalidationKey {
    SOURCE,
    SOURCE_TYPE,
    LANGUAGE_POLICY,
    URL_POLICY,
    CANONICAL_DOCUMENT,
    CANONICAL_REVISION,
    RENDERER_SETTINGS,
    SOURCE_DEPENDENCY_MAP,
    ARTIFACT_BYTES,
    METADATA_ALLOWLIST,
    OUTPUT_MODE,
    SAVE_FILENAME,
    BLOCK_VERSION,
    ASSURANCE_POLICY,
    STORAGE_MIGRATION,
    SHARE_POLICY,
    DISPLAY_METADATA,
}

@Serializable
enum class PipelineCapability {
    SESSION,
    SOURCE_IMPORTED,
    SOURCE_TYPED,
    RESOURCE_APPROVED,
    SOURCE_SEALED,
    IMPORT_ANCHOR,
    TEXT_INSPECTED,
    IMAGE_INSPECTED,
    URLS_PARSED,
    CANONICAL_DOCUMENT,
    CANONICAL_DOCUMENT_APPROVED,
    CHANGE_LEDGER,
    CANONICAL_TEXT_LOCKED,
    TEXT_ARTIFACT,
    IMAGE_ARTIFACT,
    DERIVATIVE_ARTIFACT,
    OUTPUT_BUNDLE,
    FINAL_VERIFICATION,
    ASSURANCE_COMPUTED,
    VERIFICATION_REPORT,
    SAVED_RESULT,
    SAVED_PREVIEW,
    SHARE_READY,
    EXTERNAL_EXPORT_READY,
}

@Serializable
data class LicenceMetadata(
    val spdxExpression: String,
    val attributionNotice: String? = null,
) {
    init {
        require(spdxExpression.isNotBlank()) { "Licence SPDX expression cannot be blank" }
        require(spdxExpression.length <= 256) { "Licence SPDX expression is too long" }
        require(attributionNotice == null || attributionNotice.length <= 2_048) {
            "Licence attribution notice is too long"
        }
    }
}

@Serializable
data class PipelineBlockMetadata(
    val blockId: BlockId,
    val blockVersion: BlockVersion,
    val displayName: String,
    val description: String,
    val stage: PipelineStage,
    val acceptedInputKinds: List<InputKind>,
    val supportedOutputModes: List<OutputMode>,
    val inputPredicate: List<PipelineCapability>,
    val outputGuarantees: List<PipelineCapability>,
    val mandatory: Boolean,
    val conditional: Boolean,
    val deterministic: Boolean,
    val requiresReview: Boolean,
    val settingsSchemaVersion: SchemaVersion,
    val threatCoverage: List<String>,
    val invalidationKeys: List<InvalidationKey>,
    val verificationRequirements: List<VerificationType>,
    val resourceClass: ResourceClass,
    val offlineCapability: OfflineCapability,
    val transformationCategory: TransformationCategory?,
    val contentTransforming: Boolean,
    val finalSerialization: Boolean,
    val verificationBlock: Boolean,
    val sourceBlock: Boolean,
    val finalExportPreparation: Boolean,
    val builtIn: Boolean,
    val persistentLoggingAllowed: Boolean,
    val licenceMetadata: LicenceMetadata? = null,
) {
    init {
        require(displayName.isNotBlank()) { "Block display name cannot be blank" }
        require(description.isNotBlank()) { "Block description cannot be blank" }
        require(acceptedInputKinds.isNotEmpty()) { "Block must declare compatible input kinds" }
        require(supportedOutputModes.isNotEmpty()) { "Block must declare compatible output modes" }
        require(threatCoverage.isNotEmpty()) { "Block must declare threat coverage" }
        require(threatCoverage.all(CONTENT_FREE_CODE::matches)) { "Threat coverage must use content-free codes" }
        require(!(mandatory && conditional)) { "A block cannot be unconditionally mandatory and conditional" }
        require(!contentTransforming || transformationCategory != null) {
            "Content-transforming blocks require a transformation category"
        }
        require(!verificationBlock || stage == PipelineStage.VERIFICATION) {
            "Verification blocks must be in the verification stage"
        }
    }

    fun supports(inputKind: InputKind, outputMode: OutputMode): Boolean =
        inputKind in acceptedInputKinds && outputMode in supportedOutputModes
}

@Serializable
data class BlockReference(
    val blockId: BlockId,
    val blockVersion: BlockVersion,
)

@Serializable
data class BlockInspection(
    val reasonCode: String,
    val reviewRecommended: Boolean = false,
) {
    init { require(CONTENT_FREE_CODE.matches(reasonCode)) { "Inspection reason must be content-free" } }
}

@Serializable
enum class PlanDisposition { NO_CHANGE, APPLY, REVIEW_REQUIRED, RECOVERABLE_FAILURE, FATAL_FAILURE }

@Serializable
data class BlockPlan(
    val disposition: PlanDisposition,
    val reasonCode: String,
) {
    init { require(CONTENT_FREE_CODE.matches(reasonCode)) { "Plan reason must be content-free" } }
}

@Serializable
data class PhaseCheck(
    val passed: Boolean,
    val fatal: Boolean,
    val reasonCode: String,
) {
    init {
        require(CONTENT_FREE_CODE.matches(reasonCode)) { "Phase reason must be content-free" }
        require(!passed || !fatal) { "A passing phase cannot be fatal" }
    }

    companion object {
        fun pass(reasonCode: String = "PHASE_OK") = PhaseCheck(true, false, reasonCode)
        fun recoverable(reasonCode: String) = PhaseCheck(false, false, reasonCode)
        fun fatal(reasonCode: String) = PhaseCheck(false, true, reasonCode)
    }
}

data class BlockApplication(
    val candidateContext: ExecutionContext,
    val changed: Boolean,
    val reasonCode: String,
) {
    init { require(CONTENT_FREE_CODE.matches(reasonCode)) { "Application reason must be content-free" } }
}

@Serializable
data class BlockSummary(
    val reasonCode: String,
    val changed: Boolean,
) {
    init { require(CONTENT_FREE_CODE.matches(reasonCode)) { "Summary reason must be content-free" } }
}

@Serializable
data class VerificationHint(
    val verificationType: VerificationType,
    val reasonCode: String,
) {
    init { require(CONTENT_FREE_CODE.matches(reasonCode)) { "Verification hint must be content-free" } }
}

@Serializable
enum class CleanupReason { COMPLETED, REVIEW_PAUSED, RECOVERABLE_FAILURE, FATAL_FAILURE, CANCELLED }

interface PipelineBlock {
    val metadata: PipelineBlockMetadata

    suspend fun validateConfiguration(context: ExecutionContext): PhaseCheck

    suspend fun inspect(context: ExecutionContext): BlockInspection

    suspend fun planChanges(context: ExecutionContext, inspection: BlockInspection): BlockPlan

    suspend fun apply(context: ExecutionContext, plan: BlockPlan): BlockApplication

    suspend fun selfCheck(before: ExecutionContext, application: BlockApplication): PhaseCheck

    suspend fun commit(before: ExecutionContext, application: BlockApplication): ExecutionContext

    fun summarize(before: ExecutionContext, after: ExecutionContext): BlockSummary

    suspend fun cleanup(context: ExecutionContext, reason: CleanupReason)

    fun produceVerificationHints(context: ExecutionContext): List<VerificationHint>
}

abstract class BasePipelineBlock(
    final override val metadata: PipelineBlockMetadata,
) : PipelineBlock {
    override suspend fun validateConfiguration(context: ExecutionContext): PhaseCheck = PhaseCheck.pass()

    override suspend fun inspect(context: ExecutionContext): BlockInspection = BlockInspection("INSPECTION_COMPLETE")

    override suspend fun planChanges(context: ExecutionContext, inspection: BlockInspection): BlockPlan =
        BlockPlan(PlanDisposition.NO_CHANGE, "NO_CHANGE_REQUIRED")

    override suspend fun apply(context: ExecutionContext, plan: BlockPlan): BlockApplication =
        BlockApplication(context, changed = false, reasonCode = "NO_CHANGE_APPLIED")

    override suspend fun selfCheck(before: ExecutionContext, application: BlockApplication): PhaseCheck =
        PhaseCheck.pass("SELF_CHECK_PASSED")

    override suspend fun commit(before: ExecutionContext, application: BlockApplication): ExecutionContext {
        val candidate = application.candidateContext
        require(candidate.sessionId == before.sessionId) { "A block cannot replace the active session" }
        require(candidate.workflowId == before.workflowId) { "A block cannot replace the active workflow" }
        return if (candidate.executionRevision.value > before.executionRevision.value) {
            candidate
        } else {
            candidate.copy(executionRevision = before.executionRevision.next())
        }
    }

    override fun summarize(before: ExecutionContext, after: ExecutionContext): BlockSummary =
        BlockSummary("BLOCK_COMPLETE", changed = after.executionRevision != before.executionRevision)

    override suspend fun cleanup(context: ExecutionContext, reason: CleanupReason) = Unit

    override fun produceVerificationHints(context: ExecutionContext): List<VerificationHint> =
        metadata.verificationRequirements.map { VerificationHint(it, "DECLARED_VERIFICATION_REQUIREMENT") }
}

@Serializable
data class ReviewRequest(
    val blockReference: BlockReference,
    val reasonCode: String,
    val executionRevision: Long,
) {
    init { require(CONTENT_FREE_CODE.matches(reasonCode)) { "Review reason must be content-free" } }
}

@Serializable
enum class ReviewResolution { APPROVED, REJECTED, PENDING }

fun interface ReviewGate {
    suspend fun resolve(request: ReviewRequest): ReviewResolution
}

data class AssuranceTarget(
    val ceiling: AssuranceClass,
    val highAssuranceProfile: Boolean,
)

internal val CONTENT_FREE_CODE = Regex("[A-Z][A-Z0-9_]{1,63}")
