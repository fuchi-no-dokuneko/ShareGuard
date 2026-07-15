package app.shareguard.core.storage

import app.shareguard.core.model.ArtifactKind
import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.SavedResultId
import app.shareguard.core.model.SavedResultLifecycleState
import app.shareguard.core.security.ContentDigester
import app.shareguard.core.security.SecretBytes
import app.shareguard.core.security.Sha256ContentDigester
import kotlinx.coroutines.CancellationException

data class PersistentArtifactValidation(
    val accepted: Boolean,
    val reasonCode: String,
    val assuranceCeiling: AssuranceClass? = null,
) {
    init {
        require(Regex("[A-Z][A-Z0-9_]{1,63}").matches(reasonCode)) {
            "Validation reasons must be content-free codes"
        }
    }
}

fun interface PersistentArtifactValidator {
    suspend fun validate(metadata: SavedArtifactEntity, plaintext: SecretBytes): PersistentArtifactValidation
}

object StructuralPersistentArtifactValidator : PersistentArtifactValidator {
    override suspend fun validate(
        metadata: SavedArtifactEntity,
        plaintext: SecretBytes,
    ): PersistentArtifactValidation {
        val kind = runCatching { ArtifactKind.valueOf(metadata.artifactKind) }.getOrNull()
            ?: return PersistentArtifactValidation(false, "UNKNOWN_ARTIFACT_KIND")
        val mimeMatches = when (kind) {
            ArtifactKind.CANONICAL_TEXT -> metadata.mimeType.startsWith("text/")
            ArtifactKind.REBUILT_IMAGE, ArtifactKind.DERIVATIVE_IMAGE -> metadata.mimeType.startsWith("image/")
        }
        return when {
            !mimeMatches -> PersistentArtifactValidation(false, "ARTIFACT_MIME_MISMATCH")
            plaintext.size.toLong() != metadata.byteCount ->
                PersistentArtifactValidation(false, "ARTIFACT_LENGTH_MISMATCH")
            else -> PersistentArtifactValidation(true, "STRUCTURAL_VALIDATION_PASSED")
        }
    }
}

data class SavedResultRevalidationResult(
    val savedResultId: SavedResultId,
    val valid: Boolean,
    val reasonCode: String,
    val assuranceClass: AssuranceClass,
)

class SavedResultRevalidator(
    private val repository: SavedResultRepository,
    private val validator: PersistentArtifactValidator = StructuralPersistentArtifactValidator,
    private val digester: ContentDigester = Sha256ContentDigester(),
    private val clock: StorageClock = SystemStorageClock,
) {
    suspend fun revalidate(savedResultId: SavedResultId): SavedResultRevalidationResult {
        val record = repository.findAny(savedResultId)
            ?: return SavedResultRevalidationResult(
                savedResultId,
                valid = false,
                reasonCode = StorageFailureReason.RECORD_NOT_FOUND.name,
                assuranceClass = AssuranceClass.AS_0_UNVERIFIED,
            )
        if (record.result.lifecycleState in setOf(
                SavedResultLifecycleState.DELETION_PENDING.name,
                SavedResultLifecycleState.DELETED.name,
                SavedResultLifecycleState.COMMITTING.name,
            )
        ) {
            return failed(savedResultId, StorageFailureReason.RECORD_NOT_SHAREABLE)
        }

        val structuralFailure = validateRecordLinkage(record)
        if (structuralFailure != null) return failed(savedResultId, structuralFailure)

        var ceiling = AssuranceClass.valueOf(record.result.originalVerifiedAssurance)
        try {
            record.artifacts.forEach { artifact ->
                repository.artifactStore.readFinal(record.result, artifact).use { plaintext ->
                    val decision = validator.validate(artifact, plaintext)
                    if (!decision.accepted) {
                        throw SavedResultStorageException(StorageFailureReason.VALIDATOR_REJECTED_ARTIFACT)
                    }
                    decision.assuranceCeiling?.let { ceiling = ceiling.lowerOf(it) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SavedResultStorageException) {
            return failed(savedResultId, error.reason)
        } catch (_: Exception) {
            return failed(savedResultId, StorageFailureReason.VALIDATOR_REJECTED_ARTIFACT)
        }

        if (ceiling == AssuranceClass.AS_0_UNVERIFIED) {
            return failed(savedResultId, StorageFailureReason.VALIDATOR_REJECTED_ARTIFACT)
        }
        val changed = repository.dao.markRevalidated(
            savedResultId.value,
            ceiling.name,
            clock.now().epochMillis,
        )
        return if (changed == 1) {
            SavedResultRevalidationResult(savedResultId, true, "REVALIDATION_PASSED", ceiling)
        } else {
            failed(savedResultId, StorageFailureReason.RECORD_NOT_FOUND)
        }
    }

    suspend fun requireValidForManagedShare(savedResultId: SavedResultId): SavedResultWithArtifacts {
        repository.dao.findShareable(savedResultId.value)?.let { return it }
        val result = revalidate(savedResultId)
        if (!result.valid) throw SavedResultStorageException(StorageFailureReason.RECORD_NOT_SHAREABLE)
        return repository.requireShareable(savedResultId)
    }

    suspend fun requireAllRevalidationAfterMigration(reasonCode: String = "STORAGE_MIGRATION_REVALIDATION_REQUIRED") {
        require(Regex("[A-Z][A-Z0-9_]{1,63}").matches(reasonCode))
        repository.dao.requireMigrationRevalidation(reasonCode)
    }

    private suspend fun failed(
        savedResultId: SavedResultId,
        reason: StorageFailureReason,
    ): SavedResultRevalidationResult {
        repository.quarantine(
            savedResultId,
            reason,
            migrationFailure = reason == StorageFailureReason.MIGRATION_REVALIDATION_REQUIRED,
        )
        return SavedResultRevalidationResult(
            savedResultId,
            valid = false,
            reasonCode = reason.name,
            assuranceClass = AssuranceClass.AS_0_UNVERIFIED,
        )
    }

    private fun validateRecordLinkage(record: SavedResultWithArtifacts): StorageFailureReason? {
        val result = record.result
        if (result.keyAlias.isBlank()) return StorageFailureReason.KEY_UNAVAILABLE
        if (result.originalVerifiedAssurance == AssuranceClass.AS_0_UNVERIFIED.name) {
            return StorageFailureReason.MIGRATION_REVALIDATION_REQUIRED
        }
        val expectedKinds = when (runCatching { OutputMode.valueOf(result.outputMode) }.getOrNull()) {
            OutputMode.TEXT -> setOf(ArtifactKind.CANONICAL_TEXT)
            OutputMode.REBUILT_IMAGE -> setOf(ArtifactKind.REBUILT_IMAGE)
            OutputMode.BOTH -> setOf(ArtifactKind.CANONICAL_TEXT, ArtifactKind.REBUILT_IMAGE)
            OutputMode.DERIVATIVE_IMAGE -> setOf(ArtifactKind.DERIVATIVE_IMAGE)
            null -> return StorageFailureReason.INVALID_VERIFIED_INPUT
        }
        val actualKinds = record.artifacts.mapNotNull { runCatching { ArtifactKind.valueOf(it.artifactKind) }.getOrNull() }.toSet()
        if (actualKinds != expectedKinds || record.artifacts.size != expectedKinds.size) {
            return StorageFailureReason.RECORD_NOT_FOUND
        }
        if (record.artifacts.map { it.artifactRevision }.distinct().size != 1 ||
            record.artifacts.singleRevisionOrNull() != result.verificationArtifactRevision
        ) {
            return StorageFailureReason.ARTIFACT_REVISION_MISMATCH
        }
        if (result.verificationSummaryReference == null || result.verificationReportVersion == null) {
            return StorageFailureReason.CANONICAL_REVISION_MISMATCH
        }
        val aggregate = record.artifacts.sortedBy { it.artifactKind }.joinToString("|") {
            "${it.artifactKind}:${it.contentDigest}:${it.byteCount}"
        }.encodeToByteArray()
        val matches = try {
            digester.digest(aggregate).sha256 == result.contentDigest
        } finally {
            aggregate.fill(0)
        }
        return if (matches) null else StorageFailureReason.PAYLOAD_DIGEST_MISMATCH
    }

    private fun List<SavedArtifactEntity>.singleRevisionOrNull(): Long? =
        map { it.artifactRevision }.distinct().singleOrNull()
}
