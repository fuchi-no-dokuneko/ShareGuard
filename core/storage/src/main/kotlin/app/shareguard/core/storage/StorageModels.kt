package app.shareguard.core.storage

import app.shareguard.core.model.AppBuildId
import app.shareguard.core.model.ArtifactKind
import app.shareguard.core.model.DisplayLabel
import app.shareguard.core.model.ImportAnchor
import app.shareguard.core.model.OutputArtifact
import app.shareguard.core.model.OutputBundle
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SavedResult
import app.shareguard.core.model.SavedResultDeletionResult
import app.shareguard.core.model.SavedResultId
import app.shareguard.core.model.VerificationReport
import app.shareguard.core.model.WallClockInstant
import app.shareguard.core.security.SecretBytes
import java.security.SecureRandom

class PersistVerifiedResultRequest(
    val outputBundle: OutputBundle,
    val verificationReport: VerificationReport,
    val importAnchor: ImportAnchor,
    val displayLabel: DisplayLabel,
    val assuranceRationaleSummary: SafeSummary,
    val createdByAppBuild: AppBuildId,
    artifactPayloads: Map<ArtifactKind, SecretBytes>,
) {
    val artifactPayloads: Map<ArtifactKind, SecretBytes> = artifactPayloads.toMap()

    init {
        require(outputBundle.verificationReport == null || outputBundle.verificationReport == verificationReport) {
            "Output bundle verification report differs from the persistence request"
        }
        require(verificationReport.assuranceClass != app.shareguard.core.model.AssuranceClass.AS_0_UNVERIFIED) {
            "Only verified output can be persisted"
        }
        require(verificationReport.requiredVerificationPassed && verificationReport.verificationFailures.isEmpty()) {
            "Mandatory verification has not passed"
        }
        require(verificationReport.canonicalRevision == outputBundle.canonicalRevision) {
            "Verification canonical revision mismatch"
        }
        require(verificationReport.artifactRevision == outputBundle.artifactRevision) {
            "Verification artifact revision mismatch"
        }
        require(outputBundle.artifacts.map(OutputArtifact::kind).toSet() == this.artifactPayloads.keys) {
            "Persistence payload kinds must exactly match the verified bundle"
        }
        outputBundle.artifacts.forEach { artifact ->
            require(this.artifactPayloads.getValue(artifact.kind).size.toLong() == artifact.byteCount.value) {
                "Persistence payload length differs from the verified manifest"
            }
        }
        PersistentTextPolicy.requireSafeSummary(assuranceRationaleSummary.value)
    }

    override fun toString(): String = "PersistVerifiedResultRequest(content=redacted)"
}

fun interface StorageClock {
    fun now(): WallClockInstant
}

object SystemStorageClock : StorageClock {
    override fun now(): WallClockInstant = WallClockInstant(System.currentTimeMillis().coerceAtLeast(0))
}

fun interface SavedResultIdGenerator {
    fun next(): SavedResultId
}

class SecureSavedResultIdGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
) : SavedResultIdGenerator {
    override fun next(): SavedResultId = SavedResultId("result-${secureRandom.randomHex(16)}")
}

fun interface StorageKeyAliasGenerator {
    fun next(): app.shareguard.core.security.KeyAlias
}

class SecureStorageKeyAliasGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
) : StorageKeyAliasGenerator {
    override fun next(): app.shareguard.core.security.KeyAlias =
        app.shareguard.core.security.KeyAlias("saved-result-${secureRandom.randomHex(16)}")
}

enum class PersistenceCheckpoint {
    AFTER_STAGED_FILE_SYNC,
    AFTER_STAGED_REOPEN,
    AFTER_FINAL_MOVE,
    AFTER_METADATA_COMMIT,
    AFTER_FINAL_REOPEN,
    BEFORE_VISIBILITY_COMMIT,
}

fun interface PersistenceFaultInjector {
    fun onCheckpoint(checkpoint: PersistenceCheckpoint)
}

object NoPersistenceFaults : PersistenceFaultInjector {
    override fun onCheckpoint(checkpoint: PersistenceCheckpoint) = Unit
}

enum class StorageFailureReason {
    INVALID_VERIFIED_INPUT,
    PAYLOAD_DIGEST_MISMATCH,
    PATH_OUTSIDE_APPROVED_ROOT,
    ENCRYPTION_FAILED,
    KEY_UNAVAILABLE,
    AUTHENTICATION_FAILED,
    STAGED_WRITE_FAILED,
    STAGED_REOPEN_FAILED,
    FINAL_MOVE_FAILED,
    METADATA_COMMIT_FAILED,
    FINAL_REOPEN_FAILED,
    VISIBILITY_COMMIT_FAILED,
    RECORD_NOT_SHAREABLE,
    RECORD_NOT_FOUND,
    ARTIFACT_REVISION_MISMATCH,
    CANONICAL_REVISION_MISMATCH,
    MIME_MISMATCH,
    MIGRATION_REVALIDATION_REQUIRED,
    VALIDATOR_REJECTED_ARTIFACT,
    PREVIEW_INVALID,
    SHARE_CACHE_PREPARATION_FAILED,
    DELETION_PARTIAL,
    UNEXPECTED_STORAGE_FAILURE,
}

class SavedResultStorageException(
    val reason: StorageFailureReason,
    cause: Throwable? = null,
) : java.io.IOException(reason.name, cause)

data class PersistedSavedResult(
    val savedResult: SavedResult,
    val durableWriteConfirmed: Boolean,
)

data class BulkDeletionResult(
    val results: List<SavedResultDeletionResult>,
) {
    val completed: Boolean get() = results.all { it.completed }
}

internal object PersistentTextPolicy {
    private val forbidden = listOf(
        Regex("(?i)(?:content|file)://"),
        Regex("(?i)https?://"),
        Regex("(?:^|[\\s])/(?:data|storage|sdcard|mnt|home|tmp)/"),
        Regex("(?i)before[_ -]?value"),
        Regex("(?i)destination[_ -]?(?:app|package|identity)"),
    )

    fun requireSafeSummary(value: String) {
        require(value.length <= 1_024 && forbidden.none { it.containsMatchIn(value) }) {
            "Persistent summaries must not contain source references, paths, or destinations"
        }
    }
}

private fun SecureRandom.randomHex(byteCount: Int): String {
    val bytes = ByteArray(byteCount).also(::nextBytes)
    return try {
        buildString(byteCount * 2) {
            bytes.forEach { byte -> append("%02x".format(byte.toInt() and 0xff)) }
        }
    } finally {
        bytes.fill(0)
    }
}
