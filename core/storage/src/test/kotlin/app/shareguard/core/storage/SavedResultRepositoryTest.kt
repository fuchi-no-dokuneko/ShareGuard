package app.shareguard.core.storage

import androidx.test.core.app.ApplicationProvider
import app.shareguard.core.model.DisplayLabel
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SavedResultLifecycleState
import app.shareguard.core.security.AesGcmAuthenticatedEncryption
import app.shareguard.core.security.InMemoryAesGcmKeyProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SavedResultRepositoryTest {
    @Test
    fun verifiedResultBecomesVisibleOnlyAfterFinalReopenAndCiphertextContainsNoPlaintext() = runTest {
        StorageTestFixture().use { fixture ->
            val persisted = fixture.persistText("approved visible final text")

            assertThat(persisted.durableWriteConfirmed).isTrue()
            assertThat(persisted.savedResult.canManagedShare).isTrue()
            assertThat(fixture.repository.listVisible()).containsExactly(persisted.savedResult)

            val record = fixture.repository.findAny(persisted.savedResult.savedResultId)!!
            val artifactFile = fixture.layout.resolvePersistent(record.artifacts.single().relativePath)
            val encryptedBytes = artifactFile.readBytes()
            assertThat(encryptedBytes.toString(Charsets.ISO_8859_1))
                .doesNotContain("approved visible final text")
            assertThat(artifactFile.canonicalPath)
                .startsWith(fixture.layout.savedResultsRoot.canonicalPath + File.separator)
        }
    }

    @Test
    fun everyCommitInterruptionPointKeepsTheResultOutOfNormalQueries() = runTest {
        PersistenceCheckpoint.entries.forEach { interruptedAt ->
            val injector = PersistenceFaultInjector { checkpoint ->
                if (checkpoint == interruptedAt) throw InjectedInterruption()
            }
            StorageTestFixture(faultInjector = injector).use { fixture ->
                fixture.request().use { request ->
                    val error = captureStorageFailure {
                        fixture.repository.persistVerifiedResult(request.value)
                    }
                    assertThat(error.reason).isAnyOf(
                        StorageFailureReason.UNEXPECTED_STORAGE_FAILURE,
                        StorageFailureReason.VISIBILITY_COMMIT_FAILED,
                    )
                }

                assertThat(fixture.repository.listVisible()).isEmpty()
                val residues = fixture.repository.dao.listAll()
                if (interruptedAt.ordinal >= PersistenceCheckpoint.AFTER_METADATA_COMMIT.ordinal) {
                    assertThat(residues).hasSize(1)
                    assertThat(residues.single().result.lifecycleState)
                        .isEqualTo(SavedResultLifecycleState.COMMITTING.name)
                } else {
                    assertThat(residues).isEmpty()
                }
            }
        }
    }

    @Test
    fun renameAndFavouriteDoNotMutateManagedArtifactBytes() = runTest {
        StorageTestFixture().use { fixture ->
            val persisted = fixture.persistText()
            val id = persisted.savedResult.savedResultId
            val record = fixture.repository.findAny(id)!!
            val artifact = fixture.layout.resolvePersistent(record.artifacts.single().relativePath)
            val before = artifact.readBytes()

            val updated = fixture.repository.renameAndFavourite(id, DisplayLabel("My neutral label"), true)

            assertThat(updated.displayLabel.value).isEqualTo("My neutral label")
            assertThat(updated.favourite).isTrue()
            assertThat(artifact.readBytes()).isEqualTo(before)
        }
    }

    @Test
    fun successfulExternalExportMarkerChangesOnlyManagementMetadata() = runTest {
        StorageTestFixture().use { fixture ->
            val persisted = fixture.persistText()
            val id = persisted.savedResult.savedResultId
            val record = fixture.repository.findAny(id)!!
            val artifact = fixture.layout.resolvePersistent(record.artifacts.single().relativePath)
            val before = artifact.readBytes()

            val updated = fixture.repository.noteExternalExport(id)

            assertThat(updated.artifactManifest.externalExportKnown).isTrue()
            assertThat(updated.canManagedShare).isTrue()
            assertThat(artifact.readBytes()).isEqualTo(before)
        }
    }

    @Test
    fun processRecreationRetainsOnlyDurablyCommittedVisibleResults() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val counter = RECREATION_COUNTER.incrementAndGet()
        val databaseName = "saved-result-recreation-$counter.db"
        val persistent = File(context.noBackupFilesDir, "recreation-$counter").apply { mkdirs() }
        val cache = File(context.cacheDir, "recreation-$counter").apply { mkdirs() }
        val layout = AppPrivateStorageLayout(persistent, cache)
        val keyProvider = InMemoryAesGcmKeyProvider()
        var firstDatabase: SavedResultDatabase? = null
        var secondDatabase: SavedResultDatabase? = null
        try {
            firstDatabase = SavedResultDatabase.open(context, databaseName)
            val firstRepository = SavedResultRepository(
                database = firstDatabase,
                layout = layout,
                keyProvider = keyProvider,
                encryption = AesGcmAuthenticatedEncryption(keyProvider),
                idGenerator = SavedResultIdGenerator { app.shareguard.core.model.SavedResultId("result-recreated") },
                keyAliasGenerator = StorageKeyAliasGenerator { app.shareguard.core.security.KeyAlias("saved-recreated") },
                clock = StorageClock { app.shareguard.core.model.WallClockInstant(10_000) },
            )
            StorageTestFixture().use { requestFixture ->
                requestFixture.request().use { request ->
                    firstRepository.persistVerifiedResult(request.value)
                }
            }
            firstDatabase.close()
            firstDatabase = null

            secondDatabase = SavedResultDatabase.open(context, databaseName)
            val recreatedRepository = SavedResultRepository(
                database = secondDatabase,
                layout = layout,
                keyProvider = keyProvider,
                encryption = AesGcmAuthenticatedEncryption(keyProvider),
            )

            assertThat(recreatedRepository.listVisible()).hasSize(1)
            assertThat(recreatedRepository.listVisible().single().canManagedShare).isTrue()
        } finally {
            firstDatabase?.close()
            secondDatabase?.close()
            keyProvider.close()
            context.deleteDatabase(databaseName)
            persistent.deleteRecursively()
            cache.deleteRecursively()
        }
    }

    @Test
    fun persistentSummaryPolicyRejectsUrisPathsAndBeforeValues() {
        StorageTestFixture().use { fixture ->
            fixture.request().use { handle ->
                listOf(
                    "https://source.example/private",
                    "content://provider/source",
                    "/data/user/0/app/source",
                    "before_value was secret",
                    "destination app identity",
                ).forEach { forbidden ->
                    assertThrows(IllegalArgumentException::class.java) {
                        PersistVerifiedResultRequest(
                            outputBundle = handle.value.outputBundle,
                            verificationReport = handle.value.verificationReport,
                            importAnchor = handle.value.importAnchor,
                            displayLabel = handle.value.displayLabel,
                            assuranceRationaleSummary = SafeSummary(forbidden),
                            createdByAppBuild = handle.value.createdByAppBuild,
                            artifactPayloads = handle.value.artifactPayloads,
                        )
                    }
                }
            }
        }
    }

    private suspend fun captureStorageFailure(block: suspend () -> Unit): SavedResultStorageException =
        try {
            block()
            error("Expected persistence to fail")
        } catch (error: SavedResultStorageException) {
            error
        }

    private class InjectedInterruption : RuntimeException()

    private companion object {
        val RECREATION_COUNTER = AtomicInteger()
    }
}
