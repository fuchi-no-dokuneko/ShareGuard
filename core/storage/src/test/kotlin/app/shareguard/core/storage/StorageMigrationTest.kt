package app.shareguard.core.storage

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shareguard.core.model.MigrationState
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StorageMigrationTest {
    @Test
    fun explicitV1ToV2MigrationQuarantinesLegacyRecordsForRevalidation() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "storage-migration-${COUNTER.incrementAndGet()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            legacy.execSQL(LEGACY_RESULTS_TABLE)
            legacy.execSQL(CURRENT_ARTIFACTS_TABLE)
            legacy.execSQL(
                "CREATE INDEX index_saved_artifacts_saved_result_id ON saved_artifacts(saved_result_id)",
            )
            legacy.execSQL(
                """
                INSERT INTO saved_results (
                  saved_result_id, schema_version, display_label, output_mode, canonical_revision,
                  assurance_class, assurance_rationale_summary, verification_state,
                  verification_summary_reference, verification_report_version,
                  verification_artifact_revision, verification_statuses, unresolved_finding_count,
                  retained_source_region_count, import_wall_clock_millis, import_monotonic_nanos,
                  boot_session_reference, import_clock_confidence, persisted_at_wall_clock_millis,
                  content_digest, storage_byte_count, preview_reference, favourite,
                  created_by_app_build, lifecycle_state
                ) VALUES (
                  'result-legacy', 1, 'Legacy result', 'TEXT', 1,
                  'AS_2_REVIEWED_CANONICAL_TEXT', 'Legacy verified summary', 'VERIFIED',
                  'verification-legacy', 1, 1, 'SOURCE_REFERENCE=PASS', 0,
                  0, 1000, NULL, NULL, 'WALL_CLOCK_RESTORED', 2000,
                  '${"0".repeat(64)}', 4, NULL, 0, 'build-legacy', 'AVAILABLE'
                )
                """.trimIndent(),
            )
            legacy.version = 1
        }

        val database = Room.databaseBuilder(context, SavedResultDatabase::class.java, name)
            .addMigrations(SavedResultDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val rows = database.savedResultDao().listAll()
            assertThat(rows).hasSize(1)
            assertThat(rows.single().result.visibilityConfirmed).isFalse()
            assertThat(rows.single().result.lifecycleState).isEqualTo("QUARANTINED")
            assertThat(rows.single().result.migrationState)
                .isEqualTo(MigrationState.REVALIDATION_REQUIRED.name)
            assertThat(database.savedResultDao().listVisible()).isEmpty()
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migrationHookQuarantinesThenNormalRevalidationRestoresOnlyTheOriginalBytes() = runTest {
        StorageTestFixture().use { fixture ->
            val id = fixture.persistText("immutable bytes across metadata migration").savedResult.savedResultId
            val record = fixture.repository.findAny(id)!!
            val file = fixture.layout.resolvePersistent(record.artifacts.single().relativePath)
            val before = file.readBytes()

            fixture.revalidator.requireAllRevalidationAfterMigration()
            assertThat(fixture.repository.listVisible()).isEmpty()

            val result = fixture.revalidator.revalidate(id)
            assertThat(result.valid).isTrue()
            assertThat(file.readBytes()).isEqualTo(before)
            assertThat(fixture.repository.findVisible(id)).isNotNull()
        }
    }

    private companion object {
        val COUNTER = AtomicInteger()

        val LEGACY_RESULTS_TABLE =
            """
            CREATE TABLE saved_results (
              saved_result_id TEXT NOT NULL PRIMARY KEY,
              schema_version INTEGER NOT NULL,
              display_label TEXT NOT NULL,
              output_mode TEXT NOT NULL,
              canonical_revision INTEGER NOT NULL,
              assurance_class TEXT NOT NULL,
              assurance_rationale_summary TEXT NOT NULL,
              verification_state TEXT NOT NULL,
              verification_summary_reference TEXT,
              verification_report_version INTEGER,
              verification_artifact_revision INTEGER,
              verification_statuses TEXT,
              unresolved_finding_count INTEGER,
              retained_source_region_count INTEGER,
              import_wall_clock_millis INTEGER NOT NULL,
              import_monotonic_nanos INTEGER,
              boot_session_reference TEXT,
              import_clock_confidence TEXT NOT NULL,
              persisted_at_wall_clock_millis INTEGER NOT NULL,
              content_digest TEXT NOT NULL,
              storage_byte_count INTEGER NOT NULL,
              preview_reference TEXT,
              favourite INTEGER NOT NULL,
              created_by_app_build TEXT NOT NULL,
              lifecycle_state TEXT NOT NULL
            )
            """.trimIndent()

        val CURRENT_ARTIFACTS_TABLE =
            """
            CREATE TABLE saved_artifacts (
              saved_result_id TEXT NOT NULL,
              artifact_kind TEXT NOT NULL,
              artifact_reference TEXT NOT NULL,
              relative_path TEXT NOT NULL,
              mime_type TEXT NOT NULL,
              content_digest TEXT NOT NULL,
              artifact_revision INTEGER NOT NULL,
              byte_count INTEGER NOT NULL,
              retained_region_ids TEXT NOT NULL,
              dependency_types TEXT NOT NULL,
              PRIMARY KEY(saved_result_id, artifact_kind),
              FOREIGN KEY(saved_result_id) REFERENCES saved_results(saved_result_id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
    }
}
