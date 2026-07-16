package app.shareguard.core.pipeline

import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.BlockId
import app.shareguard.core.model.BlockVersion
import app.shareguard.core.model.InputKind
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.SchemaVersion
import app.shareguard.core.model.WorkflowVersion
import kotlinx.serialization.Serializable

@Serializable
data class SourceAlternative(
    val defaultBlockId: BlockId,
    val allowedAlternatives: List<BlockId>,
)

@Serializable
data class PipelinePreset(
    val presetId: String,
    val schemaVersion: SchemaVersion,
    val presetVersion: WorkflowVersion,
    val inputKind: InputKind,
    val outputMode: OutputMode,
    val blockReferences: List<BlockReference>,
    val requiredBlockIds: List<BlockId>,
    val sourceAlternatives: List<SourceAlternative>,
    val assuranceCeiling: AssuranceClass,
    val highAssuranceProfile: Boolean,
) {
    init {
        require(PRESET_ID.matches(presetId)) { "Invalid preset ID: $presetId" }
        require(blockReferences.isNotEmpty()) { "Preset sequence cannot be empty" }
        require(blockReferences.map { it.blockId }.distinct().size == blockReferences.size) {
            "Preset cannot repeat a block"
        }
        require(requiredBlockIds.distinct().size == requiredBlockIds.size) {
            "Required block IDs must be unique"
        }
        require(requiredBlockIds.all { required -> blockReferences.any { it.blockId == required } }) {
            "Every required block must exist in the canonical preset sequence"
        }
        require(
            outputMode != OutputMode.DERIVATIVE_IMAGE ||
                assuranceCeiling.isAtMost(AssuranceClass.AS_1_REENCODED_DERIVATIVE)
        ) { "Derivative preset cannot exceed AS-1" }
    }

    val blockIds: List<BlockId>
        get() = blockReferences.map { it.blockId }

    fun withSequence(sequence: List<BlockReference>): PipelinePreset = copy(blockReferences = sequence)

    fun selectSource(blockId: BlockId): PipelinePreset {
        val alternative = sourceAlternatives.singleOrNull { blockId in it.allowedAlternatives }
            ?: throw IllegalArgumentException("${blockId.value} is not a source alternative for $presetId")
        val replaced = blockReferences.map { reference ->
            if (reference.blockId in alternative.allowedAlternatives) {
                BlockReference(blockId, NormativeBlockCatalog.registry.require(blockId).blockVersion)
            } else {
                reference
            }
        }
        val required = requiredBlockIds.map {
            if (it in alternative.allowedAlternatives) blockId else it
        }
        return copy(blockReferences = replaced, requiredBlockIds = required)
    }
}

object BuiltInPresets {
    val textBalanced: PipelinePreset = preset(
        id = "PRESET-TT-BALANCED",
        inputKind = InputKind.TEXT,
        outputMode = OutputMode.TEXT,
        sequence = textCanonical(strictUrl = false) + textOutputAndVerification() + completion(),
        sourceAlternatives = textSourceAlternatives(),
        ceiling = AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT,
    )

    val textStrictUrl: PipelinePreset = preset(
        id = "PRESET-TT-STRICT-URL",
        inputKind = InputKind.TEXT,
        outputMode = OutputMode.TEXT,
        sequence = textCanonical(strictUrl = true) + textOutputAndVerification() + completion(),
        sourceAlternatives = textSourceAlternatives(),
        ceiling = AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT,
    )

    val textRebuiltImage: PipelinePreset = preset(
        id = "PRESET-TI-REBUILT",
        inputKind = InputKind.TEXT,
        outputMode = OutputMode.REBUILT_IMAGE,
        sequence = textCanonical(strictUrl = false) + rebuiltImageOutputAndVerification() + completion(),
        sourceAlternatives = textSourceAlternatives(),
        ceiling = AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE,
    )

    val imageCanonicalText: PipelinePreset = preset(
        id = "PRESET-IT-CANONICAL",
        inputKind = InputKind.IMAGE,
        outputMode = OutputMode.TEXT,
        sequence = imageCanonical(includeFinalAssuranceGate = true) + textOutputAndVerification() + completion(),
        sourceAlternatives = imageSourceAlternatives(),
        ceiling = AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT,
    )

    val imageFullRebuild: PipelinePreset = preset(
        id = "PRESET-II-FULL-REBUILD",
        inputKind = InputKind.IMAGE,
        outputMode = OutputMode.REBUILT_IMAGE,
        sequence = imageCanonical(includeFinalAssuranceGate = false) + listOf(
            "IMG-017", "REV-006", "REV-008", "TXT-017",
        ) + fullRebuildOutputAndVerification() + completion(),
        sourceAlternatives = imageSourceAlternatives(),
        ceiling = AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE,
    )

    val imageDerivative: PipelinePreset = preset(
        id = "PRESET-II-DERIVATIVE",
        inputKind = InputKind.IMAGE,
        outputMode = OutputMode.DERIVATIVE_IMAGE,
        sequence = imageInput() + ids("IMG", 1..8) + listOf(
            "CAN-001", "CAN-002",
            // Quantization and stochastic perturbation remain off until corpus benchmark approval exists.
            "DER-001", "DER-002", "DER-005", "DER-006",
            "CAN-003",
            "OUT-IMG-001", "OUT-BND-001",
            "VER-001", "VER-003", "VER-007", "VER-008", "VER-009", "VER-010", "VER-014", "VER-015",
        ) + completion(),
        sourceAlternatives = imageSourceAlternatives(),
        ceiling = AssuranceClass.AS_1_REENCODED_DERIVATIVE,
        highAssurance = false,
    )

    val textAndRebuiltImage: PipelinePreset = preset(
        id = "PRESET-TB-BOTH",
        inputKind = InputKind.TEXT,
        outputMode = OutputMode.BOTH,
        sequence = textCanonical(strictUrl = false) + listOf("OUT-TXT-001", "OUT-TXT-002") +
            rebuiltImageOutputAndVerification(includeBundle = true) + completion(),
        sourceAlternatives = textSourceAlternatives(),
        ceiling = AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE,
    )

    val imageTextAndRebuiltImage: PipelinePreset = preset(
        id = "PRESET-IB-BOTH",
        inputKind = InputKind.IMAGE,
        outputMode = OutputMode.BOTH,
        sequence = imageCanonical(includeFinalAssuranceGate = false) + listOf(
            "IMG-017", "REV-006", "REV-008", "TXT-017", "OUT-TXT-001", "OUT-TXT-002",
        ) + fullRebuildOutputAndVerification(includeBundle = true) + completion(),
        sourceAlternatives = imageSourceAlternatives(),
        ceiling = AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE,
    )

    val all: List<PipelinePreset> = listOf(
        textBalanced,
        textStrictUrl,
        textRebuiltImage,
        imageCanonicalText,
        imageFullRebuild,
        imageDerivative,
        textAndRebuiltImage,
        imageTextAndRebuiltImage,
    )

    fun require(presetId: String): PipelinePreset =
        all.singleOrNull { it.presetId == presetId }
            ?: throw IllegalArgumentException("Unknown built-in preset: $presetId")

    private fun preset(
        id: String,
        inputKind: InputKind,
        outputMode: OutputMode,
        sequence: List<String>,
        sourceAlternatives: List<SourceAlternative>,
        ceiling: AssuranceClass,
        highAssurance: Boolean = true,
    ): PipelinePreset {
        val references = sequence.map { blockId ->
            val metadata = NormativeBlockCatalog.registry.require(BlockId(blockId))
            BlockReference(metadata.blockId, metadata.blockVersion)
        }
        return PipelinePreset(
            presetId = id,
            schemaVersion = SchemaVersion(1),
            presetVersion = WorkflowVersion(1),
            inputKind = inputKind,
            outputMode = outputMode,
            blockReferences = references,
            requiredBlockIds = references.map { it.blockId },
            sourceAlternatives = sourceAlternatives,
            assuranceCeiling = ceiling,
            highAssuranceProfile = highAssurance,
        )
    }

    private fun textInput(): List<String> = listOf(
        "SYS-001", "IN-001", "IN-005", "IN-006", "PST-001",
    )

    private fun imageInput(): List<String> = listOf(
        "SYS-001", "IN-002", "IN-005", "IN-006", "IN-007", "PST-001",
    )

    private fun textCanonical(strictUrl: Boolean): List<String> =
        textInput() +
            ids("TXT", 1..9) +
            ids("URL", 1..9) +
            listOf("CAN-001", "CAN-002") +
            ids("TXT", 10..16) +
            (if (strictUrl) ids("URL", 10..15) else listOf("URL-010", "URL-014", "URL-015")) +
            listOf("REV-001", "REV-002", "REV-004", "CAN-003", "REV-007", "REV-008", "TXT-017")

    private fun imageCanonical(includeFinalAssuranceGate: Boolean): List<String> {
        val ending = buildList {
            addAll(listOf("REV-001", "REV-002", "REV-004", "CAN-003", "REV-007"))
            if (includeFinalAssuranceGate) add("REV-008")
            if (includeFinalAssuranceGate) add("TXT-017")
        }
        return imageInput() +
            ids("IMG", 1..6) +
            ids("IMG", 9..16) +
            ids("TXT", 1..9) +
            ids("URL", 1..9) +
            listOf("CAN-001", "CAN-002", "REV-003", "REV-005") +
            (if (includeFinalAssuranceGate) listOf("REV-006") else emptyList()) +
            ids("TXT", 10..16) +
            ids("URL", 10..15) +
            ending
    }

    private fun textOutputAndVerification(): List<String> = listOf(
        "OUT-TXT-001", "OUT-TXT-002", "OUT-BND-001",
        "VER-001", "VER-002", "VER-004", "VER-005", "VER-007", "VER-011", "VER-014", "VER-015",
    )

    private fun rebuiltImageOutputAndVerification(includeBundle: Boolean = false): List<String> {
        val output = listOf(
            "REN-001", "REN-002", "REN-003", "REN-004", "REN-008", "REN-009", "REN-010", "REN-011",
            "OUT-IMG-001",
        )
        val verification = listOf(
            "VER-001", "VER-002", "VER-003", "VER-004", "VER-005", "VER-006", "VER-007", "VER-008",
            "VER-009", "VER-010", "VER-014", "VER-015",
        )
        return output + listOf("OUT-BND-001") + verification
    }

    private fun fullRebuildOutputAndVerification(includeBundle: Boolean = false): List<String> {
        val output = listOf(
            "REN-001", "REN-002", "REN-003", "REN-004", "REN-008", "REN-009", "REN-010", "REN-011",
            "OUT-IMG-001", "OUT-BND-001",
        )
        return output + ids("VER", 1..11) + listOf("VER-014", "VER-015")
    }

    private fun completion(): List<String> = listOf(
        "PST-002", "PST-003", "EXP-001", "PST-005", "EXP-002", "SYS-003",
    )

    private fun ids(prefix: String, range: IntRange): List<String> =
        range.map { "$prefix-${it.toString().padStart(3, '0')}" }

    private fun textSourceAlternatives() = listOf(
        SourceAlternative(BlockId("IN-001"), listOf(BlockId("IN-001"), BlockId("IN-004"))),
    )
    private fun imageSourceAlternatives() = listOf(
        SourceAlternative(BlockId("IN-002"), listOf(BlockId("IN-002"), BlockId("IN-003"))),
    )
}

private val PRESET_ID = Regex("PRESET-[A-Z0-9-]+")
