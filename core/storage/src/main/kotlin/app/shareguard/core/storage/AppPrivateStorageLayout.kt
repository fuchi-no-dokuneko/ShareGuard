package app.shareguard.core.storage

import android.content.Context
import app.shareguard.core.model.ArtifactKind
import app.shareguard.core.model.SavedResultId
import java.io.File
import java.io.IOException

/**
 * Resolves every managed path beneath one of two caller-provided app-private roots. Public storage and
 * arbitrary absolute paths are intentionally not representable through this API.
 */
class AppPrivateStorageLayout(
    persistentPrivateRoot: File,
    cachePrivateRoot: File,
) {
    private val persistentPrivateRoot = canonicalDirectory(persistentPrivateRoot)
    private val cachePrivateRoot = canonicalDirectory(cachePrivateRoot)

    internal val savedResultsRoot: File = approvedDirectory(this.persistentPrivateRoot, "saved-results-v2")
    internal val stagingRoot: File = approvedDirectory(this.persistentPrivateRoot, "saved-results-staging-v2")
    internal val managedShareRoot: File = approvedDirectory(this.cachePrivateRoot, "managed-share-v2")

    init {
        require(savedResultsRoot != stagingRoot && savedResultsRoot != managedShareRoot) {
            "Persistent, staging, and share-cache roots must be separated"
        }
    }

    internal fun stagedArtifact(savedResultId: SavedResultId, kind: ArtifactKind): File =
        safeChild(approvedDirectory(stagingRoot, savedResultId.value), "${kind.storageName}.stage")

    internal fun finalArtifact(savedResultId: SavedResultId, kind: ArtifactKind): File =
        safeChild(approvedDirectory(resultDirectory(savedResultId), "artifacts"), "${kind.storageName}.enc")

    internal fun preview(savedResultId: SavedResultId): File =
        safeChild(approvedDirectory(resultDirectory(savedResultId), "preview"), "preview.enc")

    internal fun shareCache(savedResultId: SavedResultId, token: String): File =
        safeChild(approvedDirectory(managedShareRoot, savedResultId.value), "$token.share")

    internal fun resultDirectory(savedResultId: SavedResultId): File =
        approvedDirectory(savedResultsRoot, savedResultId.value)

    internal fun stagingDirectory(savedResultId: SavedResultId): File =
        approvedDirectory(stagingRoot, savedResultId.value)

    internal fun shareDirectory(savedResultId: SavedResultId): File =
        approvedDirectory(managedShareRoot, savedResultId.value)

    internal fun hasAnyResultFootprint(savedResultId: SavedResultId): Boolean =
        safeChild(savedResultsRoot, savedResultId.value).exists() ||
            safeChild(stagingRoot, savedResultId.value).exists()

    internal fun persistentRelative(file: File): String {
        val canonical = canonicalApproved(file, savedResultsRoot)
        return savedResultsRoot.toPath().relativize(canonical.toPath()).joinToString("/")
    }

    internal fun resolvePersistent(relativePath: String): File {
        require(relativePath.isNotBlank()) { "Relative path cannot be blank" }
        require(!File(relativePath).isAbsolute) { "Absolute paths are forbidden" }
        require('\\' !in relativePath) { "Platform-independent stored paths use forward slashes" }
        val segments = relativePath.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) { "Path traversal is forbidden" }
        return canonicalApproved(File(savedResultsRoot, relativePath), savedResultsRoot)
    }

    internal fun resolveShare(savedResultId: SavedResultId, token: String): File =
        canonicalApproved(shareCache(savedResultId, token), managedShareRoot)

    internal fun isApprovedPersistent(file: File): Boolean = runCatching {
        canonicalApproved(file, savedResultsRoot)
        true
    }.getOrDefault(false)

    private fun approvedDirectory(parent: File, name: String): File {
        require(SAFE_SEGMENT.matches(name)) { "Storage directory name must be opaque" }
        val directory = canonicalApproved(File(parent, name), parent)
        if (!directory.exists() && !directory.mkdirs()) throw IOException("APP_PRIVATE_DIRECTORY_CREATION_FAILED")
        if (!directory.isDirectory) throw IOException("APP_PRIVATE_DIRECTORY_INVALID")
        return directory
    }

    private fun safeChild(parent: File, name: String): File {
        require(SAFE_SEGMENT.matches(name)) { "Storage file name must be opaque" }
        return canonicalApproved(File(parent, name), parent)
    }

    private fun canonicalApproved(candidate: File, approvedRoot: File): File {
        val canonical = runCatching { candidate.canonicalFile }
            .getOrElse { throw SavedResultStorageException(StorageFailureReason.PATH_OUTSIDE_APPROVED_ROOT) }
        val rootPath = approvedRoot.canonicalFile.toPath()
        if (!canonical.toPath().startsWith(rootPath) || canonical == approvedRoot) {
            throw SavedResultStorageException(StorageFailureReason.PATH_OUTSIDE_APPROVED_ROOT)
        }
        return canonical
    }

    companion object {
        fun from(context: Context): AppPrivateStorageLayout = AppPrivateStorageLayout(
            persistentPrivateRoot = context.noBackupFilesDir,
            cachePrivateRoot = context.cacheDir,
        )

        private val SAFE_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        private fun canonicalDirectory(directory: File): File {
            val canonical = runCatching { directory.canonicalFile }
                .getOrElse { throw IOException("APP_PRIVATE_ROOT_INVALID") }
            if (!canonical.exists() && !canonical.mkdirs()) throw IOException("APP_PRIVATE_ROOT_CREATION_FAILED")
            if (!canonical.isDirectory) throw IOException("APP_PRIVATE_ROOT_INVALID")
            return canonical
        }
    }
}

internal val ArtifactKind.storageName: String
    get() = when (this) {
        ArtifactKind.CANONICAL_TEXT -> "text"
        ArtifactKind.REBUILT_IMAGE -> "image"
        ArtifactKind.DERIVATIVE_IMAGE -> "derivative"
    }
