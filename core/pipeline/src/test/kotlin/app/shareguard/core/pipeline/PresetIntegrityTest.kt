package app.shareguard.core.pipeline

import app.shareguard.core.model.BlockVersion
import app.shareguard.core.model.SchemaVersion
import app.shareguard.core.model.WorkflowVersion
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PresetIntegrityTest {
    private val integrity = PresetIntegrity()

    @Test
    fun canonicalSerializationAndDigestAreDeterministicAndRoundTrip() {
        val first = integrity.serialize(BuiltInPresets.textBalanced)
        val second = integrity.serialize(BuiltInPresets.textBalanced)

        assertThat(first).isEqualTo(second)
        assertThat(integrity.decodeAndValidate(first)).isEqualTo(BuiltInPresets.textBalanced)
    }

    @Test
    fun anyCanonicalPayloadMutationIsRejectedByDigest() {
        val serialized = integrity.serialize(BuiltInPresets.textBalanced)
        val mutated = serialized.copy(
            canonicalJson = serialized.canonicalJson.replace("PRESET-TT-BALANCED", "PRESET-TT-MUTATED"),
        )

        val error = assertThrows(PresetIntegrityException::class.java) {
            integrity.decodeAndValidate(mutated)
        }
        assertThat(error.reasonCode).isEqualTo("PRESET_DIGEST_MISMATCH")
    }

    @Test
    fun unknownBlockVersionAndSchemaRequireExplicitMigration() {
        val preset = BuiltInPresets.textBalanced
        val unknownBlockVersion = preset.withSequence(
            preset.blockReferences.map { reference ->
                if (reference.blockId.value == "TXT-001") reference.copy(blockVersion = BlockVersion(2)) else reference
            },
        )
        val versionError = assertThrows(PresetMigrationRejectedException::class.java) {
            integrity.serialize(unknownBlockVersion)
        }
        assertThat(versionError.reasonCode).isEqualTo("UNKNOWN_BLOCK_VERSION_REQUIRES_MIGRATION")

        val schemaError = assertThrows(PresetMigrationRejectedException::class.java) {
            integrity.serialize(preset.copy(schemaVersion = SchemaVersion(2)))
        }
        assertThat(schemaError.reasonCode).isEqualTo("UNSUPPORTED_PRESET_SCHEMA")
    }

    @Test
    fun builtInVersionMutationAndUnmigratedPresetAreRejected() {
        val mutatedVersion = BuiltInPresets.textBalanced.copy(presetVersion = WorkflowVersion(2))
        val serialized = integrity.serialize(mutatedVersion)

        val versionError = assertThrows(PresetMigrationRejectedException::class.java) {
            integrity.decodeAndValidate(serialized)
        }
        assertThat(versionError.reasonCode).isEqualTo("UNMIGRATED_BUILT_IN_PRESET_VERSION")

        val explicitError = assertThrows(PresetMigrationRejectedException::class.java) {
            integrity.rejectUnmigrated(BuiltInPresets.textBalanced)
        }
        assertThat(explicitError.reasonCode).isEqualTo("EXPLICIT_PRESET_MIGRATION_REQUIRED")
    }
}
