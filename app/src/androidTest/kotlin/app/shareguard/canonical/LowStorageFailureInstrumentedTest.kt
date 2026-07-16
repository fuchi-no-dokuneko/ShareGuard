package app.shareguard.canonical

import android.system.ErrnoException
import android.system.OsConstants
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.shareguard.core.model.DisplayLabel
import app.shareguard.core.security.InMemoryAesGcmKeyProvider
import app.shareguard.core.session.AndroidMonotonicClockSource
import app.shareguard.core.session.FileSessionWorkspaceManager
import app.shareguard.core.session.ImportAnchorRecorder
import app.shareguard.core.session.SnapshotLimits
import app.shareguard.core.session.SystemWallClockSource
import app.shareguard.core.storage.AppPrivateStorageLayout
import app.shareguard.core.storage.PersistenceCheckpoint
import app.shareguard.core.storage.PersistenceFaultInjector
import app.shareguard.core.storage.SavedResultDatabase
import app.shareguard.core.storage.SavedResultRepository
import app.shareguard.core.storage.SavedResultStorageException
import app.shareguard.core.storage.StorageFailureReason
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LowStorageFailureInstrumentedTest {
    @Test
    fun injectedEnospcAfterStagedSyncLeavesNoVisibleOrPersistentPartialResult() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "instrumented-enospc-${System.nanoTime()}").apply { mkdirs() }
        val persistentRoot = File(root, "persistent")
        val cacheRoot = File(root, "cache")
        val sessionRoot = File(root, "session")
        val database = Room.inMemoryDatabaseBuilder(context, SavedResultDatabase::class.java).build()
        val keyProvider = InMemoryAesGcmKeyProvider()
        val repository = SavedResultRepository(
            database = database,
            layout = AppPrivateStorageLayout(persistentRoot, cacheRoot),
            keyProvider = keyProvider,
            faultInjector = PersistenceFaultInjector { checkpoint ->
                if (checkpoint == PersistenceCheckpoint.AFTER_STAGED_FILE_SYNC) {
                    throw IOException(ErrnoException("write", OsConstants.ENOSPC))
                }
            },
        )
        val session = FileSessionWorkspaceManager(
            workspaceRoot = sessionRoot,
            importAnchorRecorder = ImportAnchorRecorder(
                SystemWallClockSource,
                AndroidMonotonicClockSource,
                EstimatedBootSessionReferenceSource(),
            ),
            snapshotLimits = SnapshotLimits(1024L * 1024L),
            staleAfterMillis = 0L,
            wallClock = SystemWallClockSource,
        ).startSession().session
        try {
            val text = "approved low storage failure probe"
            val snapshot = session.snapshots.sealAcceptedDirectText(text)
            val workflow = CanonicalTextWorkflow(context, repository, cleanupEvidence = { true })
            val review = workflow.inspect(text)
            val approved = workflow.approve(review, allReviewItemsApproved = !review.requiresReview)
            val failure = runCatching {
                workflow.verifyAndPersist(
                    session = session,
                    sourceHandle = snapshot.descriptor.sourceHandle,
                    importAnchor = snapshot.descriptor.importAnchor,
                    plan = approved,
                    displayLabel = DisplayLabel("Low storage probe"),
                    semanticDiffApproved = true,
                    assuranceConsequenceApproved = true,
                )
            }.exceptionOrNull()

            assertTrue(failure is SavedResultStorageException)
            assertEquals(StorageFailureReason.UNEXPECTED_STORAGE_FAILURE, (failure as SavedResultStorageException).reason)
            assertEquals(StorageFailureReason.UNEXPECTED_STORAGE_FAILURE.name, failure.message)
            assertFalse("platform ENOSPC details escaped the content-free boundary", failure.message.orEmpty().contains("ENOSPC"))
            assertTrue(repository.listVisible().isEmpty())
            assertFalse(persistentRoot.walkTopDown().any { it.isFile })
        } finally {
            session.lifecycle.discard()
            database.close()
            keyProvider.close()
            root.deleteRecursively()
        }
    }
}
