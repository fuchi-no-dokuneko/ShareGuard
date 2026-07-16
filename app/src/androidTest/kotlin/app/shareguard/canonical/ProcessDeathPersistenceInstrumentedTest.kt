package app.shareguard.canonical

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import app.shareguard.core.model.DisplayLabel
import app.shareguard.core.model.DurationMillis
import app.shareguard.core.security.InMemoryAesGcmKeyProvider
import app.shareguard.core.session.AndroidMonotonicClockSource
import app.shareguard.core.session.FileSessionWorkspaceManager
import app.shareguard.core.session.ImportAnchorRecorder
import app.shareguard.core.session.SnapshotLimits
import app.shareguard.core.session.SystemWallClockSource
import app.shareguard.core.storage.AppPrivateStorageLayout
import app.shareguard.core.storage.ManagedShareCache
import app.shareguard.core.storage.PersistenceCheckpoint
import app.shareguard.core.storage.PersistenceFaultInjector
import app.shareguard.core.storage.PersistentStoreIntegritySweep
import app.shareguard.core.storage.SavedResultDatabase
import app.shareguard.core.storage.SavedResultDeletionService
import app.shareguard.core.storage.SavedResultRepository
import app.shareguard.core.storage.SavedResultRevalidator
import app.shareguard.core.storage.ShareCachePolicy
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A shell-orchestrated, two-process probe. The seed invocation is expected to die at metadata commit. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ProcessDeathPersistenceInstrumentedTest {
    @Test
    fun dieImmediatelyAfterMetadataCommit(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        cleanupProbe(context)
        val preferences = context.getSharedPreferences(PROBE_PREFERENCES, Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putBoolean(KEY_SEED_STARTED, true).commit())
        val layout = probeLayout(context)
        val database = SavedResultDatabase.open(context, DATABASE_NAME)
        val keyProvider = InMemoryAesGcmKeyProvider()
        val repository = SavedResultRepository(
            database = database,
            layout = layout,
            keyProvider = keyProvider,
            faultInjector = PersistenceFaultInjector { checkpoint ->
                if (checkpoint == PersistenceCheckpoint.AFTER_METADATA_COMMIT) {
                    preferences.edit().putBoolean(KEY_METADATA_COMMITTED, true).commit()
                    Process.killProcess(Process.myPid())
                    error("PROCESS_DEATH_DID_NOT_TERMINATE")
                }
            },
        )
        val session = sessionManager(context).startSession().session
        val snapshot = session.snapshots.sealAcceptedDirectText("approved process death persistence probe")
        val workflow = CanonicalTextWorkflow(context, repository, cleanupEvidence = { true })
        val review = workflow.inspect("approved process death persistence probe")
        val approved = workflow.approve(review, allReviewItemsApproved = !review.requiresReview)

        workflow.verifyAndPersist(
            session = session,
            sourceHandle = snapshot.descriptor.sourceHandle,
            importAnchor = snapshot.descriptor.importAnchor,
            plan = approved,
            displayLabel = DisplayLabel("Process death probe"),
            semanticDiffApproved = true,
            assuranceConsequenceApproved = true,
        )
        error("PROCESS_DEATH_CHECKPOINT_NOT_REACHED")
    }

    @Test
    fun freshProcessQuarantinesTheInterruptedCommitAndPurgesItsTransientSource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(PROBE_PREFERENCES, Context.MODE_PRIVATE)
        assertTrue("seed phase did not start", preferences.getBoolean(KEY_SEED_STARTED, false))
        assertTrue("process did not reach the durable metadata checkpoint", preferences.getBoolean(KEY_METADATA_COMMITTED, false))
        val layout = probeLayout(context)
        val database = SavedResultDatabase.open(context, DATABASE_NAME)
        val keyProvider = InMemoryAesGcmKeyProvider()
        try {
            val repository = SavedResultRepository(database, layout, keyProvider)
            assertTrue("an interrupted commit became normally visible", repository.listVisible().isEmpty())
            val revalidator = SavedResultRevalidator(repository)
            val shareCache = ManagedShareCache(
                repository,
                revalidator,
                ShareCachePolicy(DurationMillis(60_000L)),
            )
            val deletionService = SavedResultDeletionService(repository, shareCache)
            val report = PersistentStoreIntegritySweep(
                repository,
                revalidator,
                deletionService,
                managedShareCache = shareCache,
            ).run()
            assertEquals(1, report.incompleteTransactionsFound)
            assertEquals(1, report.quarantinedRecords)
            assertTrue(repository.listVisible().isEmpty())

            val cleanup = sessionManager(context).purgeStaleSessions()
            assertTrue(cleanup.attempted >= 1)
            assertEquals(0, cleanup.failed)
            assertFalse(probeSessionRoot(context).walkTopDown().any { it.isFile })
        } finally {
            database.close()
            keyProvider.close()
            cleanupProbe(context)
        }
    }

    private fun sessionManager(context: Context) = FileSessionWorkspaceManager(
        workspaceRoot = probeSessionRoot(context),
        importAnchorRecorder = ImportAnchorRecorder(
            wallClock = SystemWallClockSource,
            monotonicClock = AndroidMonotonicClockSource,
            bootSessionReferenceSource = EstimatedBootSessionReferenceSource(),
        ),
        snapshotLimits = SnapshotLimits(1024L * 1024L),
        staleAfterMillis = 0L,
        wallClock = SystemWallClockSource,
    )

    private fun probeLayout(context: Context) = AppPrivateStorageLayout(
        File(context.noBackupFilesDir, PERSISTENT_DIRECTORY),
        File(context.cacheDir, CACHE_DIRECTORY),
    )

    private fun probeSessionRoot(context: Context) = File(context.cacheDir, SESSION_DIRECTORY)

    private fun cleanupProbe(context: Context) {
        context.deleteDatabase(DATABASE_NAME)
        File(context.noBackupFilesDir, PERSISTENT_DIRECTORY).deleteRecursively()
        File(context.cacheDir, CACHE_DIRECTORY).deleteRecursively()
        probeSessionRoot(context).deleteRecursively()
        context.getSharedPreferences(PROBE_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private companion object {
        const val DATABASE_NAME = "process-death-persistence-probe-v1.db"
        const val PERSISTENT_DIRECTORY = "process-death-persistence-v1"
        const val CACHE_DIRECTORY = "process-death-cache-v1"
        const val SESSION_DIRECTORY = "process-death-session-v1"
        const val PROBE_PREFERENCES = "process-death-persistence-probe-v1"
        const val KEY_SEED_STARTED = "seed_started"
        const val KEY_METADATA_COMMITTED = "metadata_committed"
    }
}
