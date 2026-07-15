package app.shareguard.core.session

import app.shareguard.core.model.BoundedDelayPolicy
import app.shareguard.core.model.ByteCount
import app.shareguard.core.model.ContentDigest
import app.shareguard.core.model.ImportAnchor
import app.shareguard.core.model.SourceHandle
import app.shareguard.core.security.ContentDigester
import app.shareguard.core.security.Sha256ContentDigester
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class SnapshotIntegrityOutcome {
    VALID,
    MISSING,
    LOCATION_CHANGED,
    LENGTH_MISMATCH,
    DIGEST_MISMATCH,
    READ_FAILURE,
}

enum class LogicalSnapshotDeletionResult {
    DELETED,
    ALREADY_ABSENT,
    FAILED_BEST_EFFORT,
}

data class SnapshotVerification(val outcome: SnapshotIntegrityOutcome) {
    val isValid: Boolean
        get() = outcome == SnapshotIntegrityOutcome.VALID
}

/** Session-only metadata. Its redacted string form avoids logging digests or Import Anchor timestamps. */
class SourceSnapshotDescriptor(
    val sourceHandle: SourceHandle,
    val byteCount: ByteCount,
    val digest: ContentDigest,
    val importAnchor: ImportAnchor,
) {
    override fun toString(): String = "SourceSnapshotDescriptor(metadata=redacted)"
}

class SnapshotIntegrityException(
    val outcome: SnapshotIntegrityOutcome,
) : IOException("Snapshot integrity check failed: ${outcome.name}")

class SnapshotResourceLimitException : IOException("Snapshot exceeds the local resource budget")
class SnapshotCreationException : IOException("Snapshot creation failed")
class SnapshotAlreadyAcceptedException : IllegalStateException("A source is already accepted for this session")

data class SnapshotLimits(
    val maximumBytes: Long,
    val copyBufferBytes: Int = 16 * 1024,
) {
    init {
        require(maximumBytes in 1..Int.MAX_VALUE.toLong()) {
            "Snapshot maximum must be positive and JVM-addressable"
        }
        require(copyBufferBytes in 1..MAXIMUM_COPY_BUFFER_BYTES) {
            "Snapshot buffer is outside the local resource budget"
        }
    }

    private companion object {
        const val MAXIMUM_COPY_BUFFER_BYTES = 1024 * 1024
    }
}

interface SealedSourceSnapshot {
    val descriptor: SourceSnapshotDescriptor

    /** Reopens and hashes the exact bytes before returning them to a decoder. */
    suspend fun readVerified(): ByteArray
    suspend fun verify(): SnapshotVerification

    /** Logical deletion of the app-addressable file only; no physical sanitization is claimed. */
    suspend fun deleteLogical(): LogicalSnapshotDeletionResult
}

fun interface SourceHandleGenerator {
    /** Must be session-local and must not encode source/provider/account/device information. */
    fun next(): SourceHandle
}

class SecureRandomSourceHandleGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
) : SourceHandleGenerator {
    override fun next(): SourceHandle {
        val randomBytes = ByteArray(16).also(secureRandom::nextBytes)
        return try {
            SourceHandle("source-${randomBytes.toLowerHex()}")
        } finally {
            randomBytes.fill(0)
        }
    }

    private fun ByteArray.toLowerHex(): String {
        val output = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = HEX_CHARACTERS[value ushr 4]
            output[index * 2 + 1] = HEX_CHARACTERS[value and 0x0f]
        }
        return String(output)
    }

    private companion object {
        const val HEX_CHARACTERS = "0123456789abcdef"
    }
}

/**
 * Creates authoritative source copies under a caller-supplied app-private session directory. Provider
 * names, URIs, labels, and paths are deliberately absent from this API.
 */
class FileSourceSnapshotStore(
    workspaceDirectory: File,
    private val importAnchorRecorder: ImportAnchorRecorder,
    private val limits: SnapshotLimits,
    private val digester: ContentDigester = Sha256ContentDigester(),
    private val sourceHandleGenerator: SourceHandleGenerator = SecureRandomSourceHandleGenerator(),
    private val recheckDelay: SnapshotRecheckDelay = PolicyBoundedSnapshotRecheckDelay(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val workspace = runCatching { workspaceDirectory.canonicalFile }
        .getOrElse { throw SnapshotCreationException() }
    private val acceptanceState = AtomicReference(SourceAcceptanceState.IDLE)

    init {
        if (!workspace.exists() && !workspace.mkdirs()) throw SnapshotCreationException()
        if (!workspace.isDirectory) throw SnapshotCreationException()
    }

    /** Consumes and closes [providerInput]. The Import Anchor is recorded only after successful sealing. */
    suspend fun sealAcceptedProviderSource(
        providerInput: InputStream,
        delayPolicy: BoundedDelayPolicy,
        cancellationSignal: CancellationSignal = NeverCancelled,
    ): SealedSourceSnapshot = seal(providerInput, delayPolicy, cancellationSignal)

    /** Accepted Android shared text is copied into the immutable session representation before anchoring. */
    suspend fun sealAcceptedSharedText(
        sharedText: String,
        cancellationSignal: CancellationSignal = NeverCancelled,
    ): SealedSourceSnapshot = sealAcceptedText(sharedText, cancellationSignal)

    /** Direct text is considered accepted when this submission method is invoked. */
    suspend fun sealAcceptedDirectText(
        submittedText: String,
        cancellationSignal: CancellationSignal = NeverCancelled,
    ): SealedSourceSnapshot = sealAcceptedText(submittedText, cancellationSignal)

    private suspend fun sealAcceptedText(
        text: String,
        cancellationSignal: CancellationSignal,
    ): SealedSourceSnapshot {
        val encoded = text.encodeToByteArray()
        return try {
            seal(
                input = ByteArrayInputStream(encoded),
                delayPolicy = null,
                cancellationSignal = cancellationSignal,
            )
        } finally {
            encoded.fill(0)
        }
    }

    private suspend fun seal(
        input: InputStream,
        delayPolicy: BoundedDelayPolicy?,
        cancellationSignal: CancellationSignal,
    ): SealedSourceSnapshot {
        if (!acceptanceState.compareAndSet(SourceAcceptanceState.IDLE, SourceAcceptanceState.SEALING)) {
            runCatching { input.close() }
            throw SnapshotAlreadyAcceptedException()
        }
        var accepted = false
        return try {
            sealInternal(input, delayPolicy, cancellationSignal).also { accepted = true }
        } finally {
            acceptanceState.set(
                if (accepted) SourceAcceptanceState.ACCEPTED else SourceAcceptanceState.IDLE,
            )
        }
    }

    private suspend fun sealInternal(
        input: InputStream,
        delayPolicy: BoundedDelayPolicy?,
        cancellationSignal: CancellationSignal,
    ): SealedSourceSnapshot = withContext(ioDispatcher) {
        val allocation = allocateFiles()
        var completed = false
        try {
            input.use { providerInput ->
                cancellationSignal.throwIfCancellationRequested()
                copyBounded(providerInput, allocation.partial, cancellationSignal)
            }
            val byteCount = allocation.partial.length()
            val digestBeforeSeal = FileInputStream(allocation.partial).use(digester::digest)

            if (delayPolicy != null) recheckDelay.await(delayPolicy, cancellationSignal)
            cancellationSignal.throwIfCancellationRequested()
            coroutineContext.ensureActive()

            if (!allocation.partial.renameTo(allocation.sealed)) throw SnapshotCreationException()
            val preAnchorOutcome = verifyFile(
                file = allocation.sealed,
                expectedLength = byteCount,
                expectedDigest = digestBeforeSeal,
                expectedName = allocation.sealed.name,
            )
            if (preAnchorOutcome != SnapshotIntegrityOutcome.VALID) {
                throw SnapshotIntegrityException(preAnchorOutcome)
            }

            val descriptor = SourceSnapshotDescriptor(
                sourceHandle = allocation.handle,
                byteCount = ByteCount(byteCount),
                digest = digestBeforeSeal,
                importAnchor = importAnchorRecorder.recordAcceptedImport(),
            )
            completed = true
            FileSealedSourceSnapshot(
                file = allocation.sealed,
                workspace = workspace,
                descriptor = descriptor,
                limits = limits,
                digester = digester,
                ioDispatcher = ioDispatcher,
            )
        } catch (exception: SnapshotIntegrityException) {
            throw exception
        } catch (exception: SnapshotResourceLimitException) {
            throw exception
        } catch (exception: SessionCancellationException) {
            throw exception
        } catch (exception: kotlinx.coroutines.CancellationException) {
            throw exception
        } catch (_: Exception) {
            throw SnapshotCreationException()
        } finally {
            if (!completed) {
                runCatching { allocation.partial.delete() }
                runCatching { allocation.sealed.delete() }
            }
        }
    }

    private suspend fun copyBounded(
        input: InputStream,
        destination: File,
        cancellationSignal: CancellationSignal,
    ) {
        val buffer = ByteArray(limits.copyBufferBytes)
        var total = 0L
        try {
            FileOutputStream(destination, false).use { output ->
                while (true) {
                    cancellationSignal.throwIfCancellationRequested()
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (total > limits.maximumBytes - count) throw SnapshotResourceLimitException()
                    output.write(buffer, 0, count)
                    total += count
                }
                output.fd.sync()
            }
        } finally {
            buffer.fill(0)
        }
    }

    private fun allocateFiles(): SnapshotFileAllocation {
        repeat(MAX_ALLOCATION_ATTEMPTS) {
            val handle = sourceHandleGenerator.next()
            val partial = safeChild("${handle.value}.partial")
            val sealed = safeChild("${handle.value}.sealed")
            if (!partial.exists() && !sealed.exists()) {
                return SnapshotFileAllocation(handle, partial, sealed)
            }
        }
        throw SnapshotCreationException()
    }

    private fun safeChild(name: String): File {
        val child = File(workspace, name).canonicalFile
        if (child.parentFile != workspace) throw SnapshotCreationException()
        return child
    }

    private fun verifyFile(
        file: File,
        expectedLength: Long,
        expectedDigest: ContentDigest,
        expectedName: String,
    ): SnapshotIntegrityOutcome {
        if (!file.exists()) return SnapshotIntegrityOutcome.MISSING
        val canonical = runCatching { file.canonicalFile }.getOrNull()
            ?: return SnapshotIntegrityOutcome.READ_FAILURE
        if (canonical.parentFile != workspace || canonical.name != expectedName) {
            return SnapshotIntegrityOutcome.LOCATION_CHANGED
        }
        if (canonical.length() != expectedLength) return SnapshotIntegrityOutcome.LENGTH_MISMATCH
        val actualDigest = runCatching { FileInputStream(canonical).use(digester::digest) }.getOrNull()
            ?: return SnapshotIntegrityOutcome.READ_FAILURE
        return if (actualDigest == expectedDigest) {
            SnapshotIntegrityOutcome.VALID
        } else {
            SnapshotIntegrityOutcome.DIGEST_MISMATCH
        }
    }

    private data class SnapshotFileAllocation(
        val handle: SourceHandle,
        val partial: File,
        val sealed: File,
    )

    private companion object {
        const val MAX_ALLOCATION_ATTEMPTS = 16
    }

    private enum class SourceAcceptanceState { IDLE, SEALING, ACCEPTED }
}

private class FileSealedSourceSnapshot(
    private val file: File,
    private val workspace: File,
    override val descriptor: SourceSnapshotDescriptor,
    private val limits: SnapshotLimits,
    private val digester: ContentDigester,
    private val ioDispatcher: CoroutineDispatcher,
) : SealedSourceSnapshot {
    override suspend fun readVerified(): ByteArray = withContext(ioDispatcher) {
        val locationOutcome = validateLocation()
        if (locationOutcome != SnapshotIntegrityOutcome.VALID) {
            throw SnapshotIntegrityException(locationOutcome)
        }
        val expectedLength = descriptor.byteCount.value
        if (file.length() != expectedLength) {
            throw SnapshotIntegrityException(SnapshotIntegrityOutcome.LENGTH_MISMATCH)
        }
        val bytes = try {
            readBounded(file)
        } catch (exception: SnapshotResourceLimitException) {
            throw exception
        } catch (_: IOException) {
            throw SnapshotIntegrityException(SnapshotIntegrityOutcome.READ_FAILURE)
        }
        if (bytes.size.toLong() != expectedLength) {
            bytes.fill(0)
            throw SnapshotIntegrityException(SnapshotIntegrityOutcome.LENGTH_MISMATCH)
        }
        if (!digester.matches(bytes, descriptor.digest)) {
            bytes.fill(0)
            throw SnapshotIntegrityException(SnapshotIntegrityOutcome.DIGEST_MISMATCH)
        }
        bytes
    }

    override suspend fun verify(): SnapshotVerification = withContext(ioDispatcher) {
        val locationOutcome = validateLocation()
        if (locationOutcome != SnapshotIntegrityOutcome.VALID) {
            return@withContext SnapshotVerification(locationOutcome)
        }
        if (file.length() != descriptor.byteCount.value) {
            return@withContext SnapshotVerification(SnapshotIntegrityOutcome.LENGTH_MISMATCH)
        }
        val actual = runCatching { FileInputStream(file).use(digester::digest) }.getOrNull()
            ?: return@withContext SnapshotVerification(SnapshotIntegrityOutcome.READ_FAILURE)
        SnapshotVerification(
            if (actual == descriptor.digest) {
                SnapshotIntegrityOutcome.VALID
            } else {
                SnapshotIntegrityOutcome.DIGEST_MISMATCH
            },
        )
    }

    override suspend fun deleteLogical(): LogicalSnapshotDeletionResult = withContext(ioDispatcher) {
        if (!file.exists()) return@withContext LogicalSnapshotDeletionResult.ALREADY_ABSENT
        try {
            if (file.delete()) {
                LogicalSnapshotDeletionResult.DELETED
            } else {
                LogicalSnapshotDeletionResult.FAILED_BEST_EFFORT
            }
        } catch (_: SecurityException) {
            LogicalSnapshotDeletionResult.FAILED_BEST_EFFORT
        }
    }

    private fun validateLocation(): SnapshotIntegrityOutcome {
        if (!file.exists()) return SnapshotIntegrityOutcome.MISSING
        val canonical = runCatching { file.canonicalFile }.getOrNull()
            ?: return SnapshotIntegrityOutcome.READ_FAILURE
        return if (canonical.parentFile == workspace && canonical.name == file.name) {
            SnapshotIntegrityOutcome.VALID
        } else {
            SnapshotIntegrityOutcome.LOCATION_CHANGED
        }
    }

    private fun readBounded(source: File): ByteArray {
        val initialSize = source.length().coerceAtMost(limits.maximumBytes).toInt()
        val output = ByteArrayOutputStream(initialSize)
        val buffer = ByteArray(limits.copyBufferBytes)
        var total = 0L
        try {
            FileInputStream(source).use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (total > limits.maximumBytes - count) throw SnapshotResourceLimitException()
                    output.write(buffer, 0, count)
                    total += count
                }
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }

    override fun toString(): String = "SealedSourceSnapshot(source=redacted)"
}
