package app.shareguard.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SavedResultEntity::class, SavedArtifactEntity::class, SavedPreviewEntity::class],
    version = SavedResultDatabase.VERSION,
    exportSchema = true,
)
abstract class SavedResultDatabase : RoomDatabase() {
    abstract fun savedResultDao(): SavedResultDao

    companion object {
        const val VERSION: Int = 2
        const val DEFAULT_NAME: String = "saved-results.db"

        /**
         * Version 2 introduces per-result key linkage and explicit quarantine/revalidation state. Legacy
         * records are deliberately not promoted: migration leaves them quarantined until PST-004 succeeds.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE saved_results ADD COLUMN original_verified_assurance TEXT NOT NULL DEFAULT 'AS_0_UNVERIFIED'",
                )
                db.execSQL(
                    "ALTER TABLE saved_results ADD COLUMN last_integrity_check_at_millis INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE saved_results ADD COLUMN migration_state TEXT NOT NULL DEFAULT 'REVALIDATION_REQUIRED'",
                )
                db.execSQL(
                    "ALTER TABLE saved_results ADD COLUMN integrity_state TEXT NOT NULL DEFAULT 'STALE'",
                )
                db.execSQL(
                    "ALTER TABLE saved_results ADD COLUMN external_export_known INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE saved_results ADD COLUMN key_alias TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE saved_results ADD COLUMN visibility_confirmed INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE saved_results ADD COLUMN revalidation_reason_code TEXT DEFAULT 'SCHEMA_MIGRATION_REVALIDATION_REQUIRED'",
                )
                db.execSQL(
                    "UPDATE saved_results SET lifecycle_state = 'QUARANTINED', verification_state = 'REVALIDATION_REQUIRED', assurance_class = 'AS_0_UNVERIFIED'",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_saved_results_lifecycle_state_visibility_confirmed ON saved_results(lifecycle_state, visibility_confirmed)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_saved_results_migration_state ON saved_results(migration_state)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS saved_previews (
                      preview_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      saved_result_id TEXT NOT NULL,
                      preview_reference TEXT NOT NULL,
                      relative_path TEXT NOT NULL,
                      source_artifact_kind TEXT NOT NULL,
                      source_artifact_digest TEXT NOT NULL,
                      mime_type TEXT NOT NULL,
                      byte_count INTEGER NOT NULL,
                      FOREIGN KEY(saved_result_id) REFERENCES saved_results(saved_result_id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_saved_previews_saved_result_id ON saved_previews(saved_result_id)",
                )
            }
        }

        fun open(
            context: Context,
            name: String = DEFAULT_NAME,
        ): SavedResultDatabase = Room.databaseBuilder(
            context.applicationContext,
            SavedResultDatabase::class.java,
            name,
        )
            .addMigrations(MIGRATION_1_2)
            .build()
    }
}
