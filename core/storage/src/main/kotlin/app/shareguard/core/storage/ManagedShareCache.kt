package app.shareguard.core.storage

import app.shareguard.core.model.ArtifactKind
import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.ByteCount
import app.shareguard.core.model.ContentDigest
import app.shareguard.core.model.DurationMillis
import app.shareguard.core.model.MimeType
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.SavedResultId
import app.shareguard.core.security.ContentDigester
import app.shareguard.core.security.Sha256ContentDigester
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@JvmInline
value class ManagedShareToken(val value: String) {
    init { require(Regex("share-[0-9a-f]{32}").matches(value)) { "Invalid managed-share token" } }
}

data class ManagedShareDescriptor(
    val cacheToken: ManagedShareToken,
    val savedResultId: SavedResultId,
    val artifactKind: ArtifactKind,
    val mimeType: MimeType,
    val byteCount: ByteCount,
    val assuranceClass: AssuranceClass,
    val temporaryReadOnlyRepresentation: Boolean = true,
) {
    override fun toString(): String = "ManagedShareDescriptor(metadata=redacted)"
}

data class ShareCachePolicy(
    val maximumLifetime: DurationMillis,
) {
    init { require(maximumLifetime.value > 0) { "Share-cache lifetime must be positive" } }
}

fun interface MonotonicStorageClock {
    fun elapsedRealtimeMillis(): Long
}

object SystemMonotonicStorageClock : MonotonicStorageClock {
    override fun elapsedRealtimeMillis(): Long = android.os.SystemClock.elapsedRealtime()
}

class ManagedShareCache(
    private val repository: SavedResultRepository,
    private val revalidator: SavedResultRevalidator,
    private val policy: ShareCachePolicy,
    private val monotonicClock: MonotonicStorageClock = SystemMonotonicStorageClock,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val digester: ContentDigester = Sha256ContentDigester(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class CacheEntry(
        val descriptor: ManagedShareDescriptor,
        val file: File,
        val digest: ContentDigest,
        val expiresAtElapsedMillis: Long,
    )

    private val entries = ConcurrentHashMap<ManagedShareToken, CacheEntry>()

    suspend fun prepare(
        savedResultId: SavedResultId,
        selectedKind: ArtifactKind? = null,
    ): ManagedShareDescriptor {
        val record = revalidator.requireValidForManagedShare(savedResultId)
        val outputMode = OutputMode.valueOf(record.result.outputMode)
        if (outputMode == OutputMode.BOTH && selectedKind == null) {
            throw SavedResultStorageException(StorageFailureReason.RECORD_NOT_SHAREABLE)
        }
        val artifact = when (selectedKind) {
            null -> record.artifacts.singleOrNull()
            else -> record.artifacts.singleOrNull { it.artifactKind == selectedKind.name }
        } ?: throw SavedResultStorageException(StorageFailureReason.RECORD_NOT_FOUND)
        val token = nextToken()
        val destination = repository.layout.shareCache(savedResultId, token.value)
        val expectedDigest = ContentDigest(artifact.contentDigest)
        try {
            repository.artifactStore.readFinal(record.result, artifact).use { plaintext ->
                withContext(ioDispatcher) {
                    plaintext.access { bytes ->
                        FileOutputStream(destination, false).use { output ->
                            output.write(bytes)
                            output.flush()
                            output.fd.sync()
                        }
                    }
                }
            }
            val actual = withContext(ioDispatcher) {
                BufferedInputStream(FileInputStream(destination)).use(digester::digest)
            }
            if (actual != expectedDigest || destination.length() != artifact.byteCount) {
                throw SavedResultStorageException(StorageFailureReason.SHARE_CACHE_PREPARATION_FAILED)
            }
            destination.setReadable(false, false)
            destination.setWritable(false, false)
            destination.setExecutable(false, false)
            if (!destination.setReadable(true, true)) {
                throw SavedResultStorageException(StorageFailureReason.SHARE_CACHE_PREPARATION_FAILED)
            }
            val descriptor = ManagedShareDescriptor(
                cacheToken = token,
                savedResultId = savedResultId,
                artifactKind = ArtifactKind.valueOf(artifact.artifactKind),
                mimeType = MimeType(artifact.mimeType),
                byteCount = ByteCount(artifact.byteCount),
                assuranceClass = AssuranceClass.valueOf(record.result.assuranceClass),
            )
            val now = monotonicClock.elapsedRealtimeMillis()
            val expiry = runCatching { Math.addExact(now, policy.maximumLifetime.value) }
                .getOrElse { Long.MAX_VALUE }
            entries[token] = CacheEntry(descriptor, destination, expectedDigest, expiry)
            return descriptor
        } catch (error: SavedResultStorageException) {
            runCatching { destination.delete() }
            throw error
        } catch (error: Exception) {
            runCatching { destination.delete() }
            throw SavedResultStorageException(StorageFailureReason.SHARE_CACHE_PREPARATION_FAILED, error)
        }
    }

    suspend fun openReadOnly(cacheToken: ManagedShareToken): InputStream {
        val entry = entries[cacheToken]
            ?: throw SavedResultStorageException(StorageFailureReason.RECORD_NOT_FOUND)
        if (monotonicClock.elapsedRealtimeMillis() >= entry.expiresAtElapsedMillis) {
            clear(cacheToken)
            throw SavedResultStorageException(StorageFailureReason.RECORD_NOT_FOUND)
        }
        val approved = repository.layout.resolveShare(entry.descriptor.savedResultId, cacheToken.value)
        if (approved != entry.file.canonicalFile || !approved.isFile) {
            clear(cacheToken)
            throw SavedResultStorageException(StorageFailureReason.PATH_OUTSIDE_APPROVED_ROOT)
        }
        val digestMatches = withContext(ioDispatcher) {
            BufferedInputStream(FileInputStream(approved)).use(digester::digest) == entry.digest
        }
        if (!digestMatches) {
            clear(cacheToken)
            throw SavedResultStorageException(StorageFailureReason.SHARE_CACHE_PREPARATION_FAILED)
        }
        return withContext(ioDispatcher) { FileInputStream(approved) }
    }

    suspend fun cleanupExpired(): Int {
        val now = monotonicClock.elapsedRealtimeMillis()
        val expired = entries.filterValues { now >= it.expiresAtElapsedMillis }.keys.toList()
        expired.forEach { clear(it) }
        return expired.size
    }

    suspend fun clear(cacheToken: ManagedShareToken): Boolean {
        val entry = entries.remove(cacheToken) ?: return true
        return withContext(ioDispatcher) { !entry.file.exists() || entry.file.delete() }
    }

    suspend fun clearForResult(savedResultId: SavedResultId): Boolean {
        entries.filterValues { it.descriptor.savedResultId == savedResultId }.keys.toList().forEach { clear(it) }
        return withContext(ioDispatcher) {
            deleteTreeLogically(repository.layout.shareDirectory(savedResultId), repository.layout.managedShareRoot)
        }
    }

    /** Process recreation has no receiver-completion signal; untracked cache is conservatively removed. */
    suspend fun clearAllOnStartup(): Boolean = withContext(ioDispatcher) {
        entries.clear()
        val children = repository.layout.managedShareRoot.listFiles().orEmpty()
        children.all { deleteTreeLogically(it, repository.layout.managedShareRoot) }
    }

    private fun nextToken(): ManagedShareToken {
        repeat(MAX_TOKEN_ATTEMPTS) {
            val bytes = ByteArray(16).also(secureRandom::nextBytes)
            val value = try {
                "share-" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            } finally {
                bytes.fill(0)
            }
            val token = ManagedShareToken(value)
            if (!entries.containsKey(token)) return token
        }
        throw SavedResultStorageException(StorageFailureReason.SHARE_CACHE_PREPARATION_FAILED)
    }

    private companion object {
        const val MAX_TOKEN_ATTEMPTS = 32
    }
}
