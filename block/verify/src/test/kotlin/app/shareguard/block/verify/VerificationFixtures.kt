package app.shareguard.block.verify

import app.shareguard.core.model.ArtifactId
import app.shareguard.core.model.ArtifactReference
import app.shareguard.core.model.ArtifactRevision
import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.BlockId
import app.shareguard.core.model.ByteCount
import app.shareguard.core.model.CanonicalBlockId
import app.shareguard.core.model.CanonicalDocument
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.ChangeEntry
import app.shareguard.core.model.ChangeId
import app.shareguard.core.model.ChangeLedger
import app.shareguard.core.model.ChangeLedgerId
import app.shareguard.core.model.ConfidenceClass
import app.shareguard.core.model.ContentDigest
import app.shareguard.core.model.DependencyId
import app.shareguard.core.model.DependencyOrigin
import app.shareguard.core.model.DependencyType
import app.shareguard.core.model.ExecutionContext
import app.shareguard.core.model.ExecutionLifecycleState
import app.shareguard.core.model.ExecutionRevision
import app.shareguard.core.model.Finding
import app.shareguard.core.model.ImageArtifact
import app.shareguard.core.model.ImageRegion
import app.shareguard.core.model.ImageRegionPolicy
import app.shareguard.core.model.ImmutableList
import app.shareguard.core.model.InputKind
import app.shareguard.core.model.LanguagePolicy
import app.shareguard.core.model.LayoutElement
import app.shareguard.core.model.LayoutKind
import app.shareguard.core.model.LayoutModel
import app.shareguard.core.model.MimeType
import app.shareguard.core.model.OutputBundle
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.ParagraphBlock
import app.shareguard.core.model.PixelDimension
import app.shareguard.core.model.PixelSize
import app.shareguard.core.model.ReadingOrder
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SchemaVersion
import app.shareguard.core.model.SemanticImpact
import app.shareguard.core.model.SemanticRole
import app.shareguard.core.model.SourceDependency
import app.shareguard.core.model.SourceDependencyMap
import app.shareguard.core.model.SourceHandle
import app.shareguard.core.model.TextArtifact
import app.shareguard.core.model.TextRun
import app.shareguard.core.model.UrlToken
import app.shareguard.core.model.UserDecision
import app.shareguard.core.model.WallClockInstant
import app.shareguard.core.model.WorkflowId
import app.shareguard.core.model.toImmutableList
import app.shareguard.core.pipeline.BuiltInPresets
import app.shareguard.core.pipeline.PipelinePreset
import java.security.MessageDigest

internal data class Fixture(
    val request: VerificationRequest,
    val providers: VerificationProviders,
    val bytesByReference: Map<ArtifactReference, ByteArray>,
)

internal object VerificationFixtures {
    val revision = CanonicalRevision(1)
    val artifactRevision = ArtifactRevision(1)
    val ledgerId = ChangeLedgerId("ledger-1")
    val blockId = CanonicalBlockId("block-1")

    fun text(
        artifactText: String = "Hello world",
        approvedText: String = artifactText,
        urlTokens: List<UrlToken> = emptyList(),
        findings: List<Finding> = emptyList(),
        decisions: List<UserDecision> = emptyList(),
        imageRegions: List<ImageRegion> = emptyList(),
        changes: List<ChangeEntry> = emptyList(),
        appliedTransformations: List<AppliedTransformation> = emptyList(),
        sourceDependencies: List<SourceDependency> = emptyList(),
        dependencyExpectations: Set<DependencyExpectation> = sourceDependencies.map(::expectation).toSet(),
        sourceCanaries: List<SourceCanary> = emptyList(),
        referenceSurfaces: List<ReferenceSurface> = emptyList(),
        policy: FinalVerificationPolicy = FinalVerificationPolicy(),
        reviewEvidence: ReviewEvidence? = null,
        presentedAssuranceClass: AssuranceClass? = AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT,
    ): Fixture {
        val preset = BuiltInPresets.textBalanced
        val dependencyMap = SourceDependencyMap.create(revision, sourceDependencies)
        val document = document(
            text = approvedText,
            urlTokens = urlTokens,
            decisions = decisions,
            regions = imageRegions,
            dependencies = dependencyMap,
        )
        val bytes = artifactText.toByteArray(Charsets.UTF_8)
        val artifact = TextArtifact(
            artifactId = ArtifactId("text-artifact"),
            reference = ArtifactReference("managed-text"),
            artifactRevision = artifactRevision,
            canonicalRevision = revision,
            mimeType = MimeType("text/plain"),
            digest = digest(bytes),
            byteCount = ByteCount(bytes.size.toLong()),
            canonicalText = artifactText,
        )
        val ledger = ChangeLedger.create(ledgerId, revision, changes)
        val context = ExecutionContext.create(
            sessionId = app.shareguard.core.model.SessionId("session-1"),
            workflowId = WorkflowId("workflow-1"),
            workflowVersion = preset.presetVersion,
            inputKind = InputKind.TEXT,
            requestedOutput = OutputMode.TEXT,
            sourceHandle = SourceHandle("source-handle"),
            assuranceCeiling = preset.assuranceCeiling,
        ).copy(
            executionRevision = ExecutionRevision(500),
            canonicalDocument = document,
            textArtifact = artifact,
            findings = findings.toImmutableList(),
            decisions = decisions.toImmutableList(),
            changes = changes.toImmutableList(),
            sourceDependencyMap = dependencyMap,
            lifecycleState = ExecutionLifecycleState.RUNNING,
        )
        val bundle = OutputBundle(OutputMode.TEXT, revision, textArtifact = artifact)
        val manifest = manifest(preset)
        val effectiveReview = reviewEvidence ?: ReviewEvidence.create(
            semanticDiffApproval = SemanticDiffApproval(revision, changes.map { it.changeId }.toSet()),
            assuranceConsequenceApproval = AssuranceConsequenceApproval(preset.assuranceCeiling, true),
        )
        val request = VerificationRequest.create(
            preset = preset,
            context = context,
            outputBundle = bundle,
            executedBlockManifest = manifest,
            changeLedger = ledger,
            approvedCanonicalText = approvedText,
            appliedTransformations = appliedTransformations,
            dependencyScope = DependencyVerificationScope(
                expectedEntries = dependencyExpectations,
                exercisedTypes = dependencyExpectations.map { it.type }.toSet(),
                platformLimitationCodes = setOf("PLATFORM_INTERNALS_NOT_ATTESTED"),
            ),
            sourceCanaries = sourceCanaries,
            referenceSurfaces = referenceSurfaces,
            reviewEvidence = effectiveReview,
            policy = policy,
            generatedAtSessionTime = WallClockInstant(1_000),
            presentedAssuranceClass = presentedAssuranceClass,
        )
        val reopened = ReopenedArtifact.create(
            artifactRevision,
            revision,
            artifact.mimeType,
            artifact.digest,
            appPrivateLocation = true,
            bytes = bytes,
        )
        return Fixture(
            request,
            passingProviders(mapOf(artifact.reference to reopened), approvedText, listOf(blockId)),
            mapOf(artifact.reference to bytes),
        )
    }

    fun image(
        approvedText: String = "Hello world",
        imageBytes: ByteArray = byteArrayOf(1, 2, 3, 4),
        imageRegions: List<ImageRegion> = emptyList(),
        decisions: List<UserDecision> = emptyList(),
        findings: List<Finding> = emptyList(),
        sourceDependencies: List<SourceDependency> = defaultImageDependencies(),
        dependencyExpectations: Set<DependencyExpectation> = sourceDependencies.map(::expectation).toSet(),
        reviewEvidence: ReviewEvidence? = null,
        policy: FinalVerificationPolicy = FinalVerificationPolicy(),
        presentedAssuranceClass: AssuranceClass? = AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE,
    ): Fixture {
        val preset = BuiltInPresets.imageFullRebuild
        val dependencyMap = SourceDependencyMap.create(revision, sourceDependencies)
        val document = document(
            text = approvedText,
            decisions = decisions,
            regions = imageRegions,
            dependencies = dependencyMap,
        )
        val artifact = ImageArtifact(
            artifactId = ArtifactId("image-artifact"),
            reference = ArtifactReference("managed-image"),
            artifactRevision = artifactRevision,
            canonicalRevision = revision,
            mimeType = MimeType("image/png"),
            digest = digest(imageBytes),
            byteCount = ByteCount(imageBytes.size.toLong()),
            pixelSize = PixelSize(PixelDimension(120), PixelDimension(80)),
            sourceDependencyMap = dependencyMap,
        )
        val ledger = ChangeLedger.create(ledgerId, revision)
        val context = ExecutionContext.create(
            sessionId = app.shareguard.core.model.SessionId("session-image"),
            workflowId = WorkflowId("workflow-image"),
            workflowVersion = preset.presetVersion,
            inputKind = InputKind.IMAGE,
            requestedOutput = OutputMode.REBUILT_IMAGE,
            sourceHandle = SourceHandle("source-image-handle"),
            assuranceCeiling = preset.assuranceCeiling,
        ).copy(
            executionRevision = ExecutionRevision(700),
            canonicalDocument = document,
            imageArtifact = artifact,
            findings = findings.toImmutableList(),
            decisions = decisions.toImmutableList(),
            sourceDependencyMap = dependencyMap,
            lifecycleState = ExecutionLifecycleState.RUNNING,
        )
        val bundle = OutputBundle(OutputMode.REBUILT_IMAGE, revision, imageArtifact = artifact)
        val effectiveReview = reviewEvidence ?: ReviewEvidence.create(
            approvedRegionPolicies = imageRegions.associate { it.regionId to it.policy },
            semanticDiffApproval = SemanticDiffApproval(revision, emptySet()),
            assuranceConsequenceApproval = AssuranceConsequenceApproval(preset.assuranceCeiling, true),
        )
        val request = VerificationRequest.create(
            preset = preset,
            context = context,
            outputBundle = bundle,
            executedBlockManifest = manifest(preset),
            changeLedger = ledger,
            approvedCanonicalText = approvedText,
            appliedTransformations = emptyList(),
            dependencyScope = DependencyVerificationScope(
                expectedEntries = dependencyExpectations,
                exercisedTypes = dependencyExpectations.map { it.type }.toSet(),
                platformLimitationCodes = setOf("PLATFORM_INTERNALS_NOT_ATTESTED"),
            ),
            reviewEvidence = effectiveReview,
            policy = policy,
            generatedAtSessionTime = WallClockInstant(2_000),
            presentedAssuranceClass = presentedAssuranceClass,
        )
        val reopened = ReopenedArtifact.create(
            artifactRevision,
            revision,
            artifact.mimeType,
            artifact.digest,
            appPrivateLocation = true,
            bytes = imageBytes,
        )
        val providers = passingProviders(
            mapOf(artifact.reference to reopened),
            approvedText,
            listOf(blockId),
            imageRegionPolicies = imageRegions.associate { it.regionId to it.policy },
            sourcePixelOperations = sourceDependencies.filter { it.sourcePixelRetained }
                .mapNotNull { it.imageRegionId }.toSet(),
        )
        return Fixture(request, providers, mapOf(artifact.reference to imageBytes.copyOf()))
    }

    fun passingProviders(
        reopened: Map<ArtifactReference, ReopenedArtifact>,
        approvedText: String,
        readingOrder: List<CanonicalBlockId>,
        imageRegionPolicies: Map<app.shareguard.core.model.ImageRegionId, ImageRegionPolicy> = emptyMap(),
        sourcePixelOperations: Set<app.shareguard.core.model.ImageRegionId> = emptySet(),
        permissions: Set<String> = emptySet(),
    ): VerificationProviders = VerificationProviders(
        artifactReopener = ArtifactReopener { artifact ->
            reopened[artifact.reference]?.let { ProviderResult.Completed(it) }
                ?: ProviderResult.Error("TEST_ARTIFACT_NOT_FOUND")
        },
        finalImageInspector = FinalImageInspector { artifact, _ ->
            ProviderResult.Completed(
                FinalImageInspection(
                    artifactRevision = artifact.artifactRevision,
                    detectedMimeType = MimeType("image/png"),
                    independentlyDecodes = true,
                    metadataFieldCodes = emptySet(),
                    containerChunkCodes = setOf("PNG_IHDR", "PNG_IDAT", "PNG_IEND"),
                    embeddedThumbnailCount = 0,
                    channelModelCode = "RGB_8",
                    alphaModelCode = "OPAQUE",
                    colourProfileCode = "SRGB_CANONICAL",
                    freshlyAllocatedCanvas = true,
                    bundledRendererAssetsOnly = true,
                ),
            )
        },
        ocrRoundTripInspector = OcrRoundTripInspector { artifact, _, _ ->
            ProviderResult.Completed(
                OcrRoundTripInspection.create(artifact.artifactRevision, approvedText, readingOrder),
            )
        },
        barcodeInspector = BarcodeInspector { artifact ->
            ProviderResult.Completed(BarcodeInspection.create(artifact.artifactRevision, emptyList()))
        },
        regionCoverageInspector = RegionCoverageInspector { artifact ->
            ProviderResult.Completed(
                RegionCoverageInspection(
                    artifact.artifactRevision,
                    imageRegionPolicies,
                    sourcePixelOperations,
                ),
            )
        },
        idempotenceInspector = IdempotenceInspector { text, canonicalRevision ->
            ProviderResult.Completed(IdempotenceInspection(canonicalRevision, text, 0))
        },
        runtimePrivacyInspector = RuntimePrivacyInspector {
            ProviderResult.Completed(
                RuntimePrivacyInspection(
                    networkEvidenceCaptured = true,
                    networkAttemptCount = 0,
                    onDemandModelDownloadCount = 0,
                    declaredPermissionNames = permissions,
                    broadStoragePermissionPresent = false,
                    appPrivateArtifactRoot = true,
                    cleanupCompleted = true,
                    outgoingMimeMatchesArtifact = true,
                    outgoingDigestMatchesArtifact = true,
                    outgoingContentUriAppScoped = true,
                    temporaryReadGrantLeastPrivilege = true,
                ),
            )
        },
        sensitiveLoggingInspector = SensitiveLoggingInspector {
            ProviderResult.Completed(
                SensitiveLoggingInspection(
                    staticScanCompleted = true,
                    dynamicCanarySessionCompleted = true,
                    inspectedEventCount = 20,
                    prohibitedPayloadMatchCount = 0,
                    persistentProductionTracingEnabled = false,
                ),
            )
        },
    )

    fun document(
        text: String,
        urlTokens: List<UrlToken> = emptyList(),
        decisions: List<UserDecision> = emptyList(),
        regions: List<ImageRegion> = emptyList(),
        dependencies: SourceDependencyMap = SourceDependencyMap.create(revision),
    ): CanonicalDocument {
        val run = TextRun.create(
            canonicalText = text,
            semanticRole = SemanticRole.BODY,
            confidenceClass = ConfidenceClass.CERTAIN_BY_PARSER,
            userLocked = true,
        )
        return CanonicalDocument(
            schemaVersion = SchemaVersion(1),
            revision = revision,
            declaredLanguagePolicy = LanguagePolicy.create(),
            rootBlocks = ImmutableList.of(ParagraphBlock(blockId, ImmutableList.of(run))),
            urlTokens = urlTokens.toImmutableList(),
            imageRegions = regions.toImmutableList(),
            layoutModel = LayoutModel(LayoutKind.PLAIN_DOCUMENT, ImmutableList.of(LayoutElement(blockId, null, 0))),
            readingOrder = ReadingOrder(ImmutableList.of(blockId)),
            userDecisions = decisions.toImmutableList(),
            sourceDependencyMap = dependencies,
            changeLedgerReference = ledgerId,
        )
    }

    fun defaultImageDependencies(): List<SourceDependency> = listOf(
        SourceDependency(
            dependencyId = DependencyId("dep-document"),
            type = DependencyType.CANONICAL_DOCUMENT_REVISION,
            origin = DependencyOrigin.CANONICAL_DOCUMENT,
            canonicalRevision = revision,
            reason = SafeSummary("CANONICAL_DOCUMENT_INPUT"),
        ),
        SourceDependency(
            dependencyId = DependencyId("dep-renderer"),
            type = DependencyType.RENDERER_GENERATED_PRIMITIVE,
            origin = DependencyOrigin.GENERATED,
            canonicalRevision = revision,
            reason = SafeSummary("FRESH_RENDERER_PRIMITIVE"),
        ),
    )

    fun manifest(preset: PipelinePreset): ImmutableList<app.shareguard.core.model.ExecutedBlockManifestEntry> {
        val persistIndex = preset.blockReferences.indexOfFirst { it.blockId == BlockId("PST-002") }
        val references = if (persistIndex < 0) preset.blockReferences else preset.blockReferences.take(persistIndex)
        return references.mapIndexed { index, reference ->
            app.shareguard.core.model.ExecutedBlockManifestEntry(
                reference.blockId,
                reference.blockVersion,
                ExecutionRevision((index + 1).toLong()),
                index,
            )
        }.toImmutableList()
    }

    fun digest(bytes: ByteArray): ContentDigest = ContentDigest(
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) },
    )

    private fun expectation(dependency: SourceDependency): DependencyExpectation = DependencyExpectation(
        type = dependency.type,
        canonicalBlockId = dependency.canonicalBlockId,
        imageRegionId = dependency.imageRegionId,
        decisionIdValue = dependency.decisionId?.value,
    )
}
