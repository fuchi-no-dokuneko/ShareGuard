package app.shareguard.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ImportAnchor(
    val wallClock: WallClockInstant,
    val monotonic: MonotonicInstant?,
    val bootSessionReference: BootSessionReference?,
    val clockConfidence: ImportClockConfidence,
) {
    init {
        require((monotonic == null) == (bootSessionReference == null)) {
            "Monotonic import time and boot reference must be present together"
        }
        if (clockConfidence == ImportClockConfidence.MONOTONIC_ACTIVE) {
            require(monotonic != null) { "MONOTONIC_ACTIVE requires a monotonic anchor" }
        }
    }

    fun elapsedMillis(
        nowWallClock: WallClockInstant,
        nowMonotonic: MonotonicInstant? = null,
        currentBootSessionReference: BootSessionReference? = null,
    ): Long {
        val monotonicElapsed = if (
            monotonic != null &&
            nowMonotonic != null &&
            bootSessionReference == currentBootSessionReference &&
            nowMonotonic.elapsedRealtimeNanos >= monotonic.elapsedRealtimeNanos
        ) {
            (nowMonotonic.elapsedRealtimeNanos - monotonic.elapsedRealtimeNanos) / 1_000_000L
        } else {
            null
        }
        return monotonicElapsed ?: (nowWallClock.epochMillis - wallClock.epochMillis).coerceAtLeast(0)
    }
}

@Serializable
data class ArtifactManifestEntry(
    val kind: ArtifactKind,
    val reference: ArtifactReference,
    val mimeType: MimeType,
    val digest: ContentDigest,
    val artifactRevision: ArtifactRevision,
    val byteCount: ByteCount,
)

@Serializable
data class ArtifactManifest(
    val artifacts: ImmutableList<ArtifactManifestEntry>,
    val canonicalRevision: CanonicalRevision,
    val sourcePixelDependencySummary: SourceDependencySummary,
    val externalExportKnown: Boolean,
    val integrityState: IntegrityState,
) {
    init {
        require(artifacts.isNotEmpty()) { "Artifact manifest cannot be empty" }
        require(artifacts.map { it.kind }.distinct().size == artifacts.size) {
            "Artifact manifest cannot contain duplicate kinds"
        }
        require(artifacts.map { it.reference }.distinct().size == artifacts.size) {
            "Artifact references must be unique"
        }
        require(artifacts.map { it.artifactRevision }.distinct().size == 1) {
            "Manifest artifacts must share one artifact revision"
        }
    }

    val artifactRevision: ArtifactRevision
        get() = artifacts.first().artifactRevision

    val textArtifactReference: ArtifactReference?
        get() = artifacts.firstOrNull { it.kind == ArtifactKind.CANONICAL_TEXT }?.reference

    val imageArtifactReference: ArtifactReference?
        get() = artifacts.firstOrNull { it.kind == ArtifactKind.REBUILT_IMAGE }?.reference

    val derivativeArtifactReference: ArtifactReference?
        get() = artifacts.firstOrNull { it.kind == ArtifactKind.DERIVATIVE_IMAGE }?.reference

    fun validateOutputMode(outputMode: OutputMode) {
        val kinds = artifacts.map { it.kind }.toSet()
        val expected = when (outputMode) {
            OutputMode.TEXT -> setOf(ArtifactKind.CANONICAL_TEXT)
            OutputMode.REBUILT_IMAGE -> setOf(ArtifactKind.REBUILT_IMAGE)
            OutputMode.BOTH -> setOf(ArtifactKind.CANONICAL_TEXT, ArtifactKind.REBUILT_IMAGE)
            OutputMode.DERIVATIVE_IMAGE -> setOf(ArtifactKind.DERIVATIVE_IMAGE)
        }
        require(kinds == expected) { "Artifact manifest does not match output mode" }
    }

    companion object {
        fun fromBundle(
            bundle: OutputBundle,
            integrityState: IntegrityState = IntegrityState.VALID,
            externalExportKnown: Boolean = false,
        ): ArtifactManifest {
            val entries = bundle.artifacts.map {
                ArtifactManifestEntry(
                    kind = it.kind,
                    reference = it.reference,
                    mimeType = it.mimeType,
                    digest = it.digest,
                    artifactRevision = it.artifactRevision,
                    byteCount = it.byteCount,
                )
            }.toImmutableList()
            val dependencies = (bundle.imageArtifact?.sourceDependencyMap
                ?: bundle.derivativeArtifact?.sourceDependencyMap)
            val summary = SourceDependencySummary(
                retainedSourcePixelRegions = dependencies?.retainedRegionIds ?: ImmutableList.empty(),
                dependencyTypes = dependencies?.entries?.map { it.type }?.distinct()?.toImmutableList()
                    ?: ImmutableList.empty(),
            )
            return ArtifactManifest(
                artifacts = entries,
                canonicalRevision = bundle.canonicalRevision,
                sourcePixelDependencySummary = summary,
                externalExportKnown = externalExportKnown,
                integrityState = integrityState,
            )
        }
    }
}

@ConsistentCopyVisibility
@Serializable
data class SavedResult private constructor(
    val savedResultId: SavedResultId,
    val schemaVersion: SchemaVersion,
    val displayLabel: DisplayLabel,
    val outputMode: OutputMode,
    val canonicalRevision: CanonicalRevision,
    val artifactManifest: ArtifactManifest,
    val assuranceClass: AssuranceClass,
    val originalVerifiedAssurance: AssuranceClass,
    val assuranceRationaleSummary: SafeSummary,
    val verificationState: VerificationState,
    val verificationSummaryReference: VerificationSummaryReference?,
    val verificationSummary: VerificationSummary?,
    val importAnchor: ImportAnchor,
    val persistedAtWallClock: WallClockInstant,
    val lastIntegrityCheckAt: WallClockInstant?,
    val contentDigest: ContentDigest,
    val storageByteCount: ByteCount,
    val previewReference: PreviewReference?,
    val favourite: Boolean,
    val createdByAppBuild: AppBuildId,
    val migrationState: MigrationState,
    val lifecycleState: SavedResultLifecycleState,
) {
    init {
        require(artifactManifest.canonicalRevision == canonicalRevision) {
            "Saved Result canonical revision mismatch"
        }
        artifactManifest.validateOutputMode(outputMode)
        require(assuranceClass.isAtMost(originalVerifiedAssurance)) {
            "Saved Result assurance cannot exceed its originally verified class"
        }
        require(originalVerifiedAssurance != AssuranceClass.AS_0_UNVERIFIED) {
            "A normal Saved Result requires verified original assurance"
        }
        require(
            originalVerifiedAssurance != AssuranceClass.AS_1_REENCODED_DERIVATIVE ||
                outputMode == OutputMode.DERIVATIVE_IMAGE
        ) { "AS-1 is reserved for derivative output" }
        require(
            outputMode != OutputMode.DERIVATIVE_IMAGE ||
                originalVerifiedAssurance == AssuranceClass.AS_1_REENCODED_DERIVATIVE
        ) { "Derivative Saved Result must retain the derivative assurance ceiling" }
        if (verificationSummary != null) {
            require(verificationSummary.canonicalRevision == canonicalRevision) {
                "Verification summary canonical revision mismatch"
            }
            require(verificationSummary.artifactRevision == artifactManifest.artifactRevision) {
                "Verification summary artifact revision mismatch"
            }
            require(verificationSummary.assuranceClass == originalVerifiedAssurance) {
                "Persisted summary must record the originally verified assurance"
            }
        }
        if (lifecycleState == SavedResultLifecycleState.AVAILABLE) {
            require(artifactManifest.integrityState == IntegrityState.VALID) {
                "Available result requires a valid artifact manifest"
            }
            require(verificationState == VerificationState.VERIFIED) {
                "Available result must be verified"
            }
            require(migrationState == MigrationState.CURRENT) {
                "Available result must have current migration state"
            }
        }
        if (lifecycleState in setOf(
                SavedResultLifecycleState.QUARANTINED,
                SavedResultLifecycleState.DELETION_PENDING,
                SavedResultLifecycleState.DELETED,
                SavedResultLifecycleState.INVALIDATED,
            )
        ) {
            require(verificationState != VerificationState.VERIFIED || lifecycleState == SavedResultLifecycleState.DELETION_PENDING) {
                "Quarantined, deleted, or invalidated results cannot remain normally verified"
            }
        }
    }

    val canManagedShare: Boolean
        get() = lifecycleState == SavedResultLifecycleState.AVAILABLE &&
            verificationState == VerificationState.VERIFIED &&
            artifactManifest.integrityState == IntegrityState.VALID &&
            migrationState == MigrationState.CURRENT

    fun rename(displayLabel: DisplayLabel): SavedResult = copy(displayLabel = displayLabel)

    fun setFavourite(favourite: Boolean): SavedResult = copy(favourite = favourite)

    fun noteExternalExport(): SavedResult = copy(
        artifactManifest = artifactManifest.copy(externalExportKnown = true),
    )

    fun requireRevalidation(migrationState: MigrationState = this.migrationState): SavedResult = copy(
        artifactManifest = artifactManifest.copy(integrityState = IntegrityState.STALE),
        verificationState = VerificationState.REVALIDATION_REQUIRED,
        migrationState = migrationState,
        lifecycleState = SavedResultLifecycleState.QUARANTINED,
    )

    fun revalidated(
        assuranceClass: AssuranceClass,
        checkedAt: WallClockInstant,
    ): SavedResult {
        require(assuranceClass.isAtMost(originalVerifiedAssurance)) {
            "Revalidation cannot promote beyond original assurance"
        }
        require(assuranceClass != AssuranceClass.AS_0_UNVERIFIED) {
            "Failed revalidation must use revalidationFailed"
        }
        return copy(
            assuranceClass = assuranceClass,
            artifactManifest = artifactManifest.copy(integrityState = IntegrityState.VALID),
            verificationState = VerificationState.VERIFIED,
            lastIntegrityCheckAt = checkedAt,
            migrationState = MigrationState.CURRENT,
            lifecycleState = SavedResultLifecycleState.AVAILABLE,
        )
    }

    fun revalidationFailed(checkedAt: WallClockInstant): SavedResult = copy(
        assuranceClass = AssuranceClass.AS_0_UNVERIFIED,
        artifactManifest = artifactManifest.copy(integrityState = IntegrityState.INVALID),
        verificationState = VerificationState.FAILED,
        lastIntegrityCheckAt = checkedAt,
        lifecycleState = SavedResultLifecycleState.QUARANTINED,
    )

    fun markDeletionPending(): SavedResult = copy(
        verificationState = VerificationState.INVALIDATED,
        lifecycleState = SavedResultLifecycleState.DELETION_PENDING,
    )

    companion object {
        fun committed(
            savedResultId: SavedResultId,
            schemaVersion: SchemaVersion,
            displayLabel: DisplayLabel,
            outputMode: OutputMode,
            artifactManifest: ArtifactManifest,
            assuranceClass: AssuranceClass,
            assuranceRationaleSummary: SafeSummary,
            verificationSummaryReference: VerificationSummaryReference?,
            verificationSummary: VerificationSummary?,
            importAnchor: ImportAnchor,
            persistedAtWallClock: WallClockInstant,
            contentDigest: ContentDigest,
            previewReference: PreviewReference?,
            favourite: Boolean,
            createdByAppBuild: AppBuildId,
        ): SavedResult {
            require(assuranceClass != AssuranceClass.AS_0_UNVERIFIED) {
                "Unverified artifacts cannot become normal Saved Results"
            }
            require(artifactManifest.integrityState == IntegrityState.VALID) {
                "Committed Saved Result requires a valid reopened manifest"
            }
            return SavedResult(
                savedResultId = savedResultId,
                schemaVersion = schemaVersion,
                displayLabel = displayLabel,
                outputMode = outputMode,
                canonicalRevision = artifactManifest.canonicalRevision,
                artifactManifest = artifactManifest,
                assuranceClass = assuranceClass,
                originalVerifiedAssurance = assuranceClass,
                assuranceRationaleSummary = assuranceRationaleSummary,
                verificationState = VerificationState.VERIFIED,
                verificationSummaryReference = verificationSummaryReference,
                verificationSummary = verificationSummary,
                importAnchor = importAnchor,
                persistedAtWallClock = persistedAtWallClock,
                lastIntegrityCheckAt = persistedAtWallClock,
                contentDigest = contentDigest,
                storageByteCount = ByteCount(artifactManifest.artifacts.sumOf { it.byteCount.value }),
                previewReference = previewReference,
                favourite = favourite,
                createdByAppBuild = createdByAppBuild,
                migrationState = MigrationState.CURRENT,
                lifecycleState = SavedResultLifecycleState.AVAILABLE,
            )
        }
    }
}

@Serializable
data class SavedResultDeletionResult(
    val savedResultId: SavedResultId,
    val completed: Boolean,
    val remainingReferenceCategories: ImmutableList<String>,
) {
    init {
        require(!completed || remainingReferenceCategories.isEmpty()) {
            "Completed deletion cannot retain app-addressable reference categories"
        }
        require(remainingReferenceCategories.all { CONTENT_FREE_CATEGORY.matches(it) }) {
            "Deletion diagnostics must use content-free categories"
        }
    }
}

private val CONTENT_FREE_CATEGORY = Regex("[A-Z][A-Z0-9_]{1,63}")
