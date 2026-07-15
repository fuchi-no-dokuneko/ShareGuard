package app.shareguard.core.storage

import app.shareguard.core.model.ArtifactKind
import app.shareguard.core.model.ContentDigest
import app.shareguard.core.model.MimeType
import app.shareguard.core.model.SavedResultId
import app.shareguard.core.security.AuthenticatedCiphertext
import app.shareguard.core.security.AuthenticatedEncryption
import app.shareguard.core.security.AuthenticationFailedException
import app.shareguard.core.security.ContentDigester
import app.shareguard.core.security.KeyAlias
import app.shareguard.core.security.KeyUnavailableException
import app.shareguard.core.security.SecretBytes
import app.shareguard.core.security.Sha256ContentDigester
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ArtifactBinding(
    val savedResultId: SavedResultId,
    val kind: ArtifactKind,
    val canonicalRevision: Long,
    val artifactRevision: Long,
    val mimeType: MimeType,
    val contentDigest: ContentDigest,
    val byteCount: Long,
) {
    init { require(byteCount in 1..Int.MAX_VALUE.toLong()) { "Artifact is outside the supported resource budget" } }

    fun associatedData(): ByteArray = buildString {
        append("shareguard-artifact-v1\u0000")
        append(savedResultId.value).append('\u0000')
        append(kind.name).append('\u0000')
        append(canonicalRevision).append('\u0000')
        append(artifactRevision).append('\u0000')
        append(mimeType.value).append('\u0000')
        append(contentDigest.sha256).append('\u0000')
        append(byteCount)
    }.encodeToByteArray()
}

internal data class StagedArtifact(
    val binding: ArtifactBinding,
    val stagedFile: File,
    val finalFile: File,
)

internal data class FinalArtifact(
    val binding: ArtifactBinding,
    val file: File,
    val relativePath: String,
)

/** AES-GCM envelope store. Only ciphertext is written under the persistent artifact roots. */
class EncryptedArtifactStore(
    private val layout: AppPrivateStorageLayout,
    private val encryption: AuthenticatedEncryption,
    private val digester: ContentDigester = Sha256ContentDigester(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    internal suspend fun stage(
        binding: ArtifactBinding,
        keyAlias: KeyAlias,
        payload: SecretBytes,
    ): StagedArtifact = withContext(ioDispatcher) {
        val staged = layout.stagedArtifact(binding.savedResultId, binding.kind)
        val final = layout.finalArtifact(binding.savedResultId, binding.kind)
        if (staged.exists() || final.exists()) {
            throw SavedResultStorageException(StorageFailureReason.STAGED_WRITE_FAILED)
        }
        try {
            payload.access { plaintext ->
                if (plaintext.size.toLong() != binding.byteCount ||
                    !digester.matches(plaintext, binding.contentDigest)
                ) {
                    throw SavedResultStorageException(StorageFailureReason.PAYLOAD_DIGEST_MISMATCH)
                }
                writeEnvelope(staged, keyAlias, binding.associatedData(), plaintext)
            }
            restrictOwnerOnly(staged)
            StagedArtifact(binding, staged, final)
        } catch (error: SavedResultStorageException) {
            runCatching { staged.delete() }
            throw error
        } catch (error: Exception) {
            runCatching { staged.delete() }
            throw SavedResultStorageException(StorageFailureReason.STAGED_WRITE_FAILED, error)
        }
    }

    internal suspend fun verifyStaged(staged: StagedArtifact, keyAlias: KeyAlias) {
        readAndVerify(staged.stagedFile, staged.binding, keyAlias).close()
    }

    internal suspend fun moveToFinal(staged: StagedArtifact): FinalArtifact = withContext(ioDispatcher) {
        if (staged.finalFile.exists() || !staged.stagedFile.renameTo(staged.finalFile)) {
            throw SavedResultStorageException(StorageFailureReason.FINAL_MOVE_FAILED)
        }
        restrictOwnerReadOnly(staged.finalFile)
        FinalArtifact(staged.binding, staged.finalFile, layout.persistentRelative(staged.finalFile))
    }

    internal suspend fun reopenFinal(final: FinalArtifact, keyAlias: KeyAlias): SecretBytes =
        readAndVerify(final.file, final.binding, keyAlias, StorageFailureReason.FINAL_REOPEN_FAILED)

    internal suspend fun readFinal(
        result: SavedResultEntity,
        artifact: SavedArtifactEntity,
    ): SecretBytes {
        val binding = artifact.binding(result)
        val file = try {
            layout.resolvePersistent(artifact.relativePath)
        } catch (error: Exception) {
            throw SavedResultStorageException(StorageFailureReason.PATH_OUTSIDE_APPROVED_ROOT, error)
        }
        return readAndVerify(file, binding, KeyAlias(result.keyAlias), StorageFailureReason.FINAL_REOPEN_FAILED)
    }

    internal suspend fun writeAuxiliaryEncrypted(
        destination: File,
        keyAlias: KeyAlias,
        associatedData: ByteArray,
        plaintext: ByteArray,
    ) = withContext(ioDispatcher) {
        try {
            writeEnvelope(destination, keyAlias, associatedData, plaintext)
            restrictOwnerReadOnly(destination)
        } catch (error: SavedResultStorageException) {
            throw error
        } catch (error: Exception) {
            throw SavedResultStorageException(StorageFailureReason.STAGED_WRITE_FAILED, error)
        }
    }

    internal suspend fun readAuxiliaryEncrypted(
        file: File,
        keyAlias: KeyAlias,
        associatedData: ByteArray,
        expectedDigest: ContentDigest? = null,
    ): SecretBytes = withContext(ioDispatcher) {
        val plaintext = readEnvelope(file, keyAlias, associatedData, null)
        if (expectedDigest != null && !digester.matches(plaintext, expectedDigest)) {
            plaintext.fill(0)
            throw SavedResultStorageException(StorageFailureReason.PREVIEW_INVALID)
        }
        SecretBytes.consume(plaintext)
    }

    internal suspend fun deleteLogical(file: File): Boolean = withContext(ioDispatcher) {
        if (!file.exists()) true else runCatching { file.delete() && !file.exists() }.getOrDefault(false)
    }

    private suspend fun readAndVerify(
        file: File,
        binding: ArtifactBinding,
        keyAlias: KeyAlias,
        failureReason: StorageFailureReason = StorageFailureReason.STAGED_REOPEN_FAILED,
    ): SecretBytes = withContext(ioDispatcher) {
        val plaintext = readEnvelope(file, keyAlias, binding.associatedData(), binding.byteCount)
        if (!digester.matches(plaintext, binding.contentDigest)) {
            plaintext.fill(0)
            throw SavedResultStorageException(StorageFailureReason.PAYLOAD_DIGEST_MISMATCH)
        }
        try {
            SecretBytes.consume(plaintext)
        } catch (error: Exception) {
            plaintext.fill(0)
            throw SavedResultStorageException(failureReason, error)
        }
    }

    private fun writeEnvelope(
        file: File,
        keyAlias: KeyAlias,
        associatedData: ByteArray,
        plaintext: ByteArray,
    ) {
        val encrypted = try {
            encryption.encrypt(keyAlias, plaintext, associatedData)
        } catch (error: Exception) {
            throw SavedResultStorageException(StorageFailureReason.ENCRYPTION_FAILED, error)
        } finally {
            associatedData.fill(0)
        }
        val nonce = encrypted.nonceCopy()
        val ciphertext = encrypted.encryptedCopy()
        try {
            FileOutputStream(file, false).use { fileOutput ->
                val output = DataOutputStream(BufferedOutputStream(fileOutput))
                output.writeInt(ENVELOPE_MAGIC)
                output.writeInt(encrypted.formatVersion)
                output.writeInt(nonce.size)
                output.writeInt(ciphertext.size)
                output.write(nonce)
                output.write(ciphertext)
                output.flush()
                fileOutput.fd.sync()
            }
        } finally {
            nonce.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun readEnvelope(
        file: File,
        keyAlias: KeyAlias,
        associatedData: ByteArray,
        expectedPlaintextBytes: Long?,
    ): ByteArray {
        if (!file.isFile) {
            associatedData.fill(0)
            throw SavedResultStorageException(StorageFailureReason.FINAL_REOPEN_FAILED)
        }
        var nonce = byteArrayOf()
        var encrypted = byteArrayOf()
        try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != ENVELOPE_MAGIC) {
                    throw SavedResultStorageException(StorageFailureReason.AUTHENTICATION_FAILED)
                }
                val version = input.readInt()
                val nonceSize = input.readInt()
                val encryptedSize = input.readInt()
                if (version != AuthenticatedCiphertext.CURRENT_FORMAT_VERSION ||
                    nonceSize != AuthenticatedCiphertext.NONCE_BYTES ||
                    encryptedSize < AuthenticatedCiphertext.TAG_BYTES ||
                    encryptedSize > MAXIMUM_ENCRYPTED_BYTES ||
                    (expectedPlaintextBytes != null &&
                        encryptedSize.toLong() != expectedPlaintextBytes + AuthenticatedCiphertext.TAG_BYTES)
                ) {
                    throw SavedResultStorageException(StorageFailureReason.AUTHENTICATION_FAILED)
                }
                nonce = ByteArray(nonceSize)
                encrypted = ByteArray(encryptedSize)
                input.readFully(nonce)
                input.readFully(encrypted)
                if (input.read() != -1) throw SavedResultStorageException(StorageFailureReason.AUTHENTICATION_FAILED)
            }
            val envelope = AuthenticatedCiphertext.create(nonce, encrypted)
            return encryption.decrypt(keyAlias, envelope, associatedData)
        } catch (error: KeyUnavailableException) {
            throw SavedResultStorageException(StorageFailureReason.KEY_UNAVAILABLE, error)
        } catch (error: AuthenticationFailedException) {
            throw SavedResultStorageException(StorageFailureReason.AUTHENTICATION_FAILED, error)
        } catch (error: SavedResultStorageException) {
            throw error
        } catch (error: EOFException) {
            throw SavedResultStorageException(StorageFailureReason.AUTHENTICATION_FAILED, error)
        } catch (error: IOException) {
            throw SavedResultStorageException(StorageFailureReason.FINAL_REOPEN_FAILED, error)
        } finally {
            nonce.fill(0)
            encrypted.fill(0)
            associatedData.fill(0)
        }
    }

    private fun restrictOwnerOnly(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        if (!file.setReadable(true, true) || !file.setWritable(true, true)) {
            throw SavedResultStorageException(StorageFailureReason.STAGED_WRITE_FAILED)
        }
    }

    private fun restrictOwnerReadOnly(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        if (!file.setReadable(true, true)) {
            throw SavedResultStorageException(StorageFailureReason.STAGED_WRITE_FAILED)
        }
    }

    private companion object {
        const val ENVELOPE_MAGIC: Int = 0x53475231
        const val MAXIMUM_ENCRYPTED_BYTES: Int = Int.MAX_VALUE - 8
    }
}

internal fun SavedArtifactEntity.binding(result: SavedResultEntity): ArtifactBinding = ArtifactBinding(
    savedResultId = SavedResultId(savedResultId),
    kind = ArtifactKind.valueOf(artifactKind),
    canonicalRevision = result.canonicalRevision,
    artifactRevision = artifactRevision,
    mimeType = MimeType(mimeType),
    contentDigest = ContentDigest(contentDigest),
    byteCount = byteCount,
)
