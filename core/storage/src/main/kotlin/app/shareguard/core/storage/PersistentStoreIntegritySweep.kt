package app.shareguard.core.storage

import app.shareguard.core.model.MigrationState
import app.shareguard.core.model.SavedResultId
import app.shareguard.core.model.SavedResultLifecycleState
import java.io.File
import java.nio.file.Files

data class MigrationPreparation(
    val readyForRevalidation: Boolean,
    val reasonCode: String,
) {
    init { require(Regex("[A-Z][A-Z0-9_]{1,63}").matches(reasonCode)) }
}

fun interface SavedResultMigrationHook {
    /** May migrate metadata only. Artifact-byte changes remain invalid until normal revalidation succeeds. */
    suspend fun prepare(record: SavedResultWithArtifacts): MigrationPreparation
}

object MetadataOnlyMigrationHook : SavedResultMigrationHook {
    override suspend fun prepare(record: SavedResultWithArtifacts): MigrationPreparation =
        MigrationPreparation(true, "METADATA_MIGRATION_READY")
}

data class IntegritySweepReport(
    val inspectedRecords: Int,
    val revalidatedRecords: Int,
    val quarantinedRecords: Int,
    val completedPendingDeletions: Int,
    val invalidPreviewsRemoved: Int,
    val orphanFileGroupsRemoved: Int,
    val incompleteTransactionsFound: Int,
    val unresolvedReferenceGroups: Int,
    val reasonCodes: List<String>,
)

class PersistentStoreIntegritySweep(
    private val repository: SavedResultRepository,
    private val revalidator: SavedResultRevalidator,
    private val deletionService: SavedResultDeletionService,
    private val previewRepository: SavedResultPreviewRepository? = null,
    private val managedShareCache: ManagedShareCache? = null,
    private val migrationHook: SavedResultMigrationHook = MetadataOnlyMigrationHook,
) {
    suspend fun run(): IntegritySweepReport {
        var revalidated = 0
        var quarantined = 0
        var deleted = 0
        var invalidPreviews = 0
        var orphanGroups = 0
        var incomplete = 0
        var unresolved = 0
        val reasons = linkedSetOf<String>()

        if (managedShareCache?.clearAllOnStartup() == false) {
            unresolved += 1
            reasons += "SHARE_CACHE_CLEANUP_INCOMPLETE"
        }

        val records = repository.dao.listAll()
        records.forEach { record ->
            val id = SavedResultId(record.result.savedResultId)
            when (record.result.lifecycleState) {
                SavedResultLifecycleState.DELETION_PENDING.name -> {
                    val result = deletionService.retryDeletionPending(id)
                    if (result.completed) deleted += 1 else {
                        unresolved += 1
                        reasons += "DELETION_RETRY_INCOMPLETE"
                    }
                }
                SavedResultLifecycleState.COMMITTING.name -> {
                    incomplete += 1
                    quarantined += 1
                    reasons += "INCOMPLETE_TRANSACTION_QUARANTINED"
                    repository.quarantine(id, StorageFailureReason.FINAL_REOPEN_FAILED)
                }
                else -> {
                    if (record.result.migrationState != MigrationState.CURRENT.name) {
                        val preparation = runCatching { migrationHook.prepare(record) }.getOrNull()
                        if (preparation?.readyForRevalidation != true) {
                            quarantined += 1
                            reasons += preparation?.reasonCode ?: "MIGRATION_HOOK_FAILED"
                            repository.quarantine(
                                id,
                                StorageFailureReason.MIGRATION_REVALIDATION_REQUIRED,
                                migrationFailure = true,
                            )
                            return@forEach
                        }
                    }
                    val result = revalidator.revalidate(id)
                    if (result.valid) {
                        revalidated += 1
                        if (previewRepository != null && !previewRepository.validate(id)) {
                            invalidPreviews += 1
                            reasons += "INVALID_PREVIEW_REMOVED"
                        }
                    } else {
                        quarantined += 1
                        reasons += result.reasonCode
                    }
                }
            }
        }

        val knownIds = repository.dao.allIds().toSet()
        repository.layout.savedResultsRoot.listFiles().orEmpty().forEach { candidate ->
            if (candidate.name !in knownIds) {
                if (deleteTreeLogically(candidate, repository.layout.savedResultsRoot)) {
                    orphanGroups += 1
                    reasons += "ORPHAN_ARTIFACT_GROUP_REMOVED"
                } else {
                    unresolved += 1
                    reasons += "ORPHAN_ARTIFACT_GROUP_UNRESOLVED"
                }
            }
        }
        repository.layout.stagingRoot.listFiles().orEmpty().forEach { candidate ->
            incomplete += 1
            if (deleteTreeLogically(candidate, repository.layout.stagingRoot)) {
                orphanGroups += 1
                reasons += "ORPHAN_STAGING_GROUP_REMOVED"
            } else {
                unresolved += 1
                reasons += "ORPHAN_STAGING_GROUP_UNRESOLVED"
            }
        }

        repository.dao.listAll().forEach { record ->
            val id = SavedResultId(record.result.savedResultId)
            val expected = buildSet<File> {
                record.artifacts.forEach { artifact ->
                    runCatching { add(repository.layout.resolvePersistent(artifact.relativePath).canonicalFile) }
                }
                repository.dao.findPreview(id.value)?.let { preview ->
                    runCatching { add(repository.layout.resolvePersistent(preview.relativePath).canonicalFile) }
                }
            }
            val directory = repository.layout.resultDirectory(id)
            inventoryLeaves(directory).forEach { leaf ->
                val canonical = runCatching { leaf.canonicalFile }.getOrNull()
                if (canonical == null || !canonical.toPath().startsWith(directory.canonicalFile.toPath())) {
                    unresolved += 1
                    reasons += "UNAPPROVED_REFERENCE_UNRESOLVED"
                } else if (canonical !in expected && leaf.exists()) {
                    if (leaf.delete()) {
                        orphanGroups += 1
                        reasons += "UNREFERENCED_FILE_REMOVED"
                    } else {
                        unresolved += 1
                        reasons += "UNREFERENCED_FILE_UNRESOLVED"
                    }
                }
            }
        }

        return IntegritySweepReport(
            inspectedRecords = records.size,
            revalidatedRecords = revalidated,
            quarantinedRecords = quarantined,
            completedPendingDeletions = deleted,
            invalidPreviewsRemoved = invalidPreviews,
            orphanFileGroupsRemoved = orphanGroups,
            incompleteTransactionsFound = incomplete,
            unresolvedReferenceGroups = unresolved,
            reasonCodes = reasons.toList(),
        )
    }

    private fun inventoryLeaves(directory: File): List<File> {
        if (!directory.exists()) return emptyList()
        val output = mutableListOf<File>()
        fun visit(file: File) {
            if (Files.isSymbolicLink(file.toPath()) || !file.isDirectory) {
                output += file
                return
            }
            file.listFiles().orEmpty().forEach(::visit)
        }
        visit(directory)
        return output
    }
}
