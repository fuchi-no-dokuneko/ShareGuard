package app.shareguard.canonical

import android.content.Context
import app.shareguard.block.image.BlockDependencyUse
import app.shareguard.block.image.ImageDependencyDeclaration
import app.shareguard.block.image.ImageSourceDependencyMapper
import app.shareguard.block.image.RegionRetentionUse
import app.shareguard.block.image.RendererPrimitiveUse
import app.shareguard.block.render.DerivativeAcknowledgement
import app.shareguard.block.render.DerivativeImageRenderer
import app.shareguard.block.render.DerivativePolicy
import app.shareguard.block.render.DerivativeRenderRequest
import app.shareguard.block.render.DerivativeResourcePlan
import app.shareguard.block.render.RebuiltArtifactIdentity
import app.shareguard.block.render.RenderedArtifactFactory
import app.shareguard.block.verify.AppliedTransformation
import app.shareguard.block.verify.AssuranceConsequenceApproval
import app.shareguard.block.verify.DependencyExpectation
import app.shareguard.block.verify.DependencyVerificationScope
import app.shareguard.block.verify.FinalVerificationCoordinator
import app.shareguard.block.verify.FinalVerificationCatalog
import app.shareguard.block.verify.FinalVerificationOutcome
import app.shareguard.block.verify.FinalVerificationPolicy
import app.shareguard.block.verify.ReferenceSurface
import app.shareguard.block.verify.ReferenceSurfaceKind
import app.shareguard.block.verify.ReviewEvidence
import app.shareguard.block.verify.SemanticDiffApproval
import app.shareguard.block.verify.SourceCanary
import app.shareguard.block.verify.SourceCanaryKind
import app.shareguard.block.verify.VerificationProviders
import app.shareguard.block.verify.VerificationRequest
import app.shareguard.core.model.AppBuildId
import app.shareguard.core.model.ArtifactId
import app.shareguard.core.model.ArtifactKind
import app.shareguard.core.model.ArtifactReference
import app.shareguard.core.model.ArtifactRevision
import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.BlockId
import app.shareguard.core.model.BlockPhase
import app.shareguard.core.model.CanonicalBlockId
import app.shareguard.core.model.CanonicalDocument
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.ChangeEntry
import app.shareguard.core.model.ChangeId
import app.shareguard.core.model.ChangeLedger
import app.shareguard.core.model.ChangeLedgerId
import app.shareguard.core.model.ConfidenceClass
import app.shareguard.core.model.DecisionAction
import app.shareguard.core.model.DecisionId
import app.shareguard.core.model.DecisionStatus
import app.shareguard.core.model.DisplayLabel
import app.shareguard.core.model.ExecutedBlockManifestEntry
import app.shareguard.core.model.ExecutionContext
import app.shareguard.core.model.ExecutionLifecycleState
import app.shareguard.core.model.ExecutionRevision
import app.shareguard.core.model.Finding
import app.shareguard.core.model.FindingCategory
import app.shareguard.core.model.FindingId
import app.shareguard.core.model.FindingStatus
import app.shareguard.core.model.ImageRegion
import app.shareguard.core.model.ImageRegionId
import app.shareguard.core.model.ImageRegionPolicy
import app.shareguard.core.model.ImageRegionType
import app.shareguard.core.model.ImportAnchor
import app.shareguard.core.model.InputKind
import app.shareguard.core.model.LanguagePolicy
import app.shareguard.core.model.LayoutElement
import app.shareguard.core.model.LayoutKind
import app.shareguard.core.model.LayoutModel
import app.shareguard.core.model.NormalizedRect
import app.shareguard.core.model.OutputBundle
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.ReadingOrder
import app.shareguard.core.model.ReviewLink
import app.shareguard.core.model.ReviewStatus
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SchemaVersion
import app.shareguard.core.model.SemanticImpact
import app.shareguard.core.model.SemanticRisk
import app.shareguard.core.model.SessionId
import app.shareguard.core.model.Severity
import app.shareguard.core.model.SourceHandle
import app.shareguard.core.model.TraceOutcome
import app.shareguard.core.model.TransformationCategory
import app.shareguard.core.model.UnknownRegionBlock
import app.shareguard.core.model.UserDecision
import app.shareguard.core.model.WallClockInstant
import app.shareguard.core.model.WorkflowId
import app.shareguard.core.model.VerificationStatus
import app.shareguard.core.model.toImmutableList
import app.shareguard.core.pipeline.BuiltInPresets
import app.shareguard.core.pipeline.NormativeBlockCatalog
import app.shareguard.core.security.SecretBytes
import app.shareguard.core.security.Sha256ContentDigester
import app.shareguard.core.session.ManagedSession
import app.shareguard.core.session.SessionDiagnosticReason
import app.shareguard.core.storage.PersistVerifiedResultRequest
import app.shareguard.core.storage.PersistedSavedResult
import app.shareguard.core.storage.SavedResultRepository
import java.security.SecureRandom

data class DerivativeWorkflowCompletion(
    val verification: FinalVerificationOutcome,
    val persisted: PersistedSavedResult?,
    val changeLedger: ChangeLedger,
    val exactImagePreviewBytes: ByteArray,
)

/** Experimental derivative path. It always declares retained source pixels and can never exceed AS-1. */
class DerivativeImageWorkflow(
    private val context: Context,
    private val repository: SavedResultRepository,
    private val cleanupEvidence: suspend () -> Boolean,
    private val renderer: DerivativeImageRenderer = DerivativeImageRenderer(),
    private val verifier: FinalVerificationCoordinator = FinalVerificationCoordinator(),
) {
    private val digester = Sha256ContentDigester()
    private val random = SecureRandom()

    suspend fun verifyAndPersist(
        session: ManagedSession,
        sourceHandle: SourceHandle,
        importAnchor: ImportAnchor,
        source: LocalDerivativeSource,
        displayLabel: DisplayLabel,
        warningAcknowledged: Boolean,
    ): DerivativeWorkflowCompletion {
        require(warningAcknowledged) { "DERIVATIVE_WARNING_NOT_ACKNOWLEDGED" }
        val revision = CanonicalRevision(1)
        val regionId = ImageRegionId("source-image-whole")
        val blockId = CanonicalBlockId("source-image-block")
        val findingId = FindingId("finding-derivative-retained-pixels")
        val decisionId = DecisionId("decision-derivative-warning-v1")
        val finding = Finding(
            findingId = findingId,
            blockId = BlockId("DER-006"),
            category = FindingCategory.IMAGE_REGION,
            severity = Severity.HIGH,
            confidenceClass = ConfidenceClass.CERTAIN_BY_PARSER,
            sourceLocation = null,
            canonicalLocation = null,
            title = SafeSummary("DERIVATIVE_RETAINS_SOURCE_PIXEL_RELATIONSHIP"),
            explanation = SafeSummary(
                "Source pixels remain semantically and statistically related; unknown robust watermark signals may remain",
            ),
            suggestedAction = DecisionAction.ACCEPT_LOWER_ASSURANCE,
            semanticRisk = SemanticRisk.HIGH_IMPACT,
            requiresUserDecision = true,
            status = FindingStatus.ACCEPTED,
            evidenceSummary = SafeSummary("Derivative export is explicitly limited to AS-1"),
        )
        val decision = UserDecision.create(
            decisionId = decisionId,
            findingIds = listOf(findingId),
            action = DecisionAction.ACCEPT_LOWER_ASSURANCE,
            status = DecisionStatus.APPROVED,
            semanticImpact = SemanticImpact.POSSIBLE,
            rationale = SafeSummary("User acknowledged the versioned derivative warning for this export"),
            canonicalRevision = revision,
        )
        val region = ImageRegion(
            regionId = regionId,
            regionType = ImageRegionType.UNKNOWN,
            sourceBounds = NormalizedRect(0f, 0f, 1f, 1f),
            canonicalBounds = NormalizedRect(0f, 0f, 1f, 1f),
            policy = ImageRegionPolicy.RETAIN_SOURCE_PIXELS,
            sourcePixelRetained = true,
            replacementAssetId = null,
            userApproved = true,
            dependencyReason = SafeSummary("Whole orientation-applied source raster retained by experimental derivative"),
        )
        val dependencyMap = ImageSourceDependencyMapper().build(
            ImageDependencyDeclaration(
                canonicalRevision = revision,
                retainedPixelRegions = listOf(RegionRetentionUse(regionId, decisionId)),
                sourceDerivedLayout = listOf(BlockDependencyUse(blockId)),
                rendererPrimitives = listOf(
                    RendererPrimitiveUse(SafeSummary("derivative-resample-channel-canonicalization-v1"), blockId),
                ),
                userDecisions = listOf(decisionId),
            ),
        ).dependencyMap
        val ledgerId = ChangeLedgerId("ledger-${randomToken()}")
        val ledgerEntries = listOf(
            transformation(
                "DER-001", "change-derivative-resample", "SOURCE_RASTER_RESAMPLED_TO_FRESH_GRID",
                revision, decisionId, findingId,
            ),
            transformation(
                "DER-002", "change-derivative-channels", "CHANNELS_CANONICALIZED_AND_ALPHA_FLATTENED",
                revision, decisionId, findingId,
            ),
            transformation(
                "DER-005", "change-derivative-serialize", "FRESH_STRICT_PNG_SERIALIZED",
                revision, decisionId, findingId,
            ),
        )
        val ledger = ChangeLedger.create(ledgerId, revision, ledgerEntries)
        val document = CanonicalDocument(
            schemaVersion = SchemaVersion(1),
            revision = revision,
            declaredLanguagePolicy = LanguagePolicy.create(),
            rootBlocks = listOf(
                UnknownRegionBlock(
                    id = blockId,
                    regionId = regionId,
                    description = SafeSummary("Approved whole-image derivative source region"),
                ),
            ).toImmutableList(),
            urlTokens = emptyList<app.shareguard.core.model.UrlToken>().toImmutableList(),
            imageRegions = listOf(region).toImmutableList(),
            layoutModel = LayoutModel(
                kind = LayoutKind.MANUAL,
                elements = listOf(LayoutElement(blockId, NormalizedRect(0f, 0f, 1f, 1f), 0)).toImmutableList(),
            ),
            readingOrder = ReadingOrder(listOf(blockId).toImmutableList()),
            userDecisions = listOf(decision).toImmutableList(),
            sourceDependencyMap = dependencyMap,
            changeLedgerReference = ledgerId,
        )
        val bitmap = source.orientationAppliedBitmap
        val pixelCount = Math.multiplyExact(bitmap.width.toLong(), bitmap.height.toLong())
        val rendered = renderer.render(
            DerivativeRenderRequest(
                orientationAppliedSource = bitmap,
                canonicalRevision = revision,
                sourceDependencyMap = dependencyMap,
                policy = DerivativePolicy(
                    resourcePlan = DerivativeResourcePlan(bitmap.width, bitmap.height, pixelCount),
                    quantizationBitsPerChannel = null,
                    stochasticPerturbationAmplitude = 0,
                    warningVersion = WARNING_VERSION,
                ),
                acknowledgement = DerivativeAcknowledgement(WARNING_VERSION, true),
            ),
        )
        check(rendered.sourceDependencyMap == document.sourceDependencyMap) {
            "DERIVATIVE_DEPENDENCY_MAP_CHANGED_AFTER_APPROVAL"
        }
        val identity = randomToken()
        val artifact = RenderedArtifactFactory(digester).derivativeImage(
            rendered,
            RebuiltArtifactIdentity(
                artifactId = ArtifactId("derivative-$identity"),
                reference = ArtifactReference("derivative-ref-$identity"),
                artifactRevision = ArtifactRevision(1),
            ),
            revision,
        )
        val bundle = OutputBundle(
            outputMode = OutputMode.DERIVATIVE_IMAGE,
            canonicalRevision = revision,
            derivativeArtifact = artifact,
        )
        val preset = BuiltInPresets.imageDerivative
        val manifestReferences = preset.blockReferences.takeWhile { it.blockId.value != "PST-002" }
        val manifest = manifestReferences.mapIndexed { index, reference ->
            ExecutedBlockManifestEntry(
                blockId = reference.blockId,
                blockVersion = reference.blockVersion,
                executionRevision = ExecutionRevision((index + 1).toLong()),
                order = index,
            )
        }.toImmutableList()
        val executionContext = ExecutionContext.create(
            sessionId = SessionId(session.sessionId.value),
            workflowId = WorkflowId(preset.presetId.lowercase()),
            workflowVersion = preset.presetVersion,
            inputKind = InputKind.IMAGE,
            requestedOutput = OutputMode.DERIVATIVE_IMAGE,
            sourceHandle = sourceHandle,
            importAnchor = importAnchor,
            assuranceCeiling = AssuranceClass.AS_1_REENCODED_DERIVATIVE,
        ).copy(
            executionRevision = ExecutionRevision((manifest.size + 1).toLong()),
            canonicalDocument = document,
            derivativeArtifact = artifact,
            findings = listOf(finding).toImmutableList(),
            decisions = listOf(decision).toImmutableList(),
            changes = ledger.entries,
            sourceDependencyMap = dependencyMap,
            lifecycleState = ExecutionLifecycleState.RUNNING,
        )
        val transformations = ledger.entries.groupBy { it.blockId }.map { (id, entries) ->
            AppliedTransformation(
                blockId = id,
                blockVersion = NormativeBlockCatalog.registry.require(id).blockVersion,
                canonicalRevision = revision,
                changeIds = entries.map { it.changeId }.toSet(),
            )
        }
        val staging = TransientArtifactStaging.create(context, session)
        val encoded = rendered.copyBytes()
        try {
            staging.stage(artifact, encoded)
            val request = VerificationRequest.create(
                preset = preset,
                context = executionContext,
                outputBundle = bundle,
                executedBlockManifest = manifest,
                changeLedger = ledger,
                approvedCanonicalText = null,
                appliedTransformations = transformations,
                dependencyScope = DependencyVerificationScope(
                    expectedEntries = dependencyMap.entries.map {
                        DependencyExpectation(it.type, it.canonicalBlockId, it.imageRegionId, it.decisionId?.value)
                    }.toSet(),
                    exercisedTypes = dependencyMap.entries.map { it.type }.toSet(),
                    platformLimitationCodes = source.diagnosticLimitationCodes +
                        "PLATFORM_LIBRARY_INTERNALS_NOT_ENUMERATED",
                ),
                sourceCanaries = listOf(
                    SourceCanary.utf8(SourceCanaryKind.SESSION_REFERENCE, session.sessionId.value),
                    SourceCanary.utf8(SourceCanaryKind.STABLE_SEED_REFERENCE, sourceHandle.value),
                ),
                referenceSurfaces = listOf(
                    ReferenceSurface.utf8(ReferenceSurfaceKind.OUTPUT_FILENAME, "canonical-share-derivative.png"),
                    ReferenceSurface.utf8(ReferenceSurfaceKind.PERSISTABLE_SUMMARY, "DERIVATIVE_RESULT_VERIFICATION"),
                ),
                reviewEvidence = ReviewEvidence.create(
                    approvedRegionPolicies = mapOf(regionId to ImageRegionPolicy.RETAIN_SOURCE_PIXELS),
                    semanticDiffApproval = SemanticDiffApproval(revision, ledger.entries.map { it.changeId }.toSet()),
                    assuranceConsequenceApproval = AssuranceConsequenceApproval(
                        AssuranceClass.AS_1_REENCODED_DERIVATIVE,
                        true,
                    ),
                ),
                policy = FinalVerificationPolicy(requireReleaseControls = true, requirePersistentReopen = true),
                generatedAtSessionTime = WallClockInstant(System.currentTimeMillis().coerceAtLeast(0)),
            )
            val cleanupCompleted = cleanupEvidence()
            val outcome = verifier.verify(
                request,
                VerificationProviders(
                    artifactReopener = staging,
                    finalImageInspector = StrictRenderedImageInspector(rendered.operations),
                    barcodeInspector = ExactPngBarcodeInspector(),
                    regionCoverageInspector = ExactRenderedRegionInspector(
                        mapOf(regionId to ImageRegionPolicy.RETAIN_SOURCE_PIXELS),
                        rendered.operations,
                    ),
                    runtimePrivacyInspector = AppRuntimePrivacyInspector(context, cleanupCompleted),
                    sensitiveLoggingInspector = AppSensitiveLoggingInspector(),
                ),
            )
            recordContentFreeTrace(session, manifest, transformations, revision, outcome)
            val persisted = if (outcome.canPersistVerifiedResult) {
                val payload = SecretBytes.copyOf(encoded)
                try {
                    repository.persistVerifiedResult(
                        PersistVerifiedResultRequest(
                            outputBundle = bundle,
                            verificationReport = outcome.report,
                            importAnchor = importAnchor,
                            displayLabel = displayLabel,
                            assuranceRationaleSummary = outcome.report.assuranceRationale,
                            createdByAppBuild = AppBuildId("build-${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}"),
                            artifactPayloads = mapOf(ArtifactKind.DERIVATIVE_IMAGE to payload),
                        ),
                    )
                } finally {
                    payload.close()
                }
            } else {
                null
            }
            return DerivativeWorkflowCompletion(outcome, persisted, ledger, encoded.copyOf())
        } finally {
            encoded.fill(0)
            staging.close()
        }
    }

    private fun transformation(
        blockId: String,
        changeId: String,
        reason: String,
        revision: CanonicalRevision,
        decisionId: DecisionId,
        findingId: FindingId,
    ): ChangeEntry {
        val id = BlockId(blockId)
        return ChangeEntry(
            changeId = ChangeId(changeId),
            blockId = id,
            blockVersion = NormativeBlockCatalog.registry.require(id).blockVersion,
            canonicalRevision = revision,
            category = FindingCategory.IMAGE_REGION,
            sourceLocation = null,
            beforeRepresentation = null,
            afterRepresentation = null,
            reason = SafeSummary(reason),
            reversibleBeforeExport = true,
            semanticImpact = SemanticImpact.POSSIBLE,
            reviewLink = ReviewLink(
                decisionId = decisionId,
                findingIds = listOf(findingId).toImmutableList(),
                status = ReviewStatus.APPROVED,
            ),
            verificationId = null,
        )
    }

    private fun recordContentFreeTrace(
        session: ManagedSession,
        manifest: List<ExecutedBlockManifestEntry>,
        transformations: List<AppliedTransformation>,
        revision: CanonicalRevision,
        verification: FinalVerificationOutcome,
    ) {
        val verificationOutcomes = verification.report.results.mapNotNull { result ->
            FinalVerificationCatalog.require(result.type).normativeBlockId?.let { blockId ->
                blockId to when (result.status) {
                    VerificationStatus.PASS,
                    VerificationStatus.PASS_WITH_DECLARED_RESIDUAL,
                    VerificationStatus.NOT_APPLICABLE,
                    -> TraceOutcome.SUCCESS
                    VerificationStatus.REVIEW_REQUIRED -> TraceOutcome.REVIEW_REQUIRED
                    VerificationStatus.NOT_RUN,
                    VerificationStatus.FAIL,
                    VerificationStatus.ERROR,
                    -> TraceOutcome.RECOVERABLE_FAILURE
                }
            }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, outcomes) ->
            when {
                TraceOutcome.RECOVERABLE_FAILURE in outcomes -> TraceOutcome.RECOVERABLE_FAILURE
                TraceOutcome.REVIEW_REQUIRED in outcomes -> TraceOutcome.REVIEW_REQUIRED
                else -> TraceOutcome.SUCCESS
            }
        }
        manifest.forEach { entry ->
            val outcome = verificationOutcomes[entry.blockId.value] ?: when (entry.blockId.value) {
                "IMG-007", "IMG-008" -> TraceOutcome.RECOVERABLE_FAILURE
                else -> TraceOutcome.SUCCESS
            }
            BlockPhase.entries.forEach { phase ->
                session.lifecycle.diagnosticTrace.record(
                    blockId = entry.blockId,
                    blockVersion = entry.blockVersion,
                    phase = phase,
                    executionRevision = entry.executionRevision,
                    canonicalRevision = revision,
                    outcome = outcome,
                    reason = SessionDiagnosticReason.BLOCK_PHASE_COMPLETE,
                )
            }
        }
        transformations.forEachIndexed { index, transformation ->
            session.lifecycle.diagnosticTrace.record(
                blockId = transformation.blockId,
                blockVersion = transformation.blockVersion,
                transformationCategory = TransformationCategory.DERIVATIVE,
                executionRevision = ExecutionRevision((manifest.size + index + 1).toLong()),
                canonicalRevision = revision,
                outcome = TraceOutcome.SUCCESS,
                reason = SessionDiagnosticReason.TRANSFORMATION_APPLIED,
            )
        }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(12).also(random::nextBytes)
        return try {
            bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } finally {
            bytes.fill(0)
        }
    }

    private companion object {
        const val WARNING_VERSION = "derivative-warning-v1"
    }
}
