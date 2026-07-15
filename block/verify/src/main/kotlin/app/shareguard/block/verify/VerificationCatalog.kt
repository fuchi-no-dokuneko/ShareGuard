package app.shareguard.block.verify

import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.VerificationId
import app.shareguard.core.model.VerificationLayer
import app.shareguard.core.model.VerificationType

enum class VerificationApplicability {
    ALL_OUTPUTS,
    TEXTUAL_OUTPUT,
    URL_BEARING_OUTPUT,
    IMAGE_OUTPUT,
    REBUILT_IMAGE,
    CANONICAL_OUTPUT,
    STRICT_RELEASE,
}

data class VerificationDescriptor(
    val verificationId: VerificationId,
    val normativeBlockId: String?,
    val type: VerificationType,
    val layers: Set<VerificationLayer>,
    val applicability: VerificationApplicability,
    val requiredByDefault: Boolean,
) {
    init {
        require(layers.isNotEmpty()) { "Verification descriptor requires a layer" }
        require(normativeBlockId == null || Regex("VER-[0-9]{3}").matches(normativeBlockId)) {
            "Invalid normative verifier block ID"
        }
    }

    fun appliesTo(outputMode: OutputMode, releaseControls: Boolean): Boolean = when (applicability) {
        VerificationApplicability.ALL_OUTPUTS -> true
        VerificationApplicability.TEXTUAL_OUTPUT -> outputMode != OutputMode.DERIVATIVE_IMAGE
        VerificationApplicability.URL_BEARING_OUTPUT -> outputMode != OutputMode.DERIVATIVE_IMAGE
        VerificationApplicability.IMAGE_OUTPUT -> outputMode != OutputMode.TEXT
        VerificationApplicability.REBUILT_IMAGE -> outputMode in setOf(OutputMode.REBUILT_IMAGE, OutputMode.BOTH)
        VerificationApplicability.CANONICAL_OUTPUT -> outputMode != OutputMode.DERIVATIVE_IMAGE
        VerificationApplicability.STRICT_RELEASE -> releaseControls
    }
}

object FinalVerificationCatalog {
    val descriptors: List<VerificationDescriptor> = listOf(
        descriptor("ver-001", "VER-001", VerificationType.EXECUTED_BLOCK_MANIFEST, VerificationApplicability.ALL_OUTPUTS, VerificationLayer.STRUCTURAL),
        descriptor("ver-002", "VER-002", VerificationType.CANONICAL_REVISION_LINK, VerificationApplicability.ALL_OUTPUTS, VerificationLayer.STRUCTURAL, VerificationLayer.PROVENANCE),
        descriptor("ver-003", "VER-003", VerificationType.FINAL_METADATA, VerificationApplicability.IMAGE_OUTPUT, VerificationLayer.CONTAINER),
        descriptor("ver-004", "VER-004", VerificationType.FINAL_UNICODE, VerificationApplicability.TEXTUAL_OUTPUT, VerificationLayer.CONTENT),
        descriptor("ver-005", "VER-005", VerificationType.FINAL_URL, VerificationApplicability.URL_BEARING_OUTPUT, VerificationLayer.CONTENT),
        descriptor("ver-006", "VER-006", VerificationType.OCR_ROUND_TRIP, VerificationApplicability.REBUILT_IMAGE, VerificationLayer.CONTENT),
        descriptor("ver-007", "VER-007", VerificationType.SOURCE_REFERENCE, VerificationApplicability.ALL_OUTPUTS, VerificationLayer.PROVENANCE, VerificationLayer.PRIVACY_ENGINEERING),
        descriptor("ver-008", "VER-008", VerificationType.SOURCE_PIXEL_DEPENDENCY, VerificationApplicability.IMAGE_OUTPUT, VerificationLayer.PROVENANCE),
        descriptor("ver-009", "VER-009", VerificationType.MACHINE_READABLE_CODE, VerificationApplicability.IMAGE_OUTPUT, VerificationLayer.CONTENT),
        descriptor("ver-010", "VER-010", VerificationType.VISUAL_REGION_COVERAGE, VerificationApplicability.IMAGE_OUTPUT, VerificationLayer.CONTENT, VerificationLayer.PROVENANCE),
        descriptor("ver-011", "VER-011", VerificationType.IDEMPOTENCE, VerificationApplicability.CANONICAL_OUTPUT, VerificationLayer.STRUCTURAL, VerificationLayer.CONTENT),
        descriptor("ver-012", "VER-012", VerificationType.NO_NETWORK_RUNTIME, VerificationApplicability.STRICT_RELEASE, VerificationLayer.PRIVACY_ENGINEERING),
        descriptor("ver-013", "VER-013", VerificationType.SENSITIVE_LOGGING, VerificationApplicability.STRICT_RELEASE, VerificationLayer.PRIVACY_ENGINEERING),
        descriptor("ver-014", "VER-014", VerificationType.ASSURANCE_CLASSIFIER, VerificationApplicability.ALL_OUTPUTS, VerificationLayer.STRUCTURAL),
        descriptor("ver-015", "VER-015", VerificationType.HUMAN_READABLE_REPORT, VerificationApplicability.ALL_OUTPUTS, VerificationLayer.STRUCTURAL),
        descriptor("ver-pst-001", null, VerificationType.PERSISTENT_REOPEN_AND_DIGEST, VerificationApplicability.ALL_OUTPUTS, VerificationLayer.CONTAINER, VerificationLayer.PROVENANCE),
    )

    private val byType = descriptors.associateBy { it.type }

    init {
        require(byType.size == VerificationType.entries.size) {
            "Every verification type must have exactly one descriptor"
        }
    }

    fun require(type: VerificationType): VerificationDescriptor =
        requireNotNull(byType[type]) { "Missing descriptor for $type" }

    private fun descriptor(
        id: String,
        normativeBlockId: String?,
        type: VerificationType,
        applicability: VerificationApplicability,
        vararg layers: VerificationLayer,
    ): VerificationDescriptor = VerificationDescriptor(
        verificationId = VerificationId(id),
        normativeBlockId = normativeBlockId,
        type = type,
        layers = layers.toSet(),
        applicability = applicability,
        requiredByDefault = true,
    )
}
