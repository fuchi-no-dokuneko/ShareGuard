package app.shareguard.canonical

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shareguard.core.model.DisplayLabel
import app.shareguard.core.model.DecisionAction
import app.shareguard.core.model.InputKind
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.VerificationStatus
import app.shareguard.core.model.VerificationType
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
import org.robolectric.annotation.GraphicsMode

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

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun approvedImageTranscriptionRecordsPixelExclusionAndOcrLineage() = runTest {
        val session = FileSessionWorkspaceManager(
            workspaceRoot = File(context.cacheDir, "workflow-image-text-session-${System.nanoTime()}"),
            importAnchorRecorder = ImportAnchorRecorder(
                wallClock = SystemWallClockSource,
                monotonicClock = AndroidMonotonicClockSource,
                bootSessionReferenceSource = EstimatedBootSessionReferenceSource(),
            ),
            snapshotLimits = SnapshotLimits(1024L * 1024L),
            staleAfterMillis = 60_000L,
            wallClock = SystemWallClockSource,
        ).startSession().session
        val transcription = "Reviewed local OCR transcription"
        val snapshot = session.snapshots.sealAcceptedDirectText(transcription)
        val workflow = CanonicalTextWorkflow(context, repository, cleanupEvidence = { true })
        val review = workflow.inspect(transcription, InputKind.IMAGE)

        assertThat(listOfNotNull(review.ocrReviewFinding, review.imageExclusionFinding).map { it.findingId.value })
            .containsExactly("finding-ocr-transcription", "finding-image-source-exclusion")
        val approved = workflow.approve(review, allReviewItemsApproved = true)
        assertThat(approved.supplementalDecisions.map { it.action }).containsExactly(
            DecisionAction.LOCK_EXACT_WORDING,
            DecisionAction.EXCLUDE_REGION,
        )

        val completion = workflow.verifyAndPersist(
            session = session,
            sourceHandle = snapshot.descriptor.sourceHandle,
            importAnchor = snapshot.descriptor.importAnchor,
            plan = approved,
            displayLabel = DisplayLabel("Image transcription"),
            semanticDiffApproved = true,
            assuranceConsequenceApproved = true,
            inputKind = InputKind.IMAGE,
            outputMode = OutputMode.REBUILT_IMAGE,
        )

        assertThat(completion.changeLedger.entries.map { it.blockId.value })
            .containsAtLeast("REV-003", "REV-006", "REN-001", "REN-011")
        val dependency = completion.verification.report.results.single {
            it.type == VerificationType.SOURCE_PIXEL_DEPENDENCY
        }
        assertThat(dependency.status).isAnyOf(
            VerificationStatus.PASS,
            VerificationStatus.PASS_WITH_DECLARED_RESIDUAL,
        )
        assertThat(completion.verification.blockingVerificationTypes).containsNoneOf(
            VerificationType.EXECUTED_BLOCK_MANIFEST,
            VerificationType.CANONICAL_REVISION_LINK,
            VerificationType.SOURCE_PIXEL_DEPENDENCY,
        )
        session.lifecycle.complete()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun rebuiltImageLinksRendererDependenciesAndEveryOutputTransformationToTheApprovedRevision() = runTest {
        val session = FileSessionWorkspaceManager(
            workspaceRoot = File(context.cacheDir, "workflow-image-session-${System.nanoTime()}"),
            importAnchorRecorder = ImportAnchorRecorder(
                wallClock = SystemWallClockSource,
                monotonicClock = AndroidMonotonicClockSource,
                bootSessionReferenceSource = EstimatedBootSessionReferenceSource(),
            ),
            snapshotLimits = SnapshotLimits(1024L * 1024L),
            staleAfterMillis = 60_000L,
            wallClock = SystemWallClockSource,
        ).startSession().session
        val source = "Readable rebuilt image"
        val snapshot = session.snapshots.sealAcceptedDirectText(source)
        val workflow = CanonicalTextWorkflow(context, repository, cleanupEvidence = { true })
        val review = workflow.inspect(source)
        val approved = workflow.approve(review, allReviewItemsApproved = !review.requiresReview)

        val completion = workflow.verifyAndPersist(
            session = session,
            sourceHandle = snapshot.descriptor.sourceHandle,
            importAnchor = snapshot.descriptor.importAnchor,
            plan = approved,
            displayLabel = DisplayLabel("Rebuilt image"),
            semanticDiffApproved = true,
            assuranceConsequenceApproved = true,
            outputMode = OutputMode.REBUILT_IMAGE,
        )

        assertThat(completion.changeLedger.entries.map { it.blockId.value }).containsAtLeast(
            "REN-001",
            "REN-002",
            "REN-003",
            "REN-004",
            "REN-008",
            "REN-009",
            "REN-010",
            "REN-011",
        )
        val dependency = completion.verification.report.results.single {
            it.type == VerificationType.SOURCE_PIXEL_DEPENDENCY
        }
        assertThat(dependency.status).isEqualTo(VerificationStatus.PASS_WITH_DECLARED_RESIDUAL)
        assertThat(completion.verification.blockingVerificationTypes).containsNoneOf(
            VerificationType.EXECUTED_BLOCK_MANIFEST,
            VerificationType.CANONICAL_REVISION_LINK,
            VerificationType.SOURCE_PIXEL_DEPENDENCY,
        )
        session.lifecycle.complete()
    }
}
