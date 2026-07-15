package app.shareguard.core.pipeline

import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.BlockId
import app.shareguard.core.model.InputKind
import app.shareguard.core.model.OutputMode
import kotlinx.serialization.Serializable

@Serializable
enum class PipelineViolationCode {
    PV_001,
    PV_002,
    PV_003,
    PV_004,
    PV_005,
    PV_006,
    PV_007,
    PV_008,
    PV_009,
    PV_010,
    PV_011,
    PV_012,
    PV_013,
    PV_014,
    PV_015,
    PV_016,
    PV_017,
    PV_018,
    PV_019,
    PV_020,
}

@Serializable
data class PipelineViolation(
    val code: PipelineViolationCode,
    val blockIds: List<BlockId>,
    val reasonCode: String,
) {
    init { require(CONTENT_FREE_CODE.matches(reasonCode)) { "Violation reason must be content-free" } }
}

data class SequenceValidationResult(
    val violations: List<PipelineViolation>,
) {
    val isValid: Boolean get() = violations.isEmpty()

    fun requireValid() {
        if (!isValid) throw InvalidPipelineSequenceException(violations)
    }
}

class InvalidPipelineSequenceException(
    val violations: List<PipelineViolation>,
) : IllegalArgumentException(
    "Invalid pipeline: ${violations.joinToString { "${it.code}:${it.reasonCode}" }}",
)

class SequenceValidator(
    private val registry: PipelineBlockRegistry = NormativeBlockCatalog.registry,
) {
    fun validate(
        preset: PipelinePreset,
        sequence: List<BlockReference> = preset.blockReferences,
    ): SequenceValidationResult {
        val violations = mutableListOf<PipelineViolation>()
        val ids = sequence.map { it.blockId }
        val known = sequence.mapNotNull { reference ->
            val descriptor = registry.find(reference.blockId)
            when {
                descriptor == null -> violations += violation(
                    PipelineViolationCode.PV_018,
                    listOf(reference.blockId),
                    "UNKNOWN_BLOCK_ID",
                )
                descriptor.blockVersion != reference.blockVersion -> violations += violation(
                    PipelineViolationCode.PV_018,
                    listOf(reference.blockId),
                    "UNKNOWN_BLOCK_VERSION",
                )
            }
            descriptor?.takeIf { it.blockVersion == reference.blockVersion }
        }

        val sourceIds = known.filter { it.sourceBlock }.map { it.blockId }
        if (sourceIds.size != 1) {
            violations += violation(PipelineViolationCode.PV_001, sourceIds, "SOURCE_BLOCK_COUNT_INVALID")
        }

        val exportIds = known.filter { it.finalExportPreparation }.map { it.blockId }
        if (exportIds.size != 1) {
            violations += violation(PipelineViolationCode.PV_002, exportIds, "EXPORT_PREPARATION_COUNT_INVALID")
        }

        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList()
        if (duplicates.isNotEmpty()) {
            violations += violation(PipelineViolationCode.PV_003, duplicates, "DUPLICATE_BLOCK_LOOP_REPRESENTATION")
        }

        val missingRequired = preset.requiredBlockIds.filterNot(ids::contains)
        if (missingRequired.isNotEmpty()) {
            violations += violation(PipelineViolationCode.PV_005, missingRequired, "MANDATORY_BLOCK_MISSING")
        }

        known.filterNot { it.supports(preset.inputKind, preset.outputMode) }.forEach { descriptor ->
            violations += violation(
                PipelineViolationCode.PV_017,
                listOf(descriptor.blockId),
                "BLOCK_TYPE_INCOMPATIBLE",
            )
        }

        validateCapabilities(known, preset, violations)
        validateOrdering(known, preset, violations)

        val computedCeiling = computedCeiling(preset.inputKind, preset.outputMode, known)
        if (!preset.assuranceCeiling.isAtMost(computedCeiling)) {
            violations += violation(
                PipelineViolationCode.PV_015,
                emptyList(),
                "DECLARED_ASSURANCE_EXCEEDS_CEILING",
            )
        }

        val hasDerivative = known.any { it.blockId.value.startsWith("DER-") }
        if (hasDerivative && (
                preset.outputMode != OutputMode.DERIVATIVE_IMAGE ||
                    !preset.assuranceCeiling.isAtMost(AssuranceClass.AS_1_REENCODED_DERIVATIVE)
                )
        ) {
            violations += violation(PipelineViolationCode.PV_008, emptyList(), "DERIVATIVE_CEILING_VIOLATION")
        }

        if (known.any { it.blockId.value == "REN-007" } &&
            !preset.assuranceCeiling.isAtMost(AssuranceClass.AS_3_REBUILT_WITH_SOURCE_REGIONS)
        ) {
            violations += violation(PipelineViolationCode.PV_009, listOf(BlockId("REN-007")), "SOURCE_PIXEL_CEILING_VIOLATION")
        }

        known.filter { !it.builtIn && preset.highAssuranceProfile }.forEach { descriptor ->
            violations += violation(
                PipelineViolationCode.PV_019,
                listOf(descriptor.blockId),
                "EXTERNAL_BLOCK_IN_HIGH_ASSURANCE_PROFILE",
            )
        }

        known.filter { it.persistentLoggingAllowed }.forEach { descriptor ->
            violations += violation(
                PipelineViolationCode.PV_020,
                listOf(descriptor.blockId),
                "PERSISTENT_BLOCK_LOGGING_FORBIDDEN",
            )
        }

        return SequenceValidationResult(violations.distinct())
    }

    private fun validateCapabilities(
        descriptors: List<PipelineBlockMetadata>,
        preset: PipelinePreset,
        violations: MutableList<PipelineViolation>,
    ) {
        val available = linkedSetOf<PipelineCapability>()
        descriptors.forEach { descriptor ->
            val missing = descriptor.inputPredicate.filterNot(available::contains).toMutableList()
            if (descriptor.blockId.value == "OUT-IMG-001" &&
                (PipelineCapability.IMAGE_ARTIFACT in available || PipelineCapability.DERIVATIVE_ARTIFACT in available)
            ) {
                missing.remove(PipelineCapability.IMAGE_ARTIFACT)
            }
            if (descriptor.blockId.value == "CAN-001" && PipelineCapability.IMAGE_INSPECTED in available) {
                missing.remove(PipelineCapability.TEXT_INSPECTED)
            }
            if (descriptor.blockId.value == "OUT-BND-001") {
                val artifactReady = when (preset.outputMode) {
                    OutputMode.TEXT -> PipelineCapability.TEXT_ARTIFACT in available
                    OutputMode.REBUILT_IMAGE -> PipelineCapability.IMAGE_ARTIFACT in available
                    OutputMode.BOTH -> PipelineCapability.TEXT_ARTIFACT in available &&
                        PipelineCapability.IMAGE_ARTIFACT in available
                    OutputMode.DERIVATIVE_IMAGE -> PipelineCapability.DERIVATIVE_ARTIFACT in available ||
                        PipelineCapability.IMAGE_ARTIFACT in available
                }
                if (!artifactReady) missing += PipelineCapability.OUTPUT_BUNDLE
            }
            if (missing.isNotEmpty()) {
                violations += violation(
                    PipelineViolationCode.PV_004,
                    listOf(descriptor.blockId),
                    "INPUT_PREDICATE_UNSATISFIED",
                )
            }
            available += descriptor.outputGuarantees
        }
    }

    private fun validateOrdering(
        descriptors: List<PipelineBlockMetadata>,
        preset: PipelinePreset,
        violations: MutableList<PipelineViolation>,
    ) {
        val ids = descriptors.map { it.blockId.value }
        fun index(id: String) = ids.indexOf(id)
        fun present(id: String) = index(id) >= 0
        fun requireAfter(code: PipelineViolationCode, later: String, earlier: String, reason: String) {
            if (present(later) && (!present(earlier) || index(later) <= index(earlier))) {
                violations += violation(code, listOf(BlockId(later), BlockId(earlier)), reason)
            }
        }

        requireAfter(PipelineViolationCode.PV_004, "IN-005", ids.firstOrNull { it in sourceBlockIds } ?: "IN-001", "SOURCE_TYPE_BEFORE_SOURCE")
        requireAfter(PipelineViolationCode.PV_004, "IN-006", "IN-005", "RESOURCE_GUARD_BEFORE_TYPE")
        if (preset.inputKind == InputKind.IMAGE) {
            requireAfter(PipelineViolationCode.PV_004, "IN-007", "IN-006", "IMAGE_SEAL_BEFORE_RESOURCE_GUARD")
            requireAfter(PipelineViolationCode.PV_004, "PST-001", "IN-007", "IMPORT_ANCHOR_BEFORE_IMAGE_SEAL")
        } else {
            requireAfter(PipelineViolationCode.PV_004, "PST-001", "IN-006", "IMPORT_ANCHOR_BEFORE_TEXT_ACCEPTANCE")
        }

        if (present("URL-014")) {
            val policyIndexes = listOf("URL-010", "URL-011", "URL-012", "URL-013")
                .map(::index)
                .filter { it >= 0 }
            if (policyIndexes.isEmpty() || index("URL-014") <= policyIndexes.max()) {
                violations += violation(
                    PipelineViolationCode.PV_010,
                    listOf(BlockId("URL-014")),
                    "URL_SERIALIZER_BEFORE_POLICY",
                )
            }
        }

        if (present("OUT-TXT-001")) {
            requireAfter(PipelineViolationCode.PV_011, "OUT-TXT-001", "TXT-017", "TEXT_SERIALIZER_BEFORE_LOCK")
            requireAfter(PipelineViolationCode.PV_011, "OUT-TXT-001", "REV-007", "TEXT_SERIALIZER_BEFORE_FINAL_REVIEW")
        }

        val firstRenderer = ids.indexOfFirst { it.startsWith("REN-") }
        if (firstRenderer >= 0) {
            val approval = index("REV-008")
            if (approval < 0 || firstRenderer <= approval || !present("TXT-017")) {
                violations += violation(
                    PipelineViolationCode.PV_012,
                    listOf(BlockId(ids[firstRenderer])),
                    "RENDER_BEFORE_CANONICAL_APPROVAL",
                )
            }
        }

        val firstVerifier = descriptors.indexOfFirst { it.verificationBlock }
        val lastSerializer = descriptors.indexOfLast { it.finalSerialization }
        if (firstVerifier >= 0 && (lastSerializer < 0 || firstVerifier <= lastSerializer)) {
            violations += violation(
                PipelineViolationCode.PV_006,
                listOf(descriptors[firstVerifier].blockId),
                "VERIFICATION_BEFORE_FINAL_SERIALIZATION",
            )
        }
        if (firstVerifier >= 0) {
            val postVerificationTransform = descriptors.drop(firstVerifier + 1).firstOrNull { it.contentTransforming }
            if (postVerificationTransform != null) {
                violations += violation(
                    PipelineViolationCode.PV_007,
                    listOf(postVerificationTransform.blockId),
                    "CONTENT_TRANSFORM_AFTER_VERIFICATION",
                )
            }
        }

        if (present("VER-003")) {
            requireAfter(PipelineViolationCode.PV_013, "VER-003", "OUT-BND-001", "METADATA_RESCAN_BEFORE_FINAL_BYTES")
        }
        if (present("VER-006")) {
            requireAfter(PipelineViolationCode.PV_014, "VER-006", "OUT-IMG-001", "OCR_ROUND_TRIP_BEFORE_FINAL_IMAGE")
            requireAfter(PipelineViolationCode.PV_014, "VER-006", "OUT-BND-001", "OCR_ROUND_TRIP_BEFORE_FINAL_BUNDLE")
        }

        if (present("PST-002")) {
            requireAfter(PipelineViolationCode.PV_004, "PST-002", "VER-014", "PERSIST_BEFORE_ASSURANCE")
            requireAfter(PipelineViolationCode.PV_004, "PST-002", "VER-015", "PERSIST_BEFORE_VERIFICATION_REPORT")
        }
        if (present("PST-005")) {
            requireAfter(PipelineViolationCode.PV_004, "PST-005", "PST-002", "MANAGED_SHARE_BEFORE_COMMIT")
        }
        if (present("EXP-002")) {
            requireAfter(PipelineViolationCode.PV_004, "EXP-002", "PST-005", "SHARESHEET_BEFORE_MANAGED_SHARE_PREPARATION")
        }
    }

    private fun computedCeiling(
        inputKind: InputKind,
        outputMode: OutputMode,
        descriptors: List<PipelineBlockMetadata>,
    ): AssuranceClass = when {
        outputMode == OutputMode.DERIVATIVE_IMAGE || descriptors.any { it.blockId.value.startsWith("DER-") } ->
            AssuranceClass.AS_1_REENCODED_DERIVATIVE
        outputMode == OutputMode.TEXT -> AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT
        descriptors.any { it.blockId.value == "REN-007" } -> AssuranceClass.AS_3_REBUILT_WITH_SOURCE_REGIONS
        inputKind in InputKind.entries -> AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE
        else -> AssuranceClass.AS_0_UNVERIFIED
    }

    private fun violation(
        code: PipelineViolationCode,
        blockIds: List<BlockId>,
        reasonCode: String,
    ) = PipelineViolation(code, blockIds, reasonCode)

    private val sourceBlockIds = setOf("IN-001", "IN-002", "IN-003", "IN-004")
}
