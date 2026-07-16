package app.shareguard.core.storage

import app.shareguard.core.model.ArtifactKind
import app.shareguard.core.model.DurationMillis
import app.shareguard.core.model.SavedResultLifecycleState
import app.shareguard.core.security.AesGcmKeyProvider
import app.shareguard.core.security.InMemoryAesGcmKeyProvider
import app.shareguard.core.security.KeyAlias
import app.shareguard.core.security.LogicalKeyDeletionResult
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManagedShareAndDeletionTest {
    @Test
    fun managedShareUsesVerifiedTemporaryReadOnlyBytesAndTransientExpiryOnly() = runTest {
        StorageTestFixture().use { fixture ->
            val id = fixture.persistText("managed final bytes").savedResult.savedResultId
            val now = AtomicLong(100)
            val cache = ManagedShareCache(
                repository = fixture.repository,
                revalidator = fixture.revalidator,
                policy = ShareCachePolicy(DurationMillis(50)),
                monotonicClock = MonotonicStorageClock(now::get),
            )

            val descriptor = cache.prepare(id)

            assertThat(descriptor.artifactKind).isEqualTo(ArtifactKind.CANONICAL_TEXT)
            assertThat(descriptor.temporaryReadOnlyRepresentation).isTrue()
            assertThat(descriptor.toString()).doesNotContain(id.value)
            assertThat(cache.requirePreparedFile(descriptor).canonicalPath)
                .startsWith(fixture.layout.managedShareRoot.canonicalPath + File.separator)
            cache.openReadOnly(descriptor.cacheToken).use { input ->
                assertThat(input.readBytes().decodeToString()).isEqualTo("managed final bytes")
            }

            now.set(150)
            assertThat(cache.cleanupExpired()).isEqualTo(1)
            assertThat(captureStorageFailure { cache.requirePreparedFile(descriptor) }.reason)
                .isEqualTo(StorageFailureReason.RECORD_NOT_FOUND)
            assertThat(captureStorageFailure { cache.openReadOnly(descriptor.cacheToken) }.reason)
                .isEqualTo(StorageFailureReason.RECORD_NOT_FOUND)
        }
    }

    @Test
    fun singleAndBulkDeletionRemoveMetadataArtifactsPreviewsShareCachesAndKeys() = runTest {
        StorageTestFixture().use { fixture ->
            val first = fixture.persistText("first result").savedResult.savedResultId
            val second = fixture.persistText("second result").savedResult.savedResultId
            val previewRepository = SavedResultPreviewRepository(
                fixture.repository,
                PreviewPolicy(enabled = true, maximumBytes = 1_024),
            )
            previewRepository.build(first)
            val shareCache = ManagedShareCache(
                fixture.repository,
                fixture.revalidator,
                ShareCachePolicy(DurationMillis(1_000)),
                monotonicClock = MonotonicStorageClock { 10 },
            )
            val shared = shareCache.prepare(first)
            val firstAlias = KeyAlias(fixture.repository.findAny(first)!!.result.keyAlias)
            val secondAlias = KeyAlias(fixture.repository.findAny(second)!!.result.keyAlias)
            val deletion = SavedResultDeletionService(fixture.repository, shareCache)

            val result = deletion.deleteBulk(listOf(first, second))

            assertThat(result.completed).isTrue()
            assertThat(result.results).hasSize(2)
            assertThat(fixture.repository.dao.listAll()).isEmpty()
            assertThat(fixture.keyProvider.get(firstAlias)).isNull()
            assertThat(fixture.keyProvider.get(secondAlias)).isNull()
            assertThat(File(fixture.layout.savedResultsRoot, first.value).exists()).isFalse()
            assertThat(captureStorageFailure { shareCache.openReadOnly(shared.cacheToken) }.reason)
                .isEqualTo(StorageFailureReason.RECORD_NOT_FOUND)
        }
    }

    @Test
    fun partialKeyDeletionImmediatelyHidesResultAndRetryIsIdempotent() = runTest {
        val provider = ToggleDeleteKeyProvider()
        StorageTestFixture(keyProvider = provider).use { fixture ->
            val id = fixture.persistText().savedResult.savedResultId
            val deletion = SavedResultDeletionService(fixture.repository)
            provider.failDeletion = true

            val partial = deletion.delete(id)

            assertThat(partial.completed).isFalse()
            assertThat(partial.remainingReferenceCategories).contains("ENCRYPTION_KEY")
            assertThat(fixture.repository.listVisible()).isEmpty()
            assertThat(fixture.repository.findAny(id)!!.result.lifecycleState)
                .isEqualTo(SavedResultLifecycleState.DELETION_PENDING.name)

            provider.failDeletion = false
            val completed = deletion.delete(id)
            assertThat(completed.completed).isTrue()
            assertThat(deletion.delete(id).completed).isTrue()
        }
    }

    @Test
    fun interruptionAfterKeyRemovalLeavesDeletionPendingAndFreshServiceCanFinish() = runTest {
        StorageTestFixture().use { fixture ->
            val id = fixture.persistText().savedResult.savedResultId
            val interrupted = SavedResultDeletionService(
                fixture.repository,
                faultInjector = DeletionFaultInjector { checkpoint ->
                    if (checkpoint == DeletionCheckpoint.AFTER_KEY_DELETE) throw InterruptedDeletion()
                },
            )

            val partial = interrupted.delete(id)

            assertThat(partial.completed).isFalse()
            assertThat(fixture.repository.listVisible()).isEmpty()
            assertThat(fixture.repository.findAny(id)!!.result.lifecycleState)
                .isEqualTo(SavedResultLifecycleState.DELETION_PENDING.name)

            val retry = SavedResultDeletionService(fixture.repository).delete(id)
            assertThat(retry.completed).isTrue()
        }
    }

    @Test
    fun deleteAllIsSafeForEmptyAndPopulatedStores() = runTest {
        StorageTestFixture().use { fixture ->
            val deletion = SavedResultDeletionService(fixture.repository)
            assertThat(deletion.deleteAll().completed).isTrue()
            fixture.persistText("one")
            fixture.persistText("two")

            assertThat(deletion.deleteAll().completed).isTrue()
            assertThat(fixture.repository.listVisible()).isEmpty()
        }
    }

    private suspend fun captureStorageFailure(block: suspend () -> Any?): SavedResultStorageException =
        try {
            block()
            error("Expected storage failure")
        } catch (error: SavedResultStorageException) {
            error
        }

    private class ToggleDeleteKeyProvider : AesGcmKeyProvider {
        private val delegate = InMemoryAesGcmKeyProvider()
        var failDeletion: Boolean = false

        override fun getOrCreate(alias: KeyAlias): SecretKey = delegate.getOrCreate(alias)
        override fun get(alias: KeyAlias): SecretKey? = delegate.get(alias)
        override fun delete(alias: KeyAlias): LogicalKeyDeletionResult =
            if (failDeletion) LogicalKeyDeletionResult.FAILED_BEST_EFFORT else delegate.delete(alias)

        override fun close() = delegate.close()
    }

    private class InterruptedDeletion : RuntimeException()
}
