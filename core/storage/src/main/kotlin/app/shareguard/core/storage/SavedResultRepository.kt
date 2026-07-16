package app.shareguard.core.storage

import app.shareguard.core.model.ArtifactKind
import app.shareguard.core.model.ContentDigest
import app.shareguard.core.model.DisplayLabel
import app.shareguard.core.model.SavedResult
import app.shareguard.core.model.SavedResultId
import app.shareguard.core.security.AesGcmAuthenticatedEncryption
import app.shareguard.core.security.AesGcmKeyProvider
import app.shareguard.core.security.ContentDigester
import app.shareguard.core.security.KeyAlias
import app.shareguard.core.security.LogicalKeyDeletionResult
import app.shareguard.core.security.SecretBytes
import app.shareguard.core.security.Sha256ContentDigester
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class SavedResultRepository(
    private val database: SavedResultDatabase,
    internal val layout: AppPrivateStorageLayout,
    internal val keyProvider: AesGcmKeyProvider,
    encryption: app.shareguard.core.security.AuthenticatedEncryption = AesGcmAuthenticatedEncryption(keyProvider),
    private val digester: ContentDigester = Sha256ContentDigester(),
    private val idGenerator: SavedResultIdGenerator = SecureSavedResultIdGenerator(),
    private val keyAliasGenerator: StorageKeyAliasGenerator = SecureStorageKeyAliasGenerator(),
    private val clock: StorageClock = SystemStorageClock,
    private val faultInjector: PersistenceFaultInjector = NoPersistenceFaults,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    internal val dao: SavedResultDao = database.savedResultDao()
    internal val artifactStore = EncryptedArtifactStore(layout, encryption, digester, ioDispatcher)

    suspend fun persistVerifiedResult(request: PersistVerifiedResultRequest): PersistedSavedResult {
        val savedResultId = allocateSavedResultId()
        val keyAlias = keyAliasGenerator.next()
        var metadataCommitted = false
        val staged = mutableListOf<StagedArtifact>()
        val finals = mutableListOf<FinalArtifact>()
        try {
            keyProvider.getOrCreate(keyAlias)
            request.outputBundle.artifacts.forEach { artifact ->
                coroutineContext.ensureActive()
                staged += artifactStore.stage(
                    binding = ArtifactBinding(
                        savedResultId = savedResultId,
                        kind = artifact.kind,
                        canonicalRevision = artifact.canonicalRevision.value,
                        artifactRevision = artifact.artifactRevision.value,
                        mimeType = artifact.mimeType,
                        contentDigest = artifact.digest,
                        byteCount = artifact.byteCount.value,
                    ),
                    keyAlias = keyAlias,
                    payload = request.artifactPayloads.getValue(artifact.kind),
                )
            }
            faultInjector.onCheckpoint(PersistenceCheckpoint.AFTER_STAGED_FILE_SYNC)

            staged.forEach { artifactStore.verifyStaged(it, keyAlias) }
            faultInjector.onCheckpoint(PersistenceCheckpoint.AFTER_STAGED_REOPEN)

            staged.forEach { finals += artifactStore.moveToFinal(it) }
            faultInjector.onCheckpoint(PersistenceCheckpoint.AFTER_FINAL_MOVE)

            val persistedAt = clock.now()
            val aggregateDigest = aggregateDigest(finals)
            val (resultEntity, artifactEntities) = buildCommittingEntities(
                request = request,
                savedResultId = savedResultId,
                keyAlias = keyAlias.value,
                finalArtifacts = finals,
                contentDigest = aggregateDigest,
                persistedAt = persistedAt,
            )
            try {
                dao.insertCommitting(resultEntity, artifactEntities)
                metadataCommitted = true
            } catch (error: Exception) {
                throw SavedResultStorageException(StorageFailureReason.METADATA_COMMIT_FAILED, error)
            }
            faultInjector.onCheckpoint(PersistenceCheckpoint.AFTER_METADATA_COMMIT)

            finals.forEach { artifactStore.reopenFinal(it, keyAlias).close() }
            faultInjector.onCheckpoint(PersistenceCheckpoint.AFTER_FINAL_REOPEN)
            faultInjector.onCheckpoint(PersistenceCheckpoint.BEFORE_VISIBILITY_COMMIT)

            if (dao.markCommitVisible(savedResultId.value, clock.now().epochMillis) != 1) {
                throw SavedResultStorageException(StorageFailureReason.VISIBILITY_COMMIT_FAILED)
            }
            val stored = dao.findShareable(savedResultId.value)
                ?: throw SavedResultStorageException(StorageFailureReason.VISIBILITY_COMMIT_FAILED)
            return PersistedSavedResult(stored.toModel(), durableWriteConfirmed = true)
        } catch (cancelled: CancellationException) {
            if (!metadataCommitted) cleanupUncommitted(savedResultId, keyAlias, staged, finals)
            throw cancelled
        } catch (error: SavedResultStorageException) {
            if (!metadataCommitted) cleanupUncommitted(savedResultId, keyAlias, staged, finals)
            throw error
        } catch (error: IllegalArgumentException) {
            if (!metadataCommitted) cleanupUncommitted(savedResultId, keyAlias, staged, finals)
            throw SavedResultStorageException(StorageFailureReason.INVALID_VERIFIED_INPUT, error)
        } catch (error: Exception) {
            if (!metadataCommitted) cleanupUncommitted(savedResultId, keyAlias, staged, finals)
            throw SavedResultStorageException(StorageFailureReason.UNEXPECTED_STORAGE_FAILURE, error)
        }
    }

    suspend fun listVisible(): List<SavedResult> = dao.listVisible().map(SavedResultWithArtifacts::toModel)

    suspend fun findVisible(savedResultId: SavedResultId): SavedResult? =
        dao.findShareable(savedResultId.value)?.toModel()

    suspend fun renameAndFavourite(
        savedResultId: SavedResultId,
        displayLabel: DisplayLabel,
        favourite: Boolean,
    ): SavedResult {
        require(dao.updateManagementMetadata(savedResultId.value, displayLabel.value, favourite) == 1) {
            "Saved Result is unavailable for management updates"
        }
        return dao.findShareable(savedResultId.value)?.toModel()
            ?: throw SavedResultStorageException(StorageFailureReason.RECORD_NOT_SHAREABLE)
    }

    /** Records only that a user-selected external copy was successfully written. */
    suspend fun noteExternalExport(savedResultId: SavedResultId): SavedResult {
        require(dao.markExternalExportKnown(savedResultId.value) == 1) {
            "Saved Result is unavailable for export updates"
        }
        return dao.findShareable(savedResultId.value)?.toModel()
            ?: throw SavedResultStorageException(StorageFailureReason.RECORD_NOT_SHAREABLE)
    }

    internal suspend fun findAny(savedResultId: SavedResultId): SavedResultWithArtifacts? =
        dao.findAny(savedResultId.value)

    internal suspend fun requireShareable(savedResultId: SavedResultId): SavedResultWithArtifacts =
        dao.findShareable(savedResultId.value)
            ?: throw SavedResultStorageException(StorageFailureReason.RECORD_NOT_SHAREABLE)

    internal suspend fun readShareableArtifact(
        savedResultId: SavedResultId,
        kind: ArtifactKind,
    ): Pair<SavedArtifactEntity, SecretBytes> {
        val record = requireShareable(savedResultId)
        val artifact = record.artifacts.singleOrNull { it.artifactKind == kind.name }
            ?: throw SavedResultStorageException(StorageFailureReason.RECORD_NOT_FOUND)
        return artifact to artifactStore.readFinal(record.result, artifact)
    }

    internal suspend fun quarantine(
        savedResultId: SavedResultId,
        reason: StorageFailureReason,
        checkedAt: Long = clock.now().epochMillis,
        migrationFailure: Boolean = false,
    ) {
        dao.quarantine(
            savedResultId = savedResultId.value,
            verificationState = app.shareguard.core.model.VerificationState.FAILED.name,
            integrityState = app.shareguard.core.model.IntegrityState.INVALID.name,
            migrationState = if (migrationFailure) {
                app.shareguard.core.model.MigrationState.FAILED.name
            } else {
                app.shareguard.core.model.MigrationState.CURRENT.name
            },
            reasonCode = reason.name,
            checkedAtMillis = checkedAt,
        )
    }

    private suspend fun allocateSavedResultId(): SavedResultId {
        repeat(MAXIMUM_ID_ALLOCATION_ATTEMPTS) {
            val candidate = idGenerator.next()
            if (dao.findAny(candidate.value) == null &&
                !layout.hasAnyResultFootprint(candidate)
            ) {
                return candidate
            }
        }
        throw SavedResultStorageException(StorageFailureReason.METADATA_COMMIT_FAILED)
    }

    private fun aggregateDigest(finals: List<FinalArtifact>): ContentDigest {
        val canonical = finals.sortedBy { it.binding.kind.name }.joinToString("|") { final ->
            "${final.binding.kind.name}:${final.binding.contentDigest.sha256}:${final.binding.byteCount}"
        }.encodeToByteArray()
        return try {
            digester.digest(canonical)
        } finally {
            canonical.fill(0)
        }
    }

    private suspend fun cleanupUncommitted(
        savedResultId: SavedResultId,
        keyAlias: KeyAlias,
        staged: List<StagedArtifact>,
        finals: List<FinalArtifact>,
    ) = withContext(NonCancellable + ioDispatcher) {
        (staged.map { it.stagedFile } + finals.map { it.file }).forEach { runCatching { it.delete() } }
        runCatching { deleteTreeLogically(layout.stagingDirectory(savedResultId), layout.stagingRoot) }
        runCatching { deleteTreeLogically(layout.resultDirectory(savedResultId), layout.savedResultsRoot) }
        runCatching { keyProvider.delete(keyAlias) }
    }

    private companion object {
        const val MAXIMUM_ID_ALLOCATION_ATTEMPTS = 32
    }
}

internal fun deleteTreeLogically(target: File, approvedRoot: File): Boolean {
    if (!target.exists()) return true
    val targetCanonical = runCatching { target.canonicalFile }.getOrNull() ?: return false
    val rootCanonical = runCatching { approvedRoot.canonicalFile }.getOrNull() ?: return false
    if (target.isSymbolicLinkCompat() || !targetCanonical.isStrictlyInside(rootCanonical)) return false
    val children = target.listFiles() ?: return false
    var complete = true
    children.forEach { child ->
        val canonical = runCatching { child.canonicalFile }.getOrNull()
        if (child.isSymbolicLinkCompat()) {
            if (!child.delete() && child.exists()) complete = false
        } else if (canonical == null || !canonical.isStrictlyInside(targetCanonical)) {
            complete = false
        } else if (child.isDirectory) {
            if (!deleteTreeLogically(child, targetCanonical)) complete = false
        } else if (!child.delete() && child.exists()) {
            complete = false
        }
    }
    if (!target.delete() && target.exists()) complete = false
    return complete
}
