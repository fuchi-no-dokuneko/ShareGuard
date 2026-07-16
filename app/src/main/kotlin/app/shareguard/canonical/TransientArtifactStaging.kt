package app.shareguard.canonical

import android.content.Context
import app.shareguard.block.verify.ArtifactReopener
import app.shareguard.block.verify.ProviderResult
import app.shareguard.block.verify.ReopenedArtifact
import app.shareguard.core.model.ArtifactReference
import app.shareguard.core.model.OutputArtifact
import app.shareguard.core.security.Sha256ContentDigester
import app.shareguard.core.session.LogicalCleanupAction
import app.shareguard.core.session.LogicalCleanupOutcome
import app.shareguard.core.session.ManagedSession
import app.shareguard.core.storage.isStrictlyInside
import app.shareguard.core.storage.isSymbolicLinkCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Exact, app-private verification candidates. Source bytes are never accepted by this store. */
class TransientArtifactStaging private constructor(
    private val approvedRoot: File,
    private val sessionDirectory: File,
) : ArtifactReopener, AutoCloseable {
    private val digester = Sha256ContentDigester()
    private val files = linkedMapOf<ArtifactReference, File>()

    suspend fun stage(artifact: OutputArtifact, bytes: ByteArray) = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty() && bytes.size.toLong() == artifact.byteCount.value)
        require(digester.digest(bytes) == artifact.digest)
        val candidate = approvedChild("${artifact.kind.name.lowercase()}.candidate")
        FileOutputStream(candidate, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        require(candidate.length() == artifact.byteCount.value)
        files[artifact.reference] = candidate
    }

    override suspend fun reopen(artifact: OutputArtifact): ProviderResult<ReopenedArtifact> =
        withContext(Dispatchers.IO) {
            val file = files[artifact.reference]
                ?: return@withContext ProviderResult.NotRun("ARTIFACT_NOT_STAGED")
            val canonical = runCatching { file.canonicalFile }.getOrNull()
                ?: return@withContext ProviderResult.Error("ARTIFACT_PATH_INVALID")
            if (!canonical.isStrictlyInside(sessionDirectory) || !canonical.isFile) {
                return@withContext ProviderResult.Error("ARTIFACT_LOCATION_INVALID")
            }
            val bytes = runCatching { FileInputStream(canonical).use { it.readBytes() } }.getOrNull()
                ?: return@withContext ProviderResult.Error("ARTIFACT_REOPEN_FAILED")
            val digest = digester.digest(bytes)
            ProviderResult.Completed(
                ReopenedArtifact.create(
                    artifactRevision = artifact.artifactRevision,
                    canonicalRevision = artifact.canonicalRevision,
                    detectedMimeType = artifact.mimeType,
                    digest = digest,
                    appPrivateLocation = true,
                    bytes = bytes,
                ),
            ).also { bytes.fill(0) }
        }

    override fun close() {
        files.clear()
        deleteTree(sessionDirectory)
    }

    private fun approvedChild(name: String): File {
        require(name.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}")))
        val child = File(sessionDirectory, name).canonicalFile
        require(child.parentFile == sessionDirectory)
        return child
    }

    private fun deleteTree(file: File): Boolean {
        if (!file.exists()) return true
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        if (!canonical.isStrictlyInside(approvedRoot)) return false
        var complete = true
        if (file.isDirectory && !file.isSymbolicLinkCompat()) {
            file.listFiles().orEmpty().forEach { if (!deleteTree(it)) complete = false }
        }
        if (!file.delete() && file.exists()) complete = false
        return complete
    }

    companion object {
        fun create(context: Context, session: ManagedSession): TransientArtifactStaging {
            val root = File(context.cacheDir, "verified-candidates-v1").canonicalFile
            if (!root.exists() && !root.mkdirs()) error("VERIFICATION_STAGING_ROOT_UNAVAILABLE")
            val directory = File(root, "candidate-${session.sessionId.value}").canonicalFile
            require(directory.parentFile == root)
            if (!directory.exists() && !directory.mkdir()) error("VERIFICATION_STAGING_UNAVAILABLE")
            val staging = TransientArtifactStaging(root, directory)
            session.lifecycle.registerSessionTransient(
                LogicalCleanupAction {
                    if (staging.deleteTree(directory)) {
                        LogicalCleanupOutcome.DELETED
                    } else {
                        LogicalCleanupOutcome.FAILED_BEST_EFFORT
                    }
                },
            )
            return staging
        }
    }
}
