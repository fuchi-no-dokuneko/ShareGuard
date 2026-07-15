package app.shareguard.core.pipeline

import app.shareguard.core.model.BlockId
import app.shareguard.core.model.BlockVersion
import app.shareguard.core.model.InputKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SequenceValidatorTest {
    private val validator = SequenceValidator()

    @Test
    fun sourceAlternativeIsValidAndStillExactlyOneSource() {
        val directEntry = BuiltInPresets.textBalanced.selectSource(BlockId("IN-004"))
        assertThat(validator.validate(directEntry).violations).isEmpty()
        assertThat(directEntry.blockIds).contains(BlockId("IN-004"))
        assertThat(directEntry.blockIds).doesNotContain(BlockId("IN-001"))

        val switchedBack = directEntry.selectSource(BlockId("IN-001"))
        assertThat(validator.validate(switchedBack).violations).isEmpty()
        assertThat(switchedBack.blockIds).contains(BlockId("IN-001"))
        assertThat(switchedBack.blockIds).doesNotContain(BlockId("IN-004"))
    }

    @Test
    fun duplicateSourceAndDuplicateBlockAreRejected() {
        val preset = BuiltInPresets.textBalanced
        val mutated = preset.blockReferences.toMutableList().apply {
            add(2, BlockReference(BlockId("IN-004"), BlockVersion(1)))
            add(3, first { it.blockId.value == "IN-005" })
        }
        val codes = validator.validate(preset, mutated).violations.map { it.code }
        assertThat(codes).containsAtLeast(PipelineViolationCode.PV_001, PipelineViolationCode.PV_003)
    }

    @Test
    fun removingMandatoryLedgerOrPersistenceIsRejected() {
        listOf("CAN-003", "PST-002").forEach { removedId ->
            val preset = BuiltInPresets.textBalanced
            val mutated = preset.blockReferences.filterNot { it.blockId.value == removedId }
            assertThat(validator.validate(preset, mutated).violations.map { it.code })
                .contains(PipelineViolationCode.PV_005)
        }
    }

    @Test
    fun urlSerializerBeforePolicyIsRejected() {
        val preset = BuiltInPresets.textBalanced
        val mutated = moveBefore(preset.blockReferences, "URL-014", "URL-010")
        assertThat(validator.validate(preset, mutated).violations.map { it.code })
            .contains(PipelineViolationCode.PV_010)
    }

    @Test
    fun verificationBeforeFinalSerializationAndTransformationAfterVerificationAreRejected() {
        val preset = BuiltInPresets.textBalanced
        val earlyVerification = moveBefore(preset.blockReferences, "VER-001", "OUT-BND-001")
        assertThat(validator.validate(preset, earlyVerification).violations.map { it.code })
            .contains(PipelineViolationCode.PV_006)

        val lateTransformation = moveAfter(preset.blockReferences, "TXT-010", "VER-001")
        assertThat(validator.validate(preset, lateTransformation).violations.map { it.code })
            .contains(PipelineViolationCode.PV_007)
    }

    @Test
    fun rendererBeforeCanonicalApprovalIsRejected() {
        val preset = BuiltInPresets.textRebuiltImage
        val mutated = moveBefore(preset.blockReferences, "REN-001", "REV-008")
        assertThat(validator.validate(preset, mutated).violations.map { it.code })
            .contains(PipelineViolationCode.PV_012)
    }

    @Test
    fun unknownBlockVersionRequiresMigration() {
        val preset = BuiltInPresets.textBalanced
        val mutated = preset.blockReferences.map { reference ->
            if (reference.blockId.value == "TXT-001") reference.copy(blockVersion = BlockVersion(99)) else reference
        }
        assertThat(validator.validate(preset, mutated).violations.map { it.code })
            .contains(PipelineViolationCode.PV_018)
    }

    @Test
    fun incompatibleInputTypeIsRejected() {
        val preset = BuiltInPresets.textBalanced.copy(inputKind = InputKind.IMAGE)
        assertThat(validator.validate(preset).violations.map { it.code })
            .contains(PipelineViolationCode.PV_017)
    }

    private fun moveBefore(sequence: List<BlockReference>, moved: String, anchor: String): List<BlockReference> {
        val result = sequence.toMutableList()
        val item = result.removeAt(result.indexOfFirst { it.blockId.value == moved })
        result.add(result.indexOfFirst { it.blockId.value == anchor }, item)
        return result
    }

    private fun moveAfter(sequence: List<BlockReference>, moved: String, anchor: String): List<BlockReference> {
        val result = sequence.toMutableList()
        val item = result.removeAt(result.indexOfFirst { it.blockId.value == moved })
        result.add(result.indexOfFirst { it.blockId.value == anchor } + 1, item)
        return result
    }
}
