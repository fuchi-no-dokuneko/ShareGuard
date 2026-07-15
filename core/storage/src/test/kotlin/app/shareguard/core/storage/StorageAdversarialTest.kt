package app.shareguard.core.storage

import app.shareguard.core.model.ArtifactKind
import app.shareguard.core.security.KeyAlias
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StorageAdversarialTest {
    @Test
    fun ciphertextCorruptionQuarantinesAndBlocksVerifiedSharing() = runTest {
        StorageTestFixture().use { fixture ->
            val id = fixture.persistText().savedResult.savedResultId
            val record = fixture.repository.findAny(id)!!
            val file = fixture.layout.resolvePersistent(record.artifacts.single().relativePath)
            check(file.setWritable(true, true))
            RandomAccessFile(file, "rw").use { random ->
                val position = random.length() - 1
                random.seek(position)
                val original = random.read()
                random.seek(position)
                random.write(original xor 0x01)
            }

            val result = fixture.revalidator.revalidate(id)

            assertThat(result.valid).isFalse()
            assertThat(result.reasonCode).isAnyOf(
                StorageFailureReason.AUTHENTICATION_FAILED.name,
                StorageFailureReason.FINAL_REOPEN_FAILED.name,
            )
            assertThat(fixture.repository.listVisible()).isEmpty()
        }
    }

    @Test
    fun missingPerResultKeyQuarantinesCiphertextInsteadOfExposingIt() = runTest {
        StorageTestFixture().use { fixture ->
            val id = fixture.persistText().savedResult.savedResultId
            val record = fixture.repository.findAny(id)!!
            fixture.keyProvider.delete(KeyAlias(record.result.keyAlias))

            val result = fixture.revalidator.revalidate(id)

            assertThat(result.valid).isFalse()
            assertThat(result.reasonCode).isEqualTo(StorageFailureReason.KEY_UNAVAILABLE.name)
            assertThat(fixture.repository.listVisible()).isEmpty()
        }
    }

    @Test
    fun traversalRevisionAndDigestMutationAreRejectedBeforeManagedUse() = runTest {
        StorageTestFixture().use { fixture ->
            val id = fixture.persistText().savedResult.savedResultId

            assertThrows(IllegalArgumentException::class.java) {
                fixture.layout.resolvePersistent("../source-uri-canary")
            }

            fixture.database.openHelper.writableDatabase.execSQL(
                "UPDATE saved_artifacts SET relative_path = ? WHERE saved_result_id = ? AND artifact_kind = ?",
                arrayOf("../outside.enc", id.value, ArtifactKind.CANONICAL_TEXT.name),
            )
            val traversal = fixture.revalidator.revalidate(id)
            assertThat(traversal.valid).isFalse()
            assertThat(traversal.reasonCode).isEqualTo(StorageFailureReason.PATH_OUTSIDE_APPROVED_ROOT.name)
        }

        StorageTestFixture().use { fixture ->
            val id = fixture.persistText().savedResult.savedResultId
            fixture.database.openHelper.writableDatabase.execSQL(
                "UPDATE saved_artifacts SET artifact_revision = ? WHERE saved_result_id = ? AND artifact_kind = ?",
                arrayOf<Any>(2L, id.value, ArtifactKind.CANONICAL_TEXT.name),
            )

            val mismatch = fixture.revalidator.revalidate(id)

            assertThat(mismatch.valid).isFalse()
            assertThat(mismatch.reasonCode).isEqualTo(StorageFailureReason.ARTIFACT_REVISION_MISMATCH.name)
            assertThat(fixture.repository.listVisible()).isEmpty()
        }

        StorageTestFixture().use { fixture ->
            val id = fixture.persistText().savedResult.savedResultId
            fixture.database.openHelper.writableDatabase.execSQL(
                "UPDATE saved_artifacts SET content_digest = ? WHERE saved_result_id = ?",
                arrayOf("0".repeat(64), id.value),
            )

            val mismatch = fixture.revalidator.revalidate(id)

            assertThat(mismatch.valid).isFalse()
            assertThat(mismatch.reasonCode).isEqualTo(StorageFailureReason.PAYLOAD_DIGEST_MISMATCH.name)
            assertThat(fixture.repository.listVisible()).isEmpty()
        }
    }

    @Test
    fun previewSubstitutionDeletesOnlyThePreviewAndNeverReplacesAuthoritativeArtifact() = runTest {
        StorageTestFixture().use { fixture ->
            val id = fixture.persistText("authoritative final content").savedResult.savedResultId
            val previews = SavedResultPreviewRepository(
                fixture.repository,
                PreviewPolicy(enabled = true, maximumBytes = 1_024),
            )
            assertThat(previews.build(id)).isInstanceOf(PreviewBuildResult.Available::class.java)
            val record = fixture.repository.findAny(id)!!
            val finalFile = fixture.layout.resolvePersistent(record.artifacts.single().relativePath)
            val preview = fixture.repository.dao.findPreview(id.value)!!
            val previewFile = fixture.layout.resolvePersistent(preview.relativePath)
            Files.copy(finalFile.toPath(), previewFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

            assertThat(previews.read(id)).isNull()
            assertThat(fixture.repository.dao.findPreview(id.value)).isNull()
            assertThat(fixture.repository.findVisible(id)).isNotNull()
            fixture.repository.readShareableArtifact(id, ArtifactKind.CANONICAL_TEXT).second.use { plaintext ->
                assertThat(plaintext.access { it.decodeToString() }).isEqualTo("authoritative final content")
            }
        }
    }

    @Test
    fun previewLocatorSubstitutionCannotSelectAnAuthoritativeArtifactForDeletion() = runTest {
        StorageTestFixture().use { fixture ->
            val id = fixture.persistText("authoritative artifact survives locator substitution")
                .savedResult.savedResultId
            val previews = SavedResultPreviewRepository(
                fixture.repository,
                PreviewPolicy(enabled = true, maximumBytes = 1_024),
            )
            assertThat(previews.build(id)).isInstanceOf(PreviewBuildResult.Available::class.java)
            val record = fixture.repository.findAny(id)!!
            val artifact = record.artifacts.single()
            val finalFile = fixture.layout.resolvePersistent(artifact.relativePath)
            val finalCiphertext = finalFile.readBytes()
            fixture.database.openHelper.writableDatabase.execSQL(
                "UPDATE saved_previews SET relative_path = ? WHERE saved_result_id = ?",
                arrayOf(artifact.relativePath, id.value),
            )

            assertThat(previews.read(id)).isNull()
            assertThat(fixture.repository.dao.findPreview(id.value)).isNull()
            assertThat(finalFile.readBytes()).isEqualTo(finalCiphertext)
            fixture.repository.readShareableArtifact(id, ArtifactKind.CANONICAL_TEXT).second.use { plaintext ->
                assertThat(plaintext.access { it.decodeToString() })
                    .isEqualTo("authoritative artifact survives locator substitution")
            }
            assertThat(fixture.repository.findVisible(id)).isNotNull()
        }
    }

    @Test
    fun integritySweepRemovesOrphansAndQuarantinesMissingArtifactRecords() = runTest {
        StorageTestFixture().use { fixture ->
            val id = fixture.persistText().savedResult.savedResultId
            val record = fixture.repository.findAny(id)!!
            fixture.layout.resolvePersistent(record.artifacts.single().relativePath).delete()
            val orphanDirectory = File(fixture.layout.savedResultsRoot, "orphan-result").apply { mkdirs() }
            File(orphanDirectory, "orphan.enc").writeBytes(byteArrayOf(1, 2, 3))
            val previews = SavedResultPreviewRepository(
                fixture.repository,
                PreviewPolicy(enabled = true, maximumBytes = 1_024),
            )
            val deletion = SavedResultDeletionService(fixture.repository)
            val sweep = PersistentStoreIntegritySweep(
                repository = fixture.repository,
                revalidator = fixture.revalidator,
                deletionService = deletion,
                previewRepository = previews,
            )

            val report = sweep.run()

            assertThat(report.quarantinedRecords).isAtLeast(1)
            assertThat(report.orphanFileGroupsRemoved).isAtLeast(1)
            assertThat(orphanDirectory.exists()).isFalse()
            assertThat(fixture.repository.listVisible()).isEmpty()
        }
    }

    @Test
    fun sourceUriFilenameOcrAndSessionPathCanariesNeverEnterPersistentMetadataOrFiles() = runTest {
        StorageTestFixture().use { fixture ->
            fixture.persistText("approved final words")
            val canaries = listOf(
                "SRC_FILENAME_CANARY_7ec1",
                "content://SRC_URI_CANARY_91af",
                "OCR_CROP_CANARY_45dd",
                "/data/session/SESSION_PATH_CANARY_12be",
                "BEFORE_VALUE_CANARY_77ca",
                "DESTINATION_CANARY_331a",
            )
            val metadata = fixture.repository.dao.listAll().joinToString()
            val persistedBytes = fixture.persistentRoot.walkTopDown()
                .filter(File::isFile)
                .flatMap { it.readBytes().asSequence() }
                .map { (it.toInt() and 0xff).toChar() }
                .joinToString("")

            canaries.forEach { canary ->
                assertThat(metadata).doesNotContain(canary)
                assertThat(persistedBytes).doesNotContain(canary)
            }
        }
    }
}
