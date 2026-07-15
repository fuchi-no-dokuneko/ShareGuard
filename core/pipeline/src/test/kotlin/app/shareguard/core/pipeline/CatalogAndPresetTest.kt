package app.shareguard.core.pipeline

import app.shareguard.core.model.BlockId
import app.shareguard.core.model.BlockVersion
import app.shareguard.core.model.OutputMode
import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import org.junit.Test

class CatalogAndPresetTest {
    @Test
    fun registryContainsEveryNormativeBlockExactlyOnceWithCompleteStableMetadata() {
        val expected = buildList {
            addAll(ids("SYS", 1..4))
            addAll(ids("IN", 1..7))
            addAll(ids("TXT", 1..17))
            addAll(ids("URL", 1..15))
            addAll(ids("IMG", 1..18))
            addAll(ids("CAN", 1..3))
            addAll(ids("REV", 1..8))
            addAll(ids("REN", 1..11))
            addAll(listOf("OUT-TXT-001", "OUT-TXT-002", "OUT-IMG-001", "OUT-BND-001"))
            addAll(ids("DER", 1..6))
            addAll(ids("VER", 1..15))
            addAll(ids("EXP", 1..3))
            addAll(ids("PST", 1..7))
        }

        assertThat(NormativeBlockCatalog.exactIds).containsExactlyElementsIn(expected)
        assertThat(NormativeBlockCatalog.exactIds).containsNoDuplicates()
        assertThat(NormativeBlockCatalog.descriptors).hasSize(118)
        NormativeBlockCatalog.descriptors.forEach { descriptor ->
            assertThat(descriptor.blockVersion).isEqualTo(BlockVersion(1))
            assertThat(descriptor.displayName).isNotEmpty()
            assertThat(descriptor.description).isNotEmpty()
            assertThat(descriptor.acceptedInputKinds).isNotEmpty()
            assertThat(descriptor.supportedOutputModes).isNotEmpty()
            assertThat(descriptor.threatCoverage).isNotEmpty()
            assertThat(descriptor.invalidationKeys).isNotEmpty()
            assertThat(descriptor.verificationRequirements).isNotEmpty()
            assertThat(descriptor.persistentLoggingAllowed).isFalse()
            assertThat(descriptor.builtIn).isTrue()
        }
    }

    @Test
    fun allBuiltInPresetSequencesPassEveryStaticRule() {
        val validator = SequenceValidator()
        BuiltInPresets.all.forEach { preset ->
            assertThat(validator.validate(preset).violations)
                .isEmpty()
        }
    }

    @Test
    fun everyPresetUsesPrecedenceCorrectLedgerVerificationPersistenceAndShareOrder() {
        BuiltInPresets.all.forEach { preset ->
            val ids = preset.blockIds.map(BlockId::value)
            assertThat(ids.takeLast(6)).containsExactly(
                "PST-002", "PST-003", "EXP-001", "PST-005", "EXP-002", "SYS-003",
            ).inOrder()
            assertThat(ids.indexOf("CAN-003")).isLessThan(ids.indexOf("VER-001"))
            assertThat(ids.indexOf("VER-014")).isLessThan(ids.indexOf("PST-002"))
            assertThat(ids.indexOf("VER-015")).isLessThan(ids.indexOf("PST-002"))
            assertThat(ids.indexOf("PST-002")).isLessThan(ids.indexOf("PST-005"))
            assertThat(ids.count { it == "OUT-BND-001" }).isEqualTo(1)
        }
    }

    @Test
    fun bothPresetsContainTextAndImageSerializationBeforeOneBundle() {
        BuiltInPresets.all.filter { it.outputMode == OutputMode.BOTH }.forEach { preset ->
            val ids = preset.blockIds.map(BlockId::value)
            val bundle = ids.indexOf("OUT-BND-001")
            assertThat(ids.indexOf("OUT-TXT-001")).isLessThan(bundle)
            assertThat(ids.indexOf("OUT-TXT-002")).isLessThan(bundle)
            assertThat(ids.indexOf("OUT-IMG-001")).isLessThan(bundle)
            assertThat(ids.indexOf("REN-010")).isLessThan(bundle)
        }
    }

    @Test
    fun builtInPresetSequencesMatchTheNormativeOrderSnapshotsExactly() {
        val expected = mapOf(
            "PRESET-TT-BALANCED" to (59 to "9570f89b04354931044d073e6622bada594eeca13286b7621292ac8e0adc5eb4"),
            "PRESET-TT-STRICT-URL" to (62 to "20875e745994876ecfc1522bef6fdfc9ec8a5a059febdd6a008407dab3380e03"),
            "PRESET-TI-REBUILT" to (71 to "8de25f1bfa146cc2f96fafe464f22e701419ca2eb7d5249e7f73e2abafc7c38f"),
            "PRESET-IT-CANONICAL" to (79 to "e808e7ba75a9dc95d08aa867857e721ee733b50686e98fcdf7952a98002a83cc"),
            "PRESET-II-FULL-REBUILD" to (96 to "adce5ae7e58f0ced264911d056ef5a7dc0302f43d48a43d3a8de895a409ef16b"),
            "PRESET-II-DERIVATIVE" to (39 to "bd5a270d3a491e29657db78415c6bee83bf5ced8428ed8368a56ec7ddf57d25d"),
            "PRESET-TB-BOTH" to (73 to "acbb7df5f7bfcf0db0daf19e677290f00e99eea18585526667b658bec8922288"),
            "PRESET-IB-BOTH" to (98 to "9ba3706ff47a902403c646905f69a285f44a6af777f92cc6b2afbf864cb5dab1"),
        )

        assertThat(BuiltInPresets.all.map { it.presetId }).containsExactlyElementsIn(expected.keys)
        BuiltInPresets.all.forEach { preset ->
            val (size, digest) = expected.getValue(preset.presetId)
            assertThat(preset.blockReferences).hasSize(size)
            assertThat(sequenceDigest(preset)).isEqualTo(digest)
        }
    }

    private fun ids(prefix: String, range: IntRange): List<String> =
        range.map { "$prefix-${it.toString().padStart(3, '0')}" }

    private fun sequenceDigest(preset: PipelinePreset): String = MessageDigest.getInstance("SHA-256")
        .digest(preset.blockIds.joinToString(",") { it.value }.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
