package app.shareguard.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shareguard.core.model.AppBuildId
import app.shareguard.core.model.ArtifactId
import app.shareguard.core.model.ArtifactKind
import app.shareguard.core.model.ArtifactReference
import app.shareguard.core.model.ArtifactRevision
import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.BlockId
import app.shareguard.core.model.BlockVersion
import app.shareguard.core.model.BootSessionReference
import app.shareguard.core.model.ByteCount
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.DisplayLabel
import app.shareguard.core.model.ExecutionRevision
import app.shareguard.core.model.ExecutedBlockManifestEntry
import app.shareguard.core.model.ImmutableList
import app.shareguard.core.model.ImportAnchor
import app.shareguard.core.model.ImportClockConfidence
import app.shareguard.core.model.MetadataInventoryEntry
import app.shareguard.core.model.MimeType
import app.shareguard.core.model.MonotonicInstant
import app.shareguard.core.model.OutputBundle
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SavedResultId
import app.shareguard.core.model.SchemaVersion
import app.shareguard.core.model.TextArtifact
import app.shareguard.core.model.VerificationId
import app.shareguard.core.model.VerificationReport
import app.shareguard.core.model.VerificationResult
import app.shareguard.core.model.VerificationStatus
import app.shareguard.core.model.VerificationType
import app.shareguard.core.model.WallClockInstant
import app.shareguard.core.security.AesGcmAuthenticatedEncryption
import app.shareguard.core.security.AesGcmKeyProvider
import app.shareguard.core.security.ContentDigester
import app.shareguard.core.security.InMemoryAesGcmKeyProvider
import app.shareguard.core.security.KeyAlias
import app.shareguard.core.security.SecretBytes
import app.shareguard.core.security.Sha256ContentDigester
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

internal class StorageTestFixture(
    faultInjector: PersistenceFaultInjector = NoPersistenceFaults,
    val keyProvider: AesGcmKeyProvider = InMemoryAesGcmKeyProvider(),
) : AutoCloseable {
    val context: Context = ApplicationProvider.getApplicationContext()
    private val fixtureId = FIXTURE_COUNTER.incrementAndGet()
    val persistentRoot = File(context.noBackupFilesDir, "storage-test-$fixtureId").apply {
        deleteRecursively()
        check(mkdirs())
    }
    val cacheRoot = File(context.cacheDir, "storage-test-$fixtureId").apply {
        deleteRecursively()
        check(mkdirs())
    }
    val layout = AppPrivateStorageLayout(persistentRoot, cacheRoot)
    val database = Room.inMemoryDatabaseBuilder(context, SavedResultDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val ids = AtomicInteger()
    private val keys = AtomicInteger()
    val repository = SavedResultRepository(
        database = database,
        layout = layout,
        keyProvider = keyProvider,
        encryption = AesGcmAuthenticatedEncryption(keyProvider),
        idGenerator = SavedResultIdGenerator { SavedResultId("result-test-${ids.incrementAndGet()}") },
        keyAliasGenerator = StorageKeyAliasGenerator { KeyAlias("saved-test-${keys.incrementAndGet()}") },
        clock = StorageClock { WallClockInstant(10_000) },
        faultInjector = faultInjector,
    )
    val revalidator = SavedResultRevalidator(
        repository,
        clock = StorageClock { WallClockInstant(20_000) },
    )

    suspend fun persistText(text: String = "approved canonical text"): PersistedSavedResult =
        request(text).use { request -> repository.persistVerifiedResult(request.value) }

    fun request(text: String = "approved canonical text"): RequestHandle {
        val bytes = text.encodeToByteArray()
        val digest = DIGESTER.digest(bytes)
        val artifact = TextArtifact(
            artifactId = ArtifactId("artifact-text"),
            reference = ArtifactReference("verified-text"),
            artifactRevision = ArtifactRevision(1),
            canonicalRevision = CanonicalRevision(1),
            mimeType = MimeType("text/plain"),
            digest = digest,
            byteCount = ByteCount(bytes.size.toLong()),
            canonicalText = text,
        )
        val sourceAudit = VerificationResult(
            verificationId = VerificationId("source-reference"),
            type = VerificationType.SOURCE_REFERENCE,
            status = VerificationStatus.PASS,
            artifactRevision = ArtifactRevision(1),
            required = true,
            summary = SafeSummary("Source reference audit passed"),
        )
        val report = VerificationReport(
            reportVersion = SchemaVersion(1),
            artifactRevision = ArtifactRevision(1),
            canonicalRevision = CanonicalRevision(1),
            executedBlockManifest = ImmutableList.of(
                ExecutedBlockManifestEntry(
                    BlockId("VER-007"),
                    BlockVersion(1),
                    ExecutionRevision(1),
                    0,
                ),
            ),
            results = ImmutableList.empty(),
            finalMetadataInventory = ImmutableList.empty<MetadataInventoryEntry>(),
            finalUnicodeFindings = ImmutableList.empty(),
            finalUrlFindings = ImmutableList.empty(),
            ocrRoundTripFindings = ImmutableList.empty(),
            sourceReferenceAudit = sourceAudit,
            sourcePixelRegionList = ImmutableList.empty(),
            unresolvedFindingList = ImmutableList.empty(),
            assuranceClass = AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT,
            assuranceRationale = SafeSummary("Mandatory verification passed"),
            verificationFailures = ImmutableList.empty(),
            generatedAtSessionTime = WallClockInstant(9_000),
        )
        val bundle = OutputBundle(
            outputMode = OutputMode.TEXT,
            canonicalRevision = CanonicalRevision(1),
            textArtifact = artifact,
            verificationReport = report,
        )
        val payload = SecretBytes.copyOf(bytes)
        bytes.fill(0)
        return RequestHandle(
            PersistVerifiedResultRequest(
                outputBundle = bundle,
                verificationReport = report,
                importAnchor = ImportAnchor(
                    wallClock = WallClockInstant(1_000),
                    monotonic = MonotonicInstant(2_000_000_000),
                    bootSessionReference = BootSessionReference("boot-test"),
                    clockConfidence = ImportClockConfidence.MONOTONIC_ACTIVE,
                ),
                displayLabel = DisplayLabel("Saved result"),
                assuranceRationaleSummary = report.assuranceRationale,
                createdByAppBuild = AppBuildId("build-test"),
                artifactPayloads = mapOf(ArtifactKind.CANONICAL_TEXT to payload),
            ),
            payload,
        )
    }

    override fun close() {
        database.close()
        keyProvider.close()
        persistentRoot.deleteRecursively()
        cacheRoot.deleteRecursively()
    }

    class RequestHandle(
        val value: PersistVerifiedResultRequest,
        private val payload: SecretBytes,
    ) : AutoCloseable {
        override fun close() = payload.close()
    }

    private companion object {
        val FIXTURE_COUNTER = AtomicInteger()
        val DIGESTER: ContentDigester = Sha256ContentDigester()
    }
}
