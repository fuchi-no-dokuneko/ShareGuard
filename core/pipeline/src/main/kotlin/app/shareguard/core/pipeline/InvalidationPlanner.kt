package app.shareguard.core.pipeline

import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.BlockId
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.VerificationType
import kotlinx.serialization.Serializable

@Serializable
enum class ChangeSignal {
    SOURCE_CHANGED,
    CANONICAL_DOCUMENT_CHANGED,
    URL_POLICY_CHANGED,
    RENDERER_SETTINGS_CHANGED,
    SAVE_FILENAME_CHANGED,
    BLOCK_VERSION_CHANGED,
    STORAGE_MIGRATED,
    DISPLAY_METADATA_CHANGED,
}

@Serializable
data class InvalidationPlan(
    val invalidatedBlockIds: List<BlockId>,
    val invalidatedVerificationTypes: List<VerificationType>,
    val invalidateCanonicalDocument: Boolean,
    val invalidateTextArtifact: Boolean,
    val invalidateImageArtifact: Boolean,
    val invalidateDerivativeArtifact: Boolean,
    val invalidatePresetSignature: Boolean,
    val requireStoredArtifactRevalidation: Boolean,
    val resultingAssurance: AssuranceClass?,
    val reasonCode: String,
)

class InvalidationPlanner(
    private val registry: PipelineBlockRegistry = NormativeBlockCatalog.registry,
) {
    fun plan(signal: ChangeSignal, outputMode: OutputMode): InvalidationPlan {
        val keys = keysFor(signal)
        val invalidated = registry.descriptors
            .filter { descriptor -> descriptor.invalidationKeys.any(keys::contains) }
            .map { it.blockId }
        val allVerification = registry.descriptors
            .filter { it.verificationBlock && it.invalidationKeys.any(keys::contains) }
            .flatMap { it.verificationRequirements }
            .distinct()
        return when (signal) {
            ChangeSignal.SOURCE_CHANGED -> plan(
                invalidated, allVerification, canonical = true, text = true, image = true,
                derivative = true, signature = false, revalidate = false, "SOURCE_CHANGE_INVALIDATES_DOWNSTREAM",
            )
            ChangeSignal.CANONICAL_DOCUMENT_CHANGED -> plan(
                invalidated, allVerification, canonical = false, text = true, image = true,
                derivative = outputMode == OutputMode.DERIVATIVE_IMAGE, signature = false,
                revalidate = false, "CANONICAL_CHANGE_INVALIDATES_ARTIFACTS",
            )
            ChangeSignal.URL_POLICY_CHANGED -> plan(
                invalidated, allVerification, canonical = true, text = true, image = true,
                derivative = false, signature = false, revalidate = false, "URL_POLICY_INVALIDATES_CANONICAL_OUTPUT",
            )
            ChangeSignal.RENDERER_SETTINGS_CHANGED -> plan(
                invalidated, allVerification, canonical = false, text = false, image = true,
                derivative = true, signature = false, revalidate = false, "RENDERER_CHANGE_INVALIDATES_IMAGE_OUTPUT",
            )
            ChangeSignal.SAVE_FILENAME_CHANGED -> plan(
                invalidated, listOf(VerificationType.SOURCE_REFERENCE), canonical = false, text = false,
                image = false, derivative = false, signature = false, revalidate = false,
                "FILENAME_CHANGE_REQUIRES_FILENAME_VALIDATION",
            )
            ChangeSignal.BLOCK_VERSION_CHANGED -> plan(
                invalidated, allVerification, canonical = true, text = true, image = true,
                derivative = true, signature = true, revalidate = false, "BLOCK_VERSION_INVALIDATES_PRESET",
            )
            ChangeSignal.STORAGE_MIGRATED -> plan(
                invalidated, listOf(VerificationType.PERSISTENT_REOPEN_AND_DIGEST), canonical = false,
                text = false, image = false, derivative = false, signature = false, revalidate = true,
                "STORAGE_MIGRATION_REQUIRES_REVALIDATION",
            )
            ChangeSignal.DISPLAY_METADATA_CHANGED -> plan(
                emptyList(), emptyList(), canonical = false, text = false, image = false,
                derivative = false, signature = false, revalidate = false,
                "DISPLAY_METADATA_DOES_NOT_INVALIDATE_ARTIFACT",
                assurance = null,
            )
        }
    }

    private fun plan(
        blocks: List<BlockId>,
        verifiers: List<VerificationType>,
        canonical: Boolean,
        text: Boolean,
        image: Boolean,
        derivative: Boolean,
        signature: Boolean,
        revalidate: Boolean,
        reason: String,
        assurance: AssuranceClass? = AssuranceClass.AS_0_UNVERIFIED,
    ) = InvalidationPlan(
        invalidatedBlockIds = blocks.distinct(),
        invalidatedVerificationTypes = verifiers.distinct(),
        invalidateCanonicalDocument = canonical,
        invalidateTextArtifact = text,
        invalidateImageArtifact = image,
        invalidateDerivativeArtifact = derivative,
        invalidatePresetSignature = signature,
        requireStoredArtifactRevalidation = revalidate,
        resultingAssurance = assurance,
        reasonCode = reason,
    )

    private fun keysFor(signal: ChangeSignal): Set<InvalidationKey> = when (signal) {
        ChangeSignal.SOURCE_CHANGED -> setOf(InvalidationKey.SOURCE, InvalidationKey.SOURCE_TYPE)
        ChangeSignal.CANONICAL_DOCUMENT_CHANGED -> setOf(
            InvalidationKey.CANONICAL_DOCUMENT,
            InvalidationKey.CANONICAL_REVISION,
        )
        ChangeSignal.URL_POLICY_CHANGED -> setOf(InvalidationKey.URL_POLICY)
        ChangeSignal.RENDERER_SETTINGS_CHANGED -> setOf(InvalidationKey.RENDERER_SETTINGS)
        ChangeSignal.SAVE_FILENAME_CHANGED -> setOf(InvalidationKey.SAVE_FILENAME)
        ChangeSignal.BLOCK_VERSION_CHANGED -> setOf(InvalidationKey.BLOCK_VERSION)
        ChangeSignal.STORAGE_MIGRATED -> setOf(InvalidationKey.STORAGE_MIGRATION)
        ChangeSignal.DISPLAY_METADATA_CHANGED -> setOf(InvalidationKey.DISPLAY_METADATA)
    }
}
