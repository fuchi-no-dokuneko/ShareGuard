package app.shareguard.core.storage

import app.shareguard.core.model.ImmutableList
import app.shareguard.core.model.SavedResultDeletionResult
import app.shareguard.core.model.SavedResultId
import app.shareguard.core.security.KeyAlias
import app.shareguard.core.security.LogicalKeyDeletionResult

enum class DeletionCheckpoint {
    AFTER_MARK_PENDING,
    AFTER_KEY_DELETE,
    AFTER_FILE_DELETE,
    BEFORE_METADATA_DELETE,
}

fun interface DeletionFaultInjector {
    fun onCheckpoint(checkpoint: DeletionCheckpoint)
}

object NoDeletionFaults : DeletionFaultInjector {
    override fun onCheckpoint(checkpoint: DeletionCheckpoint) = Unit
}

class SavedResultDeletionService(
    private val repository: SavedResultRepository,
    private val managedShareCache: ManagedShareCache? = null,
    private val faultInjector: DeletionFaultInjector = NoDeletionFaults,
) {
    suspend fun delete(savedResultId: SavedResultId): SavedResultDeletionResult =
        deleteBulk(listOf(savedResultId)).results.single()

    suspend fun deleteBulk(savedResultIds: Collection<SavedResultId>): BulkDeletionResult {
        val unique = savedResultIds.distinct()
        if (unique.isEmpty()) return BulkDeletionResult(emptyList())
        repository.dao.markDeletionPending(unique.map { it.value })
        return BulkDeletionResult(unique.map { deleteMarked(it) })
    }

    suspend fun deleteAll(): BulkDeletionResult =
        deleteBulk(repository.dao.allIds().map(::SavedResultId))

    internal suspend fun retryDeletionPending(savedResultId: SavedResultId): SavedResultDeletionResult =
        deleteMarked(savedResultId)

    private suspend fun deleteMarked(savedResultId: SavedResultId): SavedResultDeletionResult {
        val record = repository.findAny(savedResultId)
            ?: return complete(savedResultId)
        val remaining = linkedSetOf<String>()
        try {
            faultInjector.onCheckpoint(DeletionCheckpoint.AFTER_MARK_PENDING)

            val keyDeleted = if (record.result.keyAlias.isBlank()) {
                true
            } else {
                val alias = runCatching { KeyAlias(record.result.keyAlias) }.getOrNull()
                if (alias == null) {
                    true
                } else {
                    when (runCatching { repository.keyProvider.delete(alias) }.getOrNull()) {
                        LogicalKeyDeletionResult.DELETED,
                        LogicalKeyDeletionResult.ALREADY_ABSENT,
                        -> true
                        LogicalKeyDeletionResult.FAILED_BEST_EFFORT,
                        null,
                        -> false
                    }
                }
            }
            if (!keyDeleted) remaining += "ENCRYPTION_KEY"
            faultInjector.onCheckpoint(DeletionCheckpoint.AFTER_KEY_DELETE)

            val shareDeleted = managedShareCache?.clearForResult(savedResultId)
                ?: deleteTreeLogically(
                    repository.layout.shareDirectory(savedResultId),
                    repository.layout.managedShareRoot,
                )
            if (!shareDeleted) remaining += "SHARE_CACHE"

            val stagingDeleted = deleteTreeLogically(
                repository.layout.stagingDirectory(savedResultId),
                repository.layout.stagingRoot,
            )
            if (!stagingDeleted) remaining += "STAGING_ARTIFACTS"

            val persistentDeleted = deleteTreeLogically(
                repository.layout.resultDirectory(savedResultId),
                repository.layout.savedResultsRoot,
            )
            if (!persistentDeleted) {
                remaining += "PERSISTENT_ARTIFACTS"
                if (record.result.previewReference != null) remaining += "PREVIEW"
            }
            faultInjector.onCheckpoint(DeletionCheckpoint.AFTER_FILE_DELETE)

            if (remaining.isEmpty()) {
                faultInjector.onCheckpoint(DeletionCheckpoint.BEFORE_METADATA_DELETE)
                if (repository.dao.deleteMetadata(savedResultId.value) != 1 &&
                    repository.findAny(savedResultId) != null
                ) {
                    remaining += "METADATA"
                }
            }
        } catch (_: Exception) {
            remaining += "INTERRUPTED_OPERATION"
        }

        if (remaining.isEmpty() && repository.findAny(savedResultId) == null) return complete(savedResultId)
        if (remaining.isEmpty()) remaining += "METADATA"
        return SavedResultDeletionResult(
            savedResultId = savedResultId,
            completed = false,
            remainingReferenceCategories = ImmutableList.copyOf(remaining),
        )
    }

    private fun complete(savedResultId: SavedResultId) = SavedResultDeletionResult(
        savedResultId = savedResultId,
        completed = true,
        remainingReferenceCategories = ImmutableList.empty(),
    )
}
