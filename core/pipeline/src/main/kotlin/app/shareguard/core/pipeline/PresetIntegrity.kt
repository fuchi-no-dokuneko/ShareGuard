package app.shareguard.core.pipeline

import app.shareguard.core.model.ContentDigest
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SerializedPreset(
    val canonicalJson: String,
    val digest: ContentDigest,
)

class PresetIntegrity(
    private val registry: PipelineBlockRegistry = NormativeBlockCatalog.registry,
    private val supportedSchemaVersion: Int = 1,
) {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = false
    }

    fun serialize(preset: PipelinePreset): SerializedPreset {
        validateReferences(preset)
        val canonical = json.encodeToString(PipelinePreset.serializer(), preset)
        return SerializedPreset(canonical, digest(canonical))
    }

    fun decodeAndValidate(serialized: SerializedPreset): PipelinePreset {
        if (digest(serialized.canonicalJson) != serialized.digest) {
            throw PresetIntegrityException("PRESET_DIGEST_MISMATCH")
        }
        val preset = try {
            json.decodeFromString(PipelinePreset.serializer(), serialized.canonicalJson)
        } catch (error: IllegalArgumentException) {
            throw PresetIntegrityException("PRESET_PARSE_FAILED", error)
        }
        if (preset.schemaVersion.value != supportedSchemaVersion) {
            throw PresetMigrationRejectedException("UNSUPPORTED_PRESET_SCHEMA")
        }
        val reencoded = json.encodeToString(PipelinePreset.serializer(), preset)
        if (reencoded != serialized.canonicalJson) {
            throw PresetIntegrityException("NON_CANONICAL_PRESET_SERIALIZATION")
        }
        validateReferences(preset)
        val builtIn = BuiltInPresets.all.singleOrNull { it.presetId == preset.presetId }
        if (builtIn != null && preset.presetVersion != builtIn.presetVersion) {
            throw PresetMigrationRejectedException("UNMIGRATED_BUILT_IN_PRESET_VERSION")
        }
        return preset
    }

    fun rejectUnmigrated(preset: PipelinePreset): Nothing =
        throw PresetMigrationRejectedException("EXPLICIT_PRESET_MIGRATION_REQUIRED")

    private fun validateReferences(preset: PipelinePreset) {
        if (preset.schemaVersion.value != supportedSchemaVersion) {
            throw PresetMigrationRejectedException("UNSUPPORTED_PRESET_SCHEMA")
        }
        preset.blockReferences.forEach { reference ->
            try {
                registry.require(reference)
            } catch (error: UnknownBlockException) {
                throw PresetMigrationRejectedException("UNKNOWN_BLOCK_REQUIRES_MIGRATION", error)
            } catch (error: UnknownBlockVersionException) {
                throw PresetMigrationRejectedException("UNKNOWN_BLOCK_VERSION_REQUIRES_MIGRATION", error)
            }
        }
    }

    private fun digest(value: String): ContentDigest {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
        return ContentDigest(bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }
}

open class PresetIntegrityException(
    val reasonCode: String,
    cause: Throwable? = null,
) : IllegalArgumentException(reasonCode, cause) {
    init { require(CONTENT_FREE_CODE.matches(reasonCode)) { "Preset reason must be content-free" } }
}

class PresetMigrationRejectedException(
    reasonCode: String,
    cause: Throwable? = null,
) : PresetIntegrityException(reasonCode, cause)
