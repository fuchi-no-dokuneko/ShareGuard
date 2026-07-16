package app.shareguard.canonical

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shareguard.core.model.DisplayLabel
import app.shareguard.core.security.InMemoryAesGcmKeyProvider
import app.shareguard.core.session.AndroidMonotonicClockSource
import app.shareguard.core.session.FileSessionWorkspaceManager
import app.shareguard.core.session.ImportAnchorRecorder
import app.shareguard.core.session.SnapshotLimits
import app.shareguard.core.session.SystemWallClockSource
import app.shareguard.core.storage.AppPrivateStorageLayout
import app.shareguard.core.storage.SavedResultDatabase
import app.shareguard.core.storage.SavedResultRepository
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttp
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CanonicalTextWorkflowTest {
    private lateinit var context: Context
    private lateinit var database: SavedResultDatabase
    private lateinit var repository: SavedResultRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        OkHttp.initialize(context)
        database = Room.inMemoryDatabaseBuilder(context, SavedResultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val root = File(context.cacheDir, "workflow-test-${System.nanoTime()}").apply { mkdirs() }
        repository = SavedResultRepository(
            database = database,
            layout = AppPrivateStorageLayout(File(root, "persistent"), File(root, "cache")),
            keyProvider = InMemoryAesGcmKeyProvider(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun exactTextWorkflowEitherPersistsWithReleaseEvidenceOrNamesOnlyReleaseBlockers() = runTest {
        val session = FileSessionWorkspaceManager(
            workspaceRoot = File(context.cacheDir, "workflow-session-${System.nanoTime()}"),
            importAnchorRecorder = ImportAnchorRecorder(
                wallClock = SystemWallClockSource,
                monotonicClock = AndroidMonotonicClockSource,
                bootSessionReferenceSource = EstimatedBootSessionReferenceSource(),
            ),
            snapshotLimits = SnapshotLimits(1024L * 1024L),
            staleAfterMillis = 60_000L,
            wallClock = SystemWallClockSource,
        ).startSession().session
        val snapshot = session.snapshots.sealAcceptedDirectText("Hello canonical world")
        val workflow = CanonicalTextWorkflow(context, repository, cleanupEvidence = { true })
        val review = workflow.inspect("Hello canonical world")
        val approved = workflow.approve(review, allReviewItemsApproved = !review.requiresReview)

        val completion = workflow.verifyAndPersist(
            session = session,
            sourceHandle = snapshot.descriptor.sourceHandle,
            importAnchor = snapshot.descriptor.importAnchor,
            plan = approved,
            displayLabel = DisplayLabel("Text result"),
            semanticDiffApproved = true,
            assuranceConsequenceApproved = true,
        )

        if (BuildConfig.RELEASE_PRIVACY_EVIDENCE) {
            assertThat(completion.persisted).isNotNull()
            assertThat(completion.verification.blockingVerificationTypes).isEmpty()
        } else {
            assertThat(completion.persisted).isNull()
            assertThat(completion.verification.blockingVerificationTypes.map { it.name })
                .containsExactly("NO_NETWORK_RUNTIME", "SENSITIVE_LOGGING")
        }
        assertThat(completion.canonicalText).isEqualTo("Hello canonical world")
        session.lifecycle.complete()
    }
}
