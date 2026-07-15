package app.shareguard.core.storage

import app.shareguard.core.model.ArtifactKind
import app.shareguard.core.model.ContentDigest
import app.shareguard.core.model.MimeType
import app.shareguard.core.model.PreviewReference
import app.shareguard.core.model.SavedResultId
import app.shareguard.core.security.KeyAlias
import app.shareguard.core.security.SecretBytes
import java.nio.charset.StandardCharsets
import kotlin.math.min

data class PreviewPolicy(
    val enabled: Boolean,
    val maximumBytes: Int,
) {
    init { require(maximumBytes in 1..MAXIMUM_PREVIEW_BUDGET) { "Preview budget is invalid" } }

    private companion object {
        const val MAXIMUM_PREVIEW_BUDGET = 1024 * 1024
    }
}

fun interface SafePreviewGenerator {
    /** Receives only reopened final Managed Artifact bytes, never a source/session representation. */
    fun generate(kind: ArtifactKind, mimeType: MimeType, finalArtifact: ByteArray, maximumBytes: Int): ByteArray?
}

object TextOnlySafePreviewGenerator : SafePreviewGenerator {
    override fun generate(
        kind: ArtifactKind,
        mimeType: MimeType,
        finalArtifact: ByteArray,
        maximumBytes: Int,
    ): ByteArray? {
        if (kind != ArtifactKind.CANONICAL_TEXT || !mimeType.value.startsWith("text/")) return null
        val text = String(finalArtifact, StandardCharsets.UTF_8)
        return text.take(min(text.length, maximumBytes)).encodeToByteArray().let { encoded ->
            if (encoded.size <= maximumBytes) encoded else encoded.copyOf(maximumBytes).also { encoded.fill(0) }
        }
    }
}

sealed interface PreviewBuildResult {
    data class Available(val previewReference: PreviewReference, val byteCount: Int) : PreviewBuildResult
    data class GenericIcon(val reasonCode: String) : PreviewBuildResult
}

class SavedResultPreviewRepository(
    private val repository: SavedResultRepository,
    private val policy: PreviewPolicy,
    private val generator: SafePreviewGenerator = TextOnlySafePreviewGenerator,
) {
    suspend fun build(savedResultId: SavedResultId): PreviewBuildResult {
        if (!policy.enabled) {
            clear(savedResultId)
            return PreviewBuildResult.GenericIcon("PREVIEWS_DISABLED")
        }
        val record = repository.requireShareable(savedResultId)
        val source = record.artifacts.sortedBy { it.artifactKind }.firstOrNull()
            ?: return PreviewBuildResult.GenericIcon("NO_FINAL_ARTIFACT")
        val kind = ArtifactKind.valueOf(source.artifactKind)
        val previewBytes = try {
            repository.artifactStore.readFinal(record.result, source).use { finalArtifact ->
                finalArtifact.access { bytes ->
                    generator.generate(kind, MimeType(source.mimeType), bytes, policy.maximumBytes)
                }
            }
        } catch (_: Exception) {
            null
        }
        if (previewBytes == null || previewBytes.isEmpty() || previewBytes.size > policy.maximumBytes) {
            previewBytes?.fill(0)
            clear(savedResultId)
            return PreviewBuildResult.GenericIcon("PREVIEW_GENERATION_FAILED")
        }

        val reference = PreviewReference("preview-${savedResultId.value.removePrefix("result-")}")
        val file = repository.layout.preview(savedResultId)
        val associatedData = previewAssociatedData(savedResultId, reference, source)
        return try {
            repository.artifactStore.writeAuxiliaryEncrypted(
                file,
                KeyAlias(record.result.keyAlias),
                associatedData,
                previewBytes,
            )
            repository.artifactStore.readAuxiliaryEncrypted(
                file,
                KeyAlias(record.result.keyAlias),
                previewAssociatedData(savedResultId, reference, source),
            ).close()
            repository.dao.replacePreview(
                SavedPreviewEntity(
                    savedResultId = savedResultId.value,
                    previewReference = reference.value,
                    relativePath = repository.layout.persistentRelative(file),
                    sourceArtifactKind = source.artifactKind,
                    sourceArtifactDigest = source.contentDigest,
                    mimeType = source.mimeType,
                    byteCount = previewBytes.size.toLong(),
                ),
            )
            PreviewBuildResult.Available(reference, previewBytes.size)
        } catch (_: Exception) {
            runCatching { file.delete() }
            repository.dao.deletePreview(savedResultId.value)
            repository.dao.setPreviewReference(savedResultId.value, null)
            PreviewBuildResult.GenericIcon("PREVIEW_PERSISTENCE_FAILED")
        } finally {
            previewBytes.fill(0)
        }
    }

    suspend fun read(savedResultId: SavedResultId): SecretBytes? {
        val record = repository.requireShareable(savedResultId)
        val preview = repository.dao.findPreview(savedResultId.value) ?: return null
        val source = record.artifacts.singleOrNull {
            it.artifactKind == preview.sourceArtifactKind && it.contentDigest == preview.sourceArtifactDigest
        } ?: run {
            invalidatePreview(savedResultId)
            return null
        }
        val reference = runCatching { PreviewReference(preview.previewReference) }.getOrNull() ?: run {
            invalidatePreview(savedResultId)
            return null
        }
        val file = trustedPreviewFile(savedResultId, preview) ?: run {
            invalidatePreview(savedResultId)
            return null
        }
        return try {
            repository.artifactStore.readAuxiliaryEncrypted(
                file,
                KeyAlias(record.result.keyAlias),
                previewAssociatedData(savedResultId, reference, source),
            )
        } catch (_: Exception) {
            invalidatePreview(savedResultId)
            null
        }
    }

    suspend fun validate(savedResultId: SavedResultId): Boolean {
        val preview = repository.dao.findPreview(savedResultId.value) ?: return true
        return read(savedResultId)?.use { it.size.toLong() == preview.byteCount } ?: false
    }

    suspend fun clear(savedResultId: SavedResultId): Boolean {
        // Preview metadata is never trusted to select a deletion target. The per-result preview path is
        // deterministic, so even a substituted locator cannot delete an authoritative artifact.
        val deleted = runCatching {
            repository.artifactStore.deleteLogical(repository.layout.preview(savedResultId))
        }.getOrDefault(false)
        repository.dao.deletePreview(savedResultId.value)
        repository.dao.setPreviewReference(savedResultId.value, null)
        return deleted
    }

    private fun trustedPreviewFile(
        savedResultId: SavedResultId,
        preview: SavedPreviewEntity,
    ): java.io.File? = runCatching {
        val expected = repository.layout.preview(savedResultId)
        expected.takeIf { preview.relativePath == repository.layout.persistentRelative(expected) }
    }.getOrNull()

    private suspend fun invalidatePreview(savedResultId: SavedResultId) {
        runCatching { repository.layout.preview(savedResultId).delete() }
        repository.dao.deletePreview(savedResultId.value)
        repository.dao.setPreviewReference(savedResultId.value, null)
    }

    private fun previewAssociatedData(
        savedResultId: SavedResultId,
        reference: PreviewReference,
        source: SavedArtifactEntity,
    ): ByteArray = buildString {
        append("shareguard-preview-v1\u0000")
        append(savedResultId.value).append('\u0000')
        append(reference.value).append('\u0000')
        append(source.artifactKind).append('\u0000')
        append(source.contentDigest)
    }.encodeToByteArray()
}
