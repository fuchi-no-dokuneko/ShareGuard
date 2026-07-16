package app.shareguard.canonical

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.BoundedDelayPolicy
import app.shareguard.core.model.BoundedDelayPurpose
import app.shareguard.core.model.DisplayLabel
import app.shareguard.core.model.DurationMillis
import app.shareguard.core.model.SafeSummary
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
class DerivativeImageWorkflowTest {
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
        val root = File(context.cacheDir, "derivative-test-${System.nanoTime()}").apply { mkdirs() }
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
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun derivativeRetainsExplicitPixelLineageAndCannotExceedAs1() = runTest {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(31, 73, 127))
            setHasAlpha(false)
        }
        val sourceBytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        val session = FileSessionWorkspaceManager(
            workspaceRoot = File(context.cacheDir, "derivative-session-${System.nanoTime()}"),
            importAnchorRecorder = ImportAnchorRecorder(
                wallClock = SystemWallClockSource,
                monotonicClock = AndroidMonotonicClockSource,
                bootSessionReferenceSource = EstimatedBootSessionReferenceSource(),
            ),
            snapshotLimits = SnapshotLimits(1024L * 1024L),
            staleAfterMillis = 60_000L,
            wallClock = SystemWallClockSource,
        ).startSession().session
        val snapshot = try {
            session.snapshots.sealAcceptedProviderSource(
                ByteArrayInputStream(sourceBytes),
                BoundedDelayPolicy(
                    enabled = false,
                    purpose = BoundedDelayPurpose.PROVIDER_SNAPSHOT_RECHECK,
                    minimum = DurationMillis(0),
                    maximum = DurationMillis(0),
                    validationReference = SafeSummary("derivative-workflow-unit-test-v1"),
                ),
            )
        } finally {
            sourceBytes.fill(0)
        }
        val source = LocalDerivativeSource(
            orientationAppliedBitmap = bitmap,
            diagnosticLimitationCodes = setOf(
                "SOURCE_DIAGNOSTICS_NOT_CORPUS_CALIBRATED",
                "DERIVATIVE_DIAGNOSTIC_COMPARISON_NOT_RUN",
            ),
        )
        val workflow = DerivativeImageWorkflow(context, repository, cleanupEvidence = { true })

        val completion = try {
            workflow.verifyAndPersist(
                session = session,
                sourceHandle = snapshot.descriptor.sourceHandle,
                importAnchor = snapshot.descriptor.importAnchor,
                source = source,
                displayLabel = DisplayLabel("Derivative image"),
                warningAcknowledged = true,
            )
        } finally {
            source.close()
        }

        assertThat(completion.verification.report.assuranceClass)
            .isAnyOf(
                AssuranceClass.AS_0_UNVERIFIED,
                AssuranceClass.AS_1_REENCODED_DERIVATIVE,
            )
        assertThat(completion.changeLedger.entries.map { it.blockId.value })
            .containsExactly("DER-001", "DER-002", "DER-005").inOrder()
        assertThat(completion.changeLedger.entries.map { it.semanticImpact }.toSet())
            .containsExactly(app.shareguard.core.model.SemanticImpact.POSSIBLE)
        assertThat(completion.changeLedger.entries.mapNotNull { it.reviewLink?.decisionId?.value }.toSet())
            .containsExactly("decision-derivative-warning-v1")
        val dependency = completion.verification.report.results.single {
            it.type == VerificationType.SOURCE_PIXEL_DEPENDENCY
        }
        assertThat(dependency.status).isEqualTo(VerificationStatus.PASS_WITH_DECLARED_RESIDUAL)
        assertThat(completion.verification.blockingVerificationTypes).containsNoneOf(
            VerificationType.EXECUTED_BLOCK_MANIFEST,
            VerificationType.CANONICAL_REVISION_LINK,
            VerificationType.SOURCE_PIXEL_DEPENDENCY,
            VerificationType.VISUAL_REGION_COVERAGE,
            VerificationType.ASSURANCE_CLASSIFIER,
        )
        assertThat(completion.exactImagePreviewBytes.copyOfRange(0, 8)).isEqualTo(PNG_SIGNATURE)
        completion.exactImagePreviewBytes.fill(0)
        session.lifecycle.complete()
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
