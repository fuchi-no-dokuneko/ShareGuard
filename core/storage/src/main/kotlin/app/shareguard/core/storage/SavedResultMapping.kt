package app.shareguard.core.storage

import app.shareguard.core.model.AppBuildId
import app.shareguard.core.model.ArtifactKind
import app.shareguard.core.model.ArtifactManifest
import app.shareguard.core.model.ArtifactManifestEntry
import app.shareguard.core.model.ArtifactReference
import app.shareguard.core.model.ArtifactRevision
import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.BootSessionReference
import app.shareguard.core.model.ByteCount
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.ContentDigest
import app.shareguard.core.model.DependencyType
import app.shareguard.core.model.DisplayLabel
import app.shareguard.core.model.ImageRegionId
import app.shareguard.core.model.ImmutableList
import app.shareguard.core.model.ImportAnchor
import app.shareguard.core.model.ImportClockConfidence
import app.shareguard.core.model.IntegrityState
import app.shareguard.core.model.MigrationState
import app.shareguard.core.model.MimeType
import app.shareguard.core.model.MonotonicInstant
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.PreviewReference
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SavedResult
import app.shareguard.core.model.SavedResultId
import app.shareguard.core.model.SavedResultLifecycleState
import app.shareguard.core.model.SchemaVersion
import app.shareguard.core.model.SourceDependencySummary
import app.shareguard.core.model.VerificationState
import app.shareguard.core.model.VerificationStatus
import app.shareguard.core.model.VerificationStatusSummary
import app.shareguard.core.model.VerificationSummary
import app.shareguard.core.model.VerificationSummaryReference
import app.shareguard.core.model.VerificationType
import app.shareguard.core.model.WallClockInstant

internal fun SavedResultWithArtifacts.toModel(): SavedResult {
    val entity = result
    val integrity = IntegrityState.valueOf(entity.integrityState)
    val manifest = ArtifactManifest(
        artifacts = ImmutableList.copyOf(artifacts.sortedBy { it.artifactKind }.map { artifact ->
            ArtifactManifestEntry(
                kind = ArtifactKind.valueOf(artifact.artifactKind),
                reference = ArtifactReference(artifact.artifactReference),
                mimeType = MimeType(artifact.mimeType),
                digest = ContentDigest(artifact.contentDigest),
                artifactRevision = ArtifactRevision(artifact.artifactRevision),
                byteCount = ByteCount(artifact.byteCount),
            )
        }),
        canonicalRevision = CanonicalRevision(entity.canonicalRevision),
        sourcePixelDependencySummary = SourceDependencySummary(
            retainedSourcePixelRegions = ImmutableList.copyOf(
                artifacts.flatMap { it.retainedRegionIds.decodeTokens() }
                    .distinct()
                    .map(::ImageRegionId),
            ),
            dependencyTypes = ImmutableList.copyOf(
                artifacts.flatMap { it.dependencyTypes.decodeTokens() }
                    .distinct()
                    .map(DependencyType::valueOf),
            ),
        ),
        externalExportKnown = entity.externalExportKnown,
        integrityState = integrity,
    )
    val originalAssurance = AssuranceClass.valueOf(entity.originalVerifiedAssurance)
    val checkedAt = WallClockInstant(
        entity.lastIntegrityCheckAtMillis ?: entity.persistedAtWallClockMillis,
    )
    var model = SavedResult.committed(
        savedResultId = SavedResultId(entity.savedResultId),
        schemaVersion = SchemaVersion(entity.schemaVersion),
        displayLabel = DisplayLabel(entity.displayLabel),
        outputMode = OutputMode.valueOf(entity.outputMode),
        artifactManifest = manifest.copy(integrityState = IntegrityState.VALID),
        assuranceClass = originalAssurance,
        assuranceRationaleSummary = SafeSummary(entity.assuranceRationaleSummary),
        verificationSummaryReference = entity.verificationSummaryReference?.let(::VerificationSummaryReference),
        verificationSummary = entity.toVerificationSummary(originalAssurance),
        importAnchor = ImportAnchor(
            wallClock = WallClockInstant(entity.importWallClockMillis),
            monotonic = entity.importMonotonicNanos?.let(::MonotonicInstant),
            bootSessionReference = entity.bootSessionReference?.let(::BootSessionReference),
            clockConfidence = ImportClockConfidence.valueOf(entity.importClockConfidence),
        ),
        persistedAtWallClock = WallClockInstant(entity.persistedAtWallClockMillis),
        contentDigest = ContentDigest(entity.contentDigest),
        previewReference = entity.previewReference?.let(::PreviewReference),
        favourite = entity.favourite,
        createdByAppBuild = AppBuildId(entity.createdByAppBuild),
    )
    if (entity.externalExportKnown) model = model.noteExternalExport()
    return when (SavedResultLifecycleState.valueOf(entity.lifecycleState)) {
        SavedResultLifecycleState.AVAILABLE -> {
            val current = AssuranceClass.valueOf(entity.assuranceClass)
            if (current == originalAssurance) model else model.revalidated(current, checkedAt)
        }
        SavedResultLifecycleState.DELETION_PENDING,
        SavedResultLifecycleState.DELETED,
        -> model.markDeletionPending()
        SavedResultLifecycleState.QUARANTINED,
        SavedResultLifecycleState.INVALIDATED,
        SavedResultLifecycleState.COMMITTING,
        -> if (VerificationState.valueOf(entity.verificationState) == VerificationState.FAILED ||
            integrity == IntegrityState.INVALID
        ) {
            model.revalidationFailed(checkedAt)
        } else {
            model.requireRevalidation(MigrationState.valueOf(entity.migrationState))
        }
    }
}

internal fun buildCommittingEntities(
    request: PersistVerifiedResultRequest,
    savedResultId: SavedResultId,
    keyAlias: String,
    finalArtifacts: List<FinalArtifact>,
    contentDigest: ContentDigest,
    persistedAt: WallClockInstant,
): Pair<SavedResultEntity, List<SavedArtifactEntity>> {
    val summary = request.verificationReport.compactSummary()
    val dependencySummary = ArtifactManifest.fromBundle(request.outputBundle).sourcePixelDependencySummary
    val suffix = savedResultId.value.removePrefix("result-")
    val result = SavedResultEntity(
        savedResultId = savedResultId.value,
        schemaVersion = CURRENT_SAVED_RESULT_SCHEMA,
        displayLabel = request.displayLabel.value,
        outputMode = request.outputBundle.outputMode.name,
        canonicalRevision = request.outputBundle.canonicalRevision.value,
        assuranceClass = request.verificationReport.assuranceClass.name,
        originalVerifiedAssurance = request.verificationReport.assuranceClass.name,
        assuranceRationaleSummary = request.assuranceRationaleSummary.value,
        verificationState = VerificationState.PENDING.name,
        verificationSummaryReference = "verification-$suffix",
        verificationReportVersion = summary.reportVersion.value,
        verificationArtifactRevision = summary.artifactRevision.value,
        verificationStatuses = summary.resultStatuses.joinToString(TOKEN_SEPARATOR) {
            "${it.type.name}$KEY_VALUE_SEPARATOR${it.status.name}"
        },
        unresolvedFindingCount = summary.unresolvedFindingCount,
        retainedSourceRegionCount = summary.retainedSourceRegionCount,
        importWallClockMillis = request.importAnchor.wallClock.epochMillis,
        importMonotonicNanos = request.importAnchor.monotonic?.elapsedRealtimeNanos,
        bootSessionReference = request.importAnchor.bootSessionReference?.value,
        importClockConfidence = request.importAnchor.clockConfidence.name,
        persistedAtWallClockMillis = persistedAt.epochMillis,
        lastIntegrityCheckAtMillis = null,
        contentDigest = contentDigest.sha256,
        storageByteCount = request.outputBundle.artifacts.sumOf { it.byteCount.value },
        previewReference = null,
        favourite = false,
        createdByAppBuild = request.createdByAppBuild.value,
        migrationState = MigrationState.CURRENT.name,
        lifecycleState = SavedResultLifecycleState.COMMITTING.name,
        integrityState = IntegrityState.PENDING.name,
        externalExportKnown = false,
        keyAlias = keyAlias,
        visibilityConfirmed = false,
        revalidationReasonCode = null,
    )
    val artifactEntities = finalArtifacts.map { final ->
        val binding = final.binding
        SavedArtifactEntity(
            savedResultId = savedResultId.value,
            artifactKind = binding.kind.name,
            artifactReference = "saved-$suffix-${binding.kind.storageName}",
            relativePath = final.relativePath,
            mimeType = binding.mimeType.value,
            contentDigest = binding.contentDigest.sha256,
            artifactRevision = binding.artifactRevision,
            byteCount = binding.byteCount,
            retainedRegionIds = dependencySummary.retainedSourcePixelRegions.joinToString(TOKEN_SEPARATOR) { it.value },
            dependencyTypes = dependencySummary.dependencyTypes.joinToString(TOKEN_SEPARATOR) { it.name },
        )
    }
    return result to artifactEntities
}

private fun SavedResultEntity.toVerificationSummary(
    originalAssurance: AssuranceClass,
): VerificationSummary? {
    val reportVersion = verificationReportVersion ?: return null
    val revision = verificationArtifactRevision ?: return null
    val statuses = verificationStatuses?.decodeTokens().orEmpty().map { encoded ->
        val parts = encoded.split(KEY_VALUE_SEPARATOR, limit = 2)
        require(parts.size == 2) { "Invalid persisted verification summary" }
        VerificationStatusSummary(
            VerificationType.valueOf(parts[0]),
            VerificationStatus.valueOf(parts[1]),
        )
    }
    return VerificationSummary(
        reportVersion = SchemaVersion(reportVersion),
        artifactRevision = ArtifactRevision(revision),
        canonicalRevision = CanonicalRevision(canonicalRevision),
        assuranceClass = originalAssurance,
        assuranceRationale = SafeSummary(assuranceRationaleSummary),
        resultStatuses = ImmutableList.copyOf(statuses),
        unresolvedFindingCount = unresolvedFindingCount ?: 0,
        retainedSourceRegionCount = retainedSourceRegionCount ?: 0,
    )
}

private fun String.decodeTokens(): List<String> =
    if (isBlank()) emptyList() else split(TOKEN_SEPARATOR).filter(String::isNotBlank)

private const val CURRENT_SAVED_RESULT_SCHEMA = 1
private const val TOKEN_SEPARATOR = ";"
private const val KEY_VALUE_SEPARATOR = "="
