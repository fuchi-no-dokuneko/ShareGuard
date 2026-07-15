package app.shareguard.core.storage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction

@Entity(
    tableName = "saved_results",
    indices = [
        Index(value = ["lifecycle_state", "visibility_confirmed"]),
        Index(value = ["migration_state"]),
    ],
)
data class SavedResultEntity(
    @ColumnInfo(name = "saved_result_id")
    @androidx.room.PrimaryKey
    val savedResultId: String,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "display_label") val displayLabel: String,
    @ColumnInfo(name = "output_mode") val outputMode: String,
    @ColumnInfo(name = "canonical_revision") val canonicalRevision: Long,
    @ColumnInfo(name = "assurance_class") val assuranceClass: String,
    @ColumnInfo(name = "original_verified_assurance") val originalVerifiedAssurance: String,
    @ColumnInfo(name = "assurance_rationale_summary") val assuranceRationaleSummary: String,
    @ColumnInfo(name = "verification_state") val verificationState: String,
    @ColumnInfo(name = "verification_summary_reference") val verificationSummaryReference: String?,
    @ColumnInfo(name = "verification_report_version") val verificationReportVersion: Int?,
    @ColumnInfo(name = "verification_artifact_revision") val verificationArtifactRevision: Long?,
    @ColumnInfo(name = "verification_statuses") val verificationStatuses: String?,
    @ColumnInfo(name = "unresolved_finding_count") val unresolvedFindingCount: Int?,
    @ColumnInfo(name = "retained_source_region_count") val retainedSourceRegionCount: Int?,
    @ColumnInfo(name = "import_wall_clock_millis") val importWallClockMillis: Long,
    @ColumnInfo(name = "import_monotonic_nanos") val importMonotonicNanos: Long?,
    @ColumnInfo(name = "boot_session_reference") val bootSessionReference: String?,
    @ColumnInfo(name = "import_clock_confidence") val importClockConfidence: String,
    @ColumnInfo(name = "persisted_at_wall_clock_millis") val persistedAtWallClockMillis: Long,
    @ColumnInfo(name = "last_integrity_check_at_millis") val lastIntegrityCheckAtMillis: Long?,
    @ColumnInfo(name = "content_digest") val contentDigest: String,
    @ColumnInfo(name = "storage_byte_count") val storageByteCount: Long,
    @ColumnInfo(name = "preview_reference") val previewReference: String?,
    @ColumnInfo(name = "favourite") val favourite: Boolean,
    @ColumnInfo(name = "created_by_app_build") val createdByAppBuild: String,
    @ColumnInfo(name = "migration_state") val migrationState: String,
    @ColumnInfo(name = "lifecycle_state") val lifecycleState: String,
    @ColumnInfo(name = "integrity_state") val integrityState: String,
    @ColumnInfo(name = "external_export_known") val externalExportKnown: Boolean,
    @ColumnInfo(name = "key_alias") val keyAlias: String,
    @ColumnInfo(name = "visibility_confirmed") val visibilityConfirmed: Boolean,
    @ColumnInfo(name = "revalidation_reason_code") val revalidationReasonCode: String?,
)

@Entity(
    tableName = "saved_artifacts",
    primaryKeys = ["saved_result_id", "artifact_kind"],
    foreignKeys = [
        ForeignKey(
            entity = SavedResultEntity::class,
            parentColumns = ["saved_result_id"],
            childColumns = ["saved_result_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["saved_result_id"])],
)
data class SavedArtifactEntity(
    @ColumnInfo(name = "saved_result_id") val savedResultId: String,
    @ColumnInfo(name = "artifact_kind") val artifactKind: String,
    @ColumnInfo(name = "artifact_reference") val artifactReference: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "content_digest") val contentDigest: String,
    @ColumnInfo(name = "artifact_revision") val artifactRevision: Long,
    @ColumnInfo(name = "byte_count") val byteCount: Long,
    @ColumnInfo(name = "retained_region_ids") val retainedRegionIds: String,
    @ColumnInfo(name = "dependency_types") val dependencyTypes: String,
)

@Entity(
    tableName = "saved_previews",
    foreignKeys = [
        ForeignKey(
            entity = SavedResultEntity::class,
            parentColumns = ["saved_result_id"],
            childColumns = ["saved_result_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["saved_result_id"], unique = true)],
)
data class SavedPreviewEntity(
    @androidx.room.PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "preview_id") val previewId: Long = 0,
    @ColumnInfo(name = "saved_result_id") val savedResultId: String,
    @ColumnInfo(name = "preview_reference") val previewReference: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "source_artifact_kind") val sourceArtifactKind: String,
    @ColumnInfo(name = "source_artifact_digest") val sourceArtifactDigest: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "byte_count") val byteCount: Long,
)

data class SavedResultWithArtifacts(
    @Embedded val result: SavedResultEntity,
    @Relation(
        parentColumn = "saved_result_id",
        entityColumn = "saved_result_id",
    )
    val artifacts: List<SavedArtifactEntity>,
)

@Dao
abstract class SavedResultDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertResult(entity: SavedResultEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertArtifacts(entities: List<SavedArtifactEntity>)

    @Transaction
    open suspend fun insertCommitting(
        result: SavedResultEntity,
        artifacts: List<SavedArtifactEntity>,
    ) {
        insertResult(result)
        insertArtifacts(artifacts)
    }

    @Transaction
    @Query("SELECT * FROM saved_results WHERE saved_result_id = :savedResultId")
    abstract suspend fun findAny(savedResultId: String): SavedResultWithArtifacts?

    @Transaction
    @Query(
        """
        SELECT * FROM saved_results
        WHERE saved_result_id = :savedResultId
          AND visibility_confirmed = 1
          AND lifecycle_state = 'AVAILABLE'
          AND verification_state = 'VERIFIED'
          AND integrity_state = 'VALID'
          AND migration_state = 'CURRENT'
        """,
    )
    abstract suspend fun findShareable(savedResultId: String): SavedResultWithArtifacts?

    @Transaction
    @Query(
        """
        SELECT * FROM saved_results
        WHERE visibility_confirmed = 1
          AND lifecycle_state = 'AVAILABLE'
          AND verification_state = 'VERIFIED'
          AND integrity_state = 'VALID'
          AND migration_state = 'CURRENT'
        ORDER BY favourite DESC, persisted_at_wall_clock_millis DESC, saved_result_id ASC
        """,
    )
    abstract suspend fun listVisible(): List<SavedResultWithArtifacts>

    @Transaction
    @Query("SELECT * FROM saved_results ORDER BY saved_result_id ASC")
    abstract suspend fun listAll(): List<SavedResultWithArtifacts>

    @Query("SELECT saved_result_id FROM saved_results ORDER BY saved_result_id ASC")
    abstract suspend fun allIds(): List<String>

    @Query(
        """
        UPDATE saved_results SET
          visibility_confirmed = 1,
          lifecycle_state = 'AVAILABLE',
          verification_state = 'VERIFIED',
          integrity_state = 'VALID',
          migration_state = 'CURRENT',
          revalidation_reason_code = NULL,
          last_integrity_check_at_millis = :checkedAtMillis
        WHERE saved_result_id = :savedResultId AND lifecycle_state = 'COMMITTING'
        """,
    )
    abstract suspend fun markCommitVisible(savedResultId: String, checkedAtMillis: Long): Int

    @Query(
        """
        UPDATE saved_results SET
          visibility_confirmed = 0,
          lifecycle_state = 'QUARANTINED',
          verification_state = :verificationState,
          integrity_state = :integrityState,
          migration_state = :migrationState,
          assurance_class = 'AS_0_UNVERIFIED',
          revalidation_reason_code = :reasonCode,
          last_integrity_check_at_millis = :checkedAtMillis
        WHERE saved_result_id = :savedResultId
          AND lifecycle_state NOT IN ('DELETION_PENDING', 'DELETED')
        """,
    )
    abstract suspend fun quarantine(
        savedResultId: String,
        verificationState: String,
        integrityState: String,
        migrationState: String,
        reasonCode: String,
        checkedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE saved_results SET
          visibility_confirmed = 1,
          lifecycle_state = 'AVAILABLE',
          verification_state = 'VERIFIED',
          integrity_state = 'VALID',
          migration_state = 'CURRENT',
          assurance_class = :assuranceClass,
          revalidation_reason_code = NULL,
          last_integrity_check_at_millis = :checkedAtMillis
        WHERE saved_result_id = :savedResultId
          AND lifecycle_state IN ('AVAILABLE', 'QUARANTINED', 'INVALIDATED')
        """,
    )
    abstract suspend fun markRevalidated(
        savedResultId: String,
        assuranceClass: String,
        checkedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE saved_results SET
          visibility_confirmed = 0,
          lifecycle_state = 'DELETION_PENDING',
          verification_state = 'INVALIDATED',
          integrity_state = 'INVALID',
          assurance_class = 'AS_0_UNVERIFIED',
          revalidation_reason_code = 'DELETION_PENDING'
        WHERE saved_result_id IN (:savedResultIds)
        """,
    )
    abstract suspend fun markDeletionPending(savedResultIds: List<String>): Int

    @Query("DELETE FROM saved_results WHERE saved_result_id = :savedResultId")
    abstract suspend fun deleteMetadata(savedResultId: String): Int

    @Query(
        """
        UPDATE saved_results SET display_label = :displayLabel, favourite = :favourite
        WHERE saved_result_id = :savedResultId
          AND visibility_confirmed = 1
          AND lifecycle_state = 'AVAILABLE'
          AND verification_state = 'VERIFIED'
          AND integrity_state = 'VALID'
          AND migration_state = 'CURRENT'
        """,
    )
    abstract suspend fun updateManagementMetadata(
        savedResultId: String,
        displayLabel: String,
        favourite: Boolean,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertPreview(entity: SavedPreviewEntity)

    @Transaction
    open suspend fun replacePreview(entity: SavedPreviewEntity) {
        upsertPreview(entity)
        setPreviewReference(entity.savedResultId, entity.previewReference)
    }

    @Query("SELECT * FROM saved_previews WHERE saved_result_id = :savedResultId")
    abstract suspend fun findPreview(savedResultId: String): SavedPreviewEntity?

    @Query("DELETE FROM saved_previews WHERE saved_result_id = :savedResultId")
    abstract suspend fun deletePreview(savedResultId: String): Int

    @Query(
        "UPDATE saved_results SET preview_reference = :previewReference WHERE saved_result_id = :savedResultId",
    )
    abstract suspend fun setPreviewReference(savedResultId: String, previewReference: String?): Int

    @Query(
        """
        UPDATE saved_results SET
          visibility_confirmed = 0,
          lifecycle_state = 'QUARANTINED',
          verification_state = 'REVALIDATION_REQUIRED',
          integrity_state = 'STALE',
          migration_state = 'REVALIDATION_REQUIRED',
          assurance_class = 'AS_0_UNVERIFIED',
          revalidation_reason_code = :reasonCode
        WHERE lifecycle_state NOT IN ('DELETION_PENDING', 'DELETED')
        """,
    )
    abstract suspend fun requireMigrationRevalidation(reasonCode: String): Int

}
