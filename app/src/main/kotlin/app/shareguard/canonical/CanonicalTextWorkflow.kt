package app.shareguard.canonical

import android.content.Context
import app.shareguard.block.image.BlockDependencyUse
import app.shareguard.block.image.ImageDependencyDeclaration
import app.shareguard.block.image.ImageSourceDependencyMapper
import app.shareguard.block.text.CanonicalTextSerializer
import app.shareguard.block.ocr.OcrScript
import app.shareguard.block.render.CanonicalImageRenderer
import app.shareguard.block.render.CanonicalRenderPolicy
import app.shareguard.block.render.CanonicalRenderRequest
import app.shareguard.block.render.CanonicalRenderTheme
import app.shareguard.block.render.EncodedRenderedImage
import app.shareguard.block.render.RebuiltArtifactIdentity
import app.shareguard.block.render.RenderOperationCode
import app.shareguard.block.render.RenderResourcePlan
import app.shareguard.block.render.RenderedArtifactFactory
import app.shareguard.block.render.defaultBundledFontRegistry
import app.shareguard.block.text.TextCanonicalizationResult
import app.shareguard.block.text.TextCanonicalizer
import app.shareguard.block.text.TextProcessingInput
import app.shareguard.block.text.TextReviewApprovals
import app.shareguard.block.text.TextReviewCode
import app.shareguard.block.text.TextSourceKind
import app.shareguard.block.url.UrlProcessingInput
import app.shareguard.block.url.UrlProcessingResult
import app.shareguard.block.url.UrlProcessingService
import app.shareguard.block.url.UrlReviewApprovals
import app.shareguard.block.url.UrlReviewCode
import app.shareguard.block.url.UrlSourceKind
import app.shareguard.block.verify.AppliedTransformation
import app.shareguard.block.verify.AssuranceConsequenceApproval
import app.shareguard.block.verify.DependencyExpectation
import app.shareguard.block.verify.DependencyVerificationScope
import app.shareguard.block.verify.FinalVerificationCoordinator
import app.shareguard.block.verify.FinalVerificationCatalog
import app.shareguard.block.verify.FinalVerificationOutcome
import app.shareguard.block.verify.FinalVerificationPolicy
import app.shareguard.block.verify.IdempotenceInspection
import app.shareguard.block.verify.IdempotenceInspector
import app.shareguard.block.verify.ProviderResult
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
import app.shareguard.core.model.BlockVersion
import app.shareguard.core.model.BlockId
import app.shareguard.core.model.BlockPhase
import app.shareguard.core.model.ByteCount
import app.shareguard.core.model.CanonicalBlockId
import app.shareguard.core.model.CanonicalDocument
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.ChangeLedger
import app.shareguard.core.model.ChangeEntry
import app.shareguard.core.model.ChangeId
import app.shareguard.core.model.ChangeLedgerId
import app.shareguard.core.model.ConfidenceClass
import app.shareguard.core.model.DependencyId
import app.shareguard.core.model.DecisionId
import app.shareguard.core.model.DecisionAction
import app.shareguard.core.model.DecisionStatus
import app.shareguard.core.model.DependencyOrigin
import app.shareguard.core.model.DependencyType
import app.shareguard.core.model.DisplayLabel
import app.shareguard.core.model.ExecutedBlockManifestEntry
import app.shareguard.core.model.ExecutionContext
import app.shareguard.core.model.ExecutionLifecycleState
import app.shareguard.core.model.ExecutionRevision
import app.shareguard.core.model.Finding
import app.shareguard.core.model.FindingCategory
import app.shareguard.core.model.FindingId
import app.shareguard.core.model.FindingStatus
import app.shareguard.core.model.ImportAnchor
import app.shareguard.core.model.InputKind
import app.shareguard.core.model.ImageArtifact
import app.shareguard.core.model.ImageRegion
import app.shareguard.core.model.ImageRegionId
import app.shareguard.core.model.ImageRegionPolicy
import app.shareguard.core.model.ImageRegionType
import app.shareguard.core.model.LayoutElement
import app.shareguard.core.model.LayoutKind
import app.shareguard.core.model.LayoutModel
import app.shareguard.core.model.MimeType
import app.shareguard.core.model.NormalizedRect
import app.shareguard.core.model.OutputBundle
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.ParagraphBlock
import app.shareguard.core.model.ReadingOrder
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SensitiveRepresentation
import app.shareguard.core.model.SemanticImpact
import app.shareguard.core.model.SemanticRisk
import app.shareguard.core.model.Severity
import app.shareguard.core.model.SchemaVersion
import app.shareguard.core.model.ScriptCode
import app.shareguard.core.model.SemanticRole
import app.shareguard.core.model.SessionId
import app.shareguard.core.model.SourceDependency
import app.shareguard.core.model.SourceDependencyMap
import app.shareguard.core.model.SourceHandle
import app.shareguard.core.model.ReviewLink
import app.shareguard.core.model.ReviewStatus
import app.shareguard.core.model.TextArtifact
import app.shareguard.core.model.TextRun
import app.shareguard.core.model.TraceOutcome
import app.shareguard.core.model.TransformationCategory
import app.shareguard.core.model.WallClockInstant
import app.shareguard.core.model.WorkflowId
import app.shareguard.core.model.VerificationStatus
import app.shareguard.core.model.UserDecision
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

class TextReviewPlan internal constructor(
    val sourceText: String,
    val inputKind: InputKind,
    val textResult: TextCanonicalizationResult,
    val urlResult: UrlProcessingResult,
    val ocrReviewFinding: Finding?,
    val imageExclusionFinding: Finding?,
) {
    val textReviewCodes: Set<TextReviewCode> = textResult.reviewGates.filter { it.blocking }.map { it.code }.toSet()
    val urlReviewCodes: Set<UrlReviewCode> = urlResult.analysisBatch.reviewGates.filter { it.blocking }.map { it.code }.toSet()
    val requiresReview: Boolean = ocrReviewFinding != null || imageExclusionFinding != null ||
        textReviewCodes.isNotEmpty() || urlReviewCodes.isNotEmpty() || textResult.failures.isNotEmpty() ||
        urlResult.analysisBatch.failures.isNotEmpty()

    override fun toString(): String = "TextReviewPlan(content=redacted,reviewRequired=$requiresReview)"
}

class ApprovedTextPlan internal constructor(
    val sourceText: String,
    val inputKind: InputKind,
    val textResult: TextCanonicalizationResult,
    val urlResult: UrlProcessingResult,
    val canonicalText: String,
    val supplementalFindings: List<Finding>,
    val supplementalDecisions: List<UserDecision>,
    val supplementalLedgerEntries: List<ChangeEntry>,
) {
    override fun toString(): String = "ApprovedTextPlan(content=redacted)"
}

data class TextWorkflowCompletion(
    val verification: FinalVerificationOutcome,
    val persisted: PersistedSavedResult?,
    val canonicalText: String,
    val changeLedger: ChangeLedger,
    val exactImagePreviewBytes: ByteArray? = null,
)

class CanonicalTextWorkflow(
    private val context: Context,
    private val repository: SavedResultRepository,
    private val cleanupEvidence: suspend () -> Boolean,
    private val textCanonicalizer: TextCanonicalizer = TextCanonicalizer(),
    private val urlService: UrlProcessingService = UrlProcessingService(),
    private val verifier: FinalVerificationCoordinator = FinalVerificationCoordinator(),
) {
    private val digester = Sha256ContentDigester()
    private val random = SecureRandom()

    fun inspect(text: String, inputKind: InputKind = InputKind.TEXT): TextReviewPlan {
        require(text.isNotEmpty())
        val revision = CanonicalRevision(1)
        val textResult = textCanonicalizer.canonicalize(
            TextProcessingInput.create(
                text,
                if (inputKind == InputKind.IMAGE) TextSourceKind.OCR_APPROVED_TEXT else TextSourceKind.PLAIN_TEXT,
            ),
            revision,
            idPrefix = "text",
        )
        val urlResult = urlService.process(
            UrlProcessingInput.create(
                textResult.canonicalText,
                if (inputKind == InputKind.IMAGE) UrlSourceKind.OCR_TEXT else UrlSourceKind.PLAIN_TEXT,
            ),
            revision,
            idPrefix = "url",
        )
        return TextReviewPlan(
            text,
            inputKind,
            textResult,
            urlResult,
            ocrReviewFinding(inputKind),
            imageExclusionFinding(inputKind),
        )
    }

    fun approve(plan: TextReviewPlan, allReviewItemsApproved: Boolean): ApprovedTextPlan {
        require(allReviewItemsApproved || !plan.requiresReview) { "REQUIRED_REVIEW_NOT_APPROVED" }
        val revision = CanonicalRevision(1)
        val textApproved = textCanonicalizer.canonicalize(
            TextProcessingInput.create(
                plan.sourceText,
                if (plan.inputKind == InputKind.IMAGE) TextSourceKind.OCR_APPROVED_TEXT else TextSourceKind.PLAIN_TEXT,
            ),
            revision,
            approvals = TextReviewApprovals.create(approvedCodes = plan.textReviewCodes),
            idPrefix = "text",
        )
        require(!textApproved.requiresReview && textApproved.failures.isEmpty()) { "TEXT_REVIEW_REMAINS_UNRESOLVED" }
        val urlDraft = urlService.process(
            UrlProcessingInput.create(
                textApproved.canonicalText,
                if (plan.inputKind == InputKind.IMAGE) UrlSourceKind.OCR_TEXT else UrlSourceKind.PLAIN_TEXT,
            ),
            revision,
            idPrefix = "url",
        )
        val urlCodes = urlDraft.analysisBatch.reviewGates.filter { it.blocking }.map { it.code }.toSet()
        val approvedHosts = urlDraft.analysisBatch.analyses.mapNotNull { analysis ->
            analysis.parsed?.let { it.candidate.tokenId to it.parsedComponents.host }
        }.toMap()
        val urlApproved = urlService.process(
            UrlProcessingInput.create(
                textApproved.canonicalText,
                if (plan.inputKind == InputKind.IMAGE) UrlSourceKind.OCR_TEXT else UrlSourceKind.PLAIN_TEXT,
            ),
            revision,
            approvals = UrlReviewApprovals.create(urlCodes, approvedHosts),
            idPrefix = "url",
        )
        val finalText = requireNotNull(urlApproved.canonicalText) { "URL_REVIEW_REMAINS_UNRESOLVED" }
        val ocrEvidence = plan.ocrReviewFinding?.let { finding ->
            val revisionBlock = BlockId("REV-003")
            val decisionId = DecisionId("decision-ocr-transcription")
            val decision = UserDecision.create(
                decisionId = decisionId,
                findingIds = listOf(finding.findingId),
                action = DecisionAction.LOCK_EXACT_WORDING,
                status = DecisionStatus.APPROVED,
                semanticImpact = SemanticImpact.POSSIBLE,
                rationale = SafeSummary("User reviewed the provisional local OCR transcription"),
                canonicalRevision = revision,
            )
            val change = ChangeEntry(
                changeId = ChangeId("change-ocr-transcription"),
                blockId = revisionBlock,
                blockVersion = NormativeBlockCatalog.registry.require(revisionBlock).blockVersion,
                canonicalRevision = revision,
                category = FindingCategory.SEMANTIC,
                sourceLocation = null,
                beforeRepresentation = null,
                afterRepresentation = SensitiveRepresentation(finalText),
                reason = SafeSummary("OCR_TRANSCRIPTION_USER_APPROVED"),
                reversibleBeforeExport = true,
                semanticImpact = SemanticImpact.POSSIBLE,
                reviewLink = ReviewLink(
                    decisionId,
                    listOf(finding.findingId).toImmutableList(),
                    ReviewStatus.APPROVED,
                ),
                verificationId = null,
            )
            Triple(listOf(finding.copy(status = FindingStatus.ACCEPTED)), listOf(decision), listOf(change))
        }
        val imageExclusionEvidence = plan.imageExclusionFinding?.let { finding ->
            val revisionBlock = BlockId("REV-006")
            val decisionId = DecisionId("decision-image-source-exclusion")
            val decision = UserDecision.create(
                decisionId = decisionId,
                findingIds = listOf(finding.findingId),
                action = DecisionAction.EXCLUDE_REGION,
                status = DecisionStatus.APPROVED,
                semanticImpact = SemanticImpact.POSSIBLE,
                rationale = SafeSummary("User approved removing the complete original image raster from canonical outputs"),
                canonicalRevision = revision,
            )
            val change = ChangeEntry(
                changeId = ChangeId("change-image-source-exclusion"),
                blockId = revisionBlock,
                blockVersion = NormativeBlockCatalog.registry.require(revisionBlock).blockVersion,
                canonicalRevision = revision,
                category = FindingCategory.IMAGE_REGION,
                sourceLocation = null,
                beforeRepresentation = null,
                afterRepresentation = null,
                reason = SafeSummary("WHOLE_SOURCE_PIXEL_REGION_EXCLUDED"),
                reversibleBeforeExport = true,
                semanticImpact = SemanticImpact.POSSIBLE,
                reviewLink = ReviewLink(
                    decisionId,
                    listOf(finding.findingId).toImmutableList(),
                    ReviewStatus.APPROVED,
                ),
                verificationId = null,
            )
            Triple(listOf(finding.copy(status = FindingStatus.ACCEPTED)), listOf(decision), listOf(change))
        }
        return ApprovedTextPlan(
            plan.sourceText,
            plan.inputKind,
            textApproved,
            urlApproved,
            finalText,
            ocrEvidence?.first.orEmpty() + imageExclusionEvidence?.first.orEmpty(),
            ocrEvidence?.second.orEmpty() + imageExclusionEvidence?.second.orEmpty(),
            ocrEvidence?.third.orEmpty() + imageExclusionEvidence?.third.orEmpty(),
        )
    }

    suspend fun verifyAndPersist(
        session: ManagedSession,
        sourceHandle: SourceHandle,
        importAnchor: ImportAnchor,
        plan: ApprovedTextPlan,
        displayLabel: DisplayLabel,
        semanticDiffApproved: Boolean,
        assuranceConsequenceApproved: Boolean,
        inputKind: InputKind = InputKind.TEXT,
        outputMode: OutputMode = OutputMode.TEXT,
    ): TextWorkflowCompletion {
        require(semanticDiffApproved) { "SEMANTIC_DIFF_NOT_APPROVED" }
        require(assuranceConsequenceApproved) { "ASSURANCE_CONSEQUENCE_NOT_APPROVED" }
        require(plan.inputKind == inputKind) { "APPROVED_PLAN_INPUT_KIND_MISMATCH" }
        require(outputMode != OutputMode.DERIVATIVE_IMAGE) { "DERIVATIVE_OUTPUT_REQUIRES_SOURCE_REGION_REVIEW" }
        val revision = CanonicalRevision(1)
        val textApproved = plan.textResult
        val urlApproved = plan.urlResult
        val finalText = plan.canonicalText
        val urlTokens = urlApproved.canonicalizations.mapNotNull { result ->
            result.urlToken?.let { token ->
                val changed = token.displayText != token.finalText || token.parsedComponents != token.normalizedComponents
                if (changed && semanticDiffApproved) token.copy(userApproved = true) else token
            }
        }
        val findings = (textApproved.findings + urlApproved.analysisBatch.findings + plan.supplementalFindings).toImmutableList()
        val decisions = (textApproved.decisions + urlApproved.canonicalizations.flatMap { it.decisions } +
            plan.supplementalDecisions).toImmutableList()
        val baseLedgerEntries = (textApproved.ledgerEntries + urlApproved.canonicalizations.flatMap { it.ledgerEntries } +
            plan.supplementalLedgerEntries)
            .toImmutableList()
        val ledgerId = ChangeLedgerId("ledger-${randomToken()}")
        val blockId = CanonicalBlockId("content-1")
        val dependencyMap = if (inputKind == InputKind.IMAGE) {
            ImageSourceDependencyMapper().build(
                ImageDependencyDeclaration(
                    canonicalRevision = revision,
                    ocrDerivedText = listOf(BlockDependencyUse(blockId)),
                    userDecisions = decisions.map { it.decisionId },
                ),
            ).dependencyMap
        } else {
            SourceDependencyMap.create(
                revision,
                listOf(
                    SourceDependency(
                        dependencyId = DependencyId("canonical-input-1"),
                        type = DependencyType.CANONICAL_DOCUMENT_REVISION,
                        origin = DependencyOrigin.CANONICAL_DOCUMENT,
                        canonicalRevision = revision,
                        canonicalBlockId = blockId,
                        reason = SafeSummary("Reviewed canonical text revision"),
                    ),
                ),
            )
        }
        val run = TextRun.create(
            canonicalText = finalText,
            scriptSet = textApproved.inspection.scalarInventory.map { it.script }.distinct(),
            semanticRole = SemanticRole.BODY,
            confidenceClass = ConfidenceClass.CERTAIN_BY_PARSER,
            userLocked = true,
        )
        val draftDocument = CanonicalDocument(
            schemaVersion = SchemaVersion(1),
            revision = revision,
            declaredLanguagePolicy = textApproved.inspection.languageResolution.policy,
            rootBlocks = listOf(ParagraphBlock(blockId, listOf(run).toImmutableList())).toImmutableList(),
            urlTokens = urlTokens.toImmutableList(),
            imageRegions = if (inputKind == InputKind.IMAGE) {
                listOf(
                    ImageRegion(
                        regionId = ImageRegionId("source-image-whole"),
                        regionType = ImageRegionType.UNKNOWN,
                        sourceBounds = NormalizedRect(0f, 0f, 1f, 1f),
                        canonicalBounds = null,
                        policy = ImageRegionPolicy.REMOVE,
                        sourcePixelRetained = false,
                        replacementAssetId = null,
                        userApproved = true,
                        dependencyReason = SafeSummary("User approved excluding the complete source raster"),
                    ),
                ).toImmutableList()
            } else {
                emptyList<ImageRegion>().toImmutableList()
            },
            layoutModel = LayoutModel(
                LayoutKind.PLAIN_DOCUMENT,
                listOf(LayoutElement(blockId, null, 0)).toImmutableList(),
            ),
            readingOrder = ReadingOrder(listOf(blockId).toImmutableList()),
            userDecisions = decisions,
            sourceDependencyMap = dependencyMap,
            changeLedgerReference = ledgerId,
        )
        val serializedText = CanonicalTextSerializer().serialize(draftDocument, revision).text
        check(serializedText == finalText) { "CANONICAL_TEXT_SERIALIZATION_MISMATCH" }
        val textBytes = if (outputMode in setOf(OutputMode.TEXT, OutputMode.BOTH)) {
            serializedText.encodeToByteArray()
        } else {
            null
        }
        val artifactIdentity = randomToken()
        val artifact = textBytes?.let { bytes ->
            TextArtifact(
                artifactId = ArtifactId("text-$artifactIdentity"),
                reference = ArtifactReference("text-ref-$artifactIdentity"),
                artifactRevision = ArtifactRevision(1),
                canonicalRevision = revision,
                mimeType = MimeType("text/plain"),
                digest = digester.digest(bytes),
                byteCount = ByteCount(bytes.size.toLong()),
                canonicalText = serializedText,
            )
        }
        val rendered: EncodedRenderedImage? = if (outputMode in setOf(OutputMode.REBUILT_IMAGE, OutputMode.BOTH)) {
            CanonicalImageRenderer(defaultBundledFontRegistry(context)).render(
                CanonicalRenderRequest(
                    document = draftDocument,
                    policy = CanonicalRenderPolicy(
                        theme = CanonicalRenderTheme.DOCUMENT,
                        resourcePlan = RenderResourcePlan(
                            outputWidthPx = 1080,
                            maximumOutputHeightPx = 12_000,
                            maximumPixelCount = 12_960_000L,
                            horizontalPaddingPx = 72,
                            verticalPaddingPx = 72,
                            blockSpacingPx = 32,
                            bodyTextSizePx = 42f,
                        ),
                        backgroundColor = 0xffffffff.toInt(),
                        foregroundColor = 0xff111111.toInt(),
                        secondarySurfaceColor = 0xfff1f3f4.toInt(),
                        redactionColor = 0xff202124.toInt(),
                    ),
                ),
            )
        } else {
            null
        }
        val document = if (rendered == null) {
            draftDocument
        } else {
            draftDocument.copy(sourceDependencyMap = rendered.sourceDependencyMap)
        }
        val ledgerEntries = (
            baseLedgerEntries + outputTransformationEntries(outputMode, rendered, revision)
        ).toImmutableList()
        val ledger = ChangeLedger(ledgerId, revision, ledgerEntries)
        val imageArtifact: ImageArtifact? = rendered?.let { encoded ->
            RenderedArtifactFactory(digester).rebuiltImage(
                encoded = encoded,
                identity = RebuiltArtifactIdentity(
                    ArtifactId("image-$artifactIdentity"),
                    ArtifactReference("image-ref-$artifactIdentity"),
                    ArtifactRevision(1),
                ),
                canonicalRevision = revision,
            )
        }
        val bundle = OutputBundle(
            outputMode = outputMode,
            canonicalRevision = revision,
            textArtifact = artifact,
            imageArtifact = imageArtifact,
        )
        val preset = preset(inputKind, outputMode)
        val manifestReferences = preset.blockReferences.takeWhile { it.blockId.value != "PST-002" }
        val manifest = manifestReferences.mapIndexed { index, reference ->
            ExecutedBlockManifestEntry(
                reference.blockId,
                reference.blockVersion,
                ExecutionRevision((index + 1).toLong()),
                index,
            )
        }.toImmutableList()
        val executionContext = ExecutionContext.create(
            sessionId = SessionId(session.sessionId.value),
            workflowId = WorkflowId(preset.presetId.lowercase()),
            workflowVersion = preset.presetVersion,
            inputKind = inputKind,
            requestedOutput = outputMode,
            sourceHandle = sourceHandle,
            importAnchor = importAnchor,
            assuranceCeiling = preset.assuranceCeiling,
        ).copy(
            executionRevision = ExecutionRevision((manifest.size + 1).toLong()),
            canonicalDocument = document,
            textArtifact = artifact,
            imageArtifact = imageArtifact,
            findings = findings,
            decisions = decisions,
            changes = ledgerEntries,
            sourceDependencyMap = document.sourceDependencyMap,
            lifecycleState = ExecutionLifecycleState.RUNNING,
        )
        val transformations = ledgerEntries.groupBy { it.blockId }.map { (id, entries) ->
            AppliedTransformation(
                blockId = id,
                blockVersion = NormativeBlockCatalog.registry.require(id).blockVersion,
                canonicalRevision = revision,
                changeIds = entries.map { it.changeId }.toSet(),
            )
        }
        val staging = TransientArtifactStaging.create(context, session)
        val imageBytes = rendered?.copyBytes()
        try {
            artifact?.let { staging.stage(it, requireNotNull(textBytes)) }
            if (imageArtifact != null && imageBytes != null) staging.stage(imageArtifact, imageBytes)
            val approvedFinalUnicodeCodes = textCanonicalizer.canonicalize(
                TextProcessingInput.create(finalText, TextSourceKind.PLAIN_TEXT),
                revision,
                approvals = TextReviewApprovals.create(TextReviewCode.entries.toSet()),
                idPrefix = "final-scan",
            ).inspection.findings.map { it.title.value }.toSet()
            val request = VerificationRequest.create(
                preset = preset,
                context = executionContext,
                outputBundle = bundle,
                executedBlockManifest = manifest,
                changeLedger = ledger,
                approvedCanonicalText = finalText,
                appliedTransformations = transformations,
                dependencyScope = DependencyVerificationScope(
                    expectedEntries = document.sourceDependencyMap.entries.map {
                        DependencyExpectation(it.type, it.canonicalBlockId, it.imageRegionId, it.decisionId?.value)
                    }.toSet(),
                    exercisedTypes = document.sourceDependencyMap.entries.map { it.type }.toSet(),
                    platformLimitationCodes = setOf("PLATFORM_LIBRARY_INTERNALS_NOT_ENUMERATED"),
                ),
                sourceCanaries = listOf(
                    SourceCanary.utf8(SourceCanaryKind.SESSION_REFERENCE, session.sessionId.value),
                    SourceCanary.utf8(SourceCanaryKind.STABLE_SEED_REFERENCE, sourceHandle.value),
                ),
                referenceSurfaces = listOf(
                    ReferenceSurface.utf8(
                        ReferenceSurfaceKind.OUTPUT_FILENAME,
                        if (outputMode == OutputMode.TEXT) "canonical-share-result.txt" else "canonical-share-result.png",
                    ),
                    ReferenceSurface.utf8(ReferenceSurfaceKind.PERSISTABLE_SUMMARY, "CANONICAL_RESULT_VERIFICATION"),
                ),
                reviewEvidence = ReviewEvidence.create(
                    approvedRegionPolicies = document.imageRegions.takeIf { it.isNotEmpty() }
                        ?.associate { it.regionId to it.policy },
                    semanticDiffApproval = SemanticDiffApproval(revision, ledgerEntries.map { it.changeId }.toSet()),
                    assuranceConsequenceApproval = AssuranceConsequenceApproval(
                        preset.assuranceCeiling,
                        assuranceConsequenceApproved,
                    ),
                ),
                policy = FinalVerificationPolicy(
                    requireReleaseControls = true,
                    requirePersistentReopen = true,
                    approvedUnicodeFindingCodes = approvedFinalUnicodeCodes,
                ),
                generatedAtSessionTime = WallClockInstant(System.currentTimeMillis().coerceAtLeast(0)),
            )
            val cleanupCompleted = cleanupEvidence()
            val outcome = verifier.verify(
                request,
                VerificationProviders(
                    artifactReopener = staging,
                    finalImageInspector = rendered?.let { StrictRenderedImageInspector(it.operations) },
                    ocrRoundTripInspector = rendered?.let {
                        ExactPngOcrInspector(ocrScripts(textApproved.inspection.scalarInventory.map { scalar -> scalar.script }.toSet()))
                    },
                    barcodeInspector = rendered?.let { ExactPngBarcodeInspector() },
                    regionCoverageInspector = rendered?.let {
                        ExactRenderedRegionInspector(
                            document.imageRegions.associate { region -> region.regionId to region.policy },
                            it.operations,
                        )
                    },
                    idempotenceInspector = idempotenceInspector(),
                    runtimePrivacyInspector = AppRuntimePrivacyInspector(context, cleanupCompleted),
                    sensitiveLoggingInspector = AppSensitiveLoggingInspector(),
                ),
            )
            recordContentFreeTrace(session, manifest, transformations, revision, outcome)
            val persisted = if (outcome.canPersistVerifiedResult) {
                val payloads = buildMap {
                    if (bundle.textArtifact != null) {
                        put(ArtifactKind.CANONICAL_TEXT, SecretBytes.copyOf(requireNotNull(textBytes)))
                    }
                    if (imageArtifact != null && imageBytes != null) {
                        put(ArtifactKind.REBUILT_IMAGE, SecretBytes.copyOf(imageBytes))
                    }
                }
                try {
                    repository.persistVerifiedResult(
                        PersistVerifiedResultRequest(
                            outputBundle = bundle,
                            verificationReport = outcome.report,
                            importAnchor = importAnchor,
                            displayLabel = displayLabel,
                            assuranceRationaleSummary = outcome.report.assuranceRationale,
                            createdByAppBuild = AppBuildId("build-${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}"),
                            artifactPayloads = payloads,
                        ),
                    )
                } finally {
                    payloads.values.forEach(SecretBytes::close)
                }
            } else {
                null
            }
            return TextWorkflowCompletion(outcome, persisted, finalText, ledger, imageBytes?.copyOf())
        } finally {
            textBytes?.fill(0)
            imageBytes?.fill(0)
            staging.close()
        }
    }

    private fun outputTransformationEntries(
        outputMode: OutputMode,
        rendered: EncodedRenderedImage?,
        revision: CanonicalRevision,
    ): List<ChangeEntry> = buildList {
        if (outputMode in setOf(OutputMode.TEXT, OutputMode.BOTH)) {
            add(outputTransformation("OUT-TXT-001", "change-output-text-serialize", "CANONICAL_TEXT_SERIALIZED", revision))
        }
        rendered?.operations?.distinctBy { it.code }?.forEach { operation ->
            val blockId = when (operation.code) {
                RenderOperationCode.FRESH_CANVAS_ALLOCATED -> "REN-001"
                RenderOperationCode.BUNDLED_FONT_RESOLVED -> "REN-002"
                RenderOperationCode.TEXT_SHAPED -> "REN-003"
                RenderOperationCode.GENERIC_PRIMITIVE_RENDERED -> "REN-004"
                RenderOperationCode.STRUCTURED_REGION_RENDERED -> "REN-005"
                RenderOperationCode.PLACEHOLDER_RENDERED,
                RenderOperationCode.REDACTION_RENDERED,
                -> "REN-006"
                RenderOperationCode.APPROVED_SOURCE_REGION_IMPORTED -> "REN-007"
                RenderOperationCode.ALPHA_FLATTENED -> "REN-008"
                RenderOperationCode.CANONICAL_COLOUR_APPLIED -> "REN-009"
                RenderOperationCode.PNG_SERIALIZED -> "REN-010"
                RenderOperationCode.PNG_REOPENED -> "REN-011"
                RenderOperationCode.DERIVATIVE_RESAMPLED,
                RenderOperationCode.DERIVATIVE_CHANNELS_CANONICALIZED,
                RenderOperationCode.DERIVATIVE_QUANTIZED,
                RenderOperationCode.EPHEMERAL_PERTURBATION_APPLIED,
                RenderOperationCode.DERIVATIVE_WARNING_ACKNOWLEDGED,
                -> error("DERIVATIVE_OPERATION_IN_CANONICAL_RENDER")
            }
            add(
                outputTransformation(
                    blockId = blockId,
                    changeId = "change-render-${operation.code.name.lowercase()}",
                    reason = operation.code.name,
                    revision = revision,
                ),
            )
        }
    }

    private fun outputTransformation(
        blockId: String,
        changeId: String,
        reason: String,
        revision: CanonicalRevision,
    ): ChangeEntry {
        val id = BlockId(blockId)
        return ChangeEntry(
            changeId = ChangeId(changeId),
            blockId = id,
            blockVersion = NormativeBlockCatalog.registry.require(id).blockVersion,
            canonicalRevision = revision,
            category = if (blockId.startsWith("OUT-")) FindingCategory.FILE_CONTAINER else FindingCategory.LAYOUT,
            sourceLocation = null,
            beforeRepresentation = null,
            afterRepresentation = null,
            reason = SafeSummary(reason),
            reversibleBeforeExport = true,
            semanticImpact = SemanticImpact.NONE,
            reviewLink = null,
            verificationId = null,
        )
    }

    private fun idempotenceInspector(): IdempotenceInspector = IdempotenceInspector { approvedText, revision ->
        val textDraft = textCanonicalizer.canonicalize(
            TextProcessingInput.create(approvedText, TextSourceKind.PLAIN_TEXT),
            revision,
            approvals = TextReviewApprovals.create(TextReviewCode.entries.toSet()),
            idPrefix = "idempotence-text",
        )
        val urlDraft = urlService.process(
            UrlProcessingInput.create(textDraft.canonicalText, UrlSourceKind.PLAIN_TEXT),
            revision,
            approvals = UrlReviewApprovals.create(UrlReviewCode.entries.toSet()),
            idPrefix = "idempotence-url",
        )
        val second = urlDraft.canonicalText ?: textDraft.canonicalText
        val count = textDraft.ledgerEntries.size + urlDraft.canonicalizations.sumOf { it.ledgerEntries.size }
        ProviderResult.Completed(IdempotenceInspection(revision, second, count))
    }

    private fun recordContentFreeTrace(
        session: ManagedSession,
        manifest: List<ExecutedBlockManifestEntry>,
        transformations: List<AppliedTransformation>,
        canonicalRevision: CanonicalRevision,
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
            BlockPhase.entries.forEach { phase ->
                session.lifecycle.diagnosticTrace.record(
                    blockId = entry.blockId,
                    blockVersion = entry.blockVersion,
                    phase = phase,
                    executionRevision = entry.executionRevision,
                    canonicalRevision = canonicalRevision,
                    outcome = verificationOutcomes[entry.blockId.value] ?: TraceOutcome.SUCCESS,
                    reason = SessionDiagnosticReason.BLOCK_PHASE_COMPLETE,
                )
            }
        }
        transformations.forEachIndexed { index, transformation ->
            session.lifecycle.diagnosticTrace.record(
                blockId = transformation.blockId,
                blockVersion = transformation.blockVersion,
                transformationCategory = transformation.blockId.toTraceCategory(),
                executionRevision = ExecutionRevision((manifest.size + index + 1).toLong()),
                canonicalRevision = transformation.canonicalRevision,
                outcome = TraceOutcome.SUCCESS,
                reason = SessionDiagnosticReason.TRANSFORMATION_APPLIED,
            )
        }
    }

    private fun BlockId.toTraceCategory(): TransformationCategory = when {
        value.startsWith("URL-") -> TransformationCategory.URL_TRANSFORMATION
        value.startsWith("REN-") -> TransformationCategory.IMAGE_RENDER
        value.startsWith("OUT-") -> TransformationCategory.SERIALIZATION
        value.startsWith("VER-") -> TransformationCategory.VERIFICATION
        else -> TransformationCategory.TEXT_NORMALIZATION
    }

    private fun preset(inputKind: InputKind, outputMode: OutputMode) = when (inputKind to outputMode) {
        InputKind.TEXT to OutputMode.TEXT -> BuiltInPresets.textBalanced
        InputKind.TEXT to OutputMode.REBUILT_IMAGE -> BuiltInPresets.textRebuiltImage
        InputKind.TEXT to OutputMode.BOTH -> BuiltInPresets.textAndRebuiltImage
        InputKind.IMAGE to OutputMode.TEXT -> BuiltInPresets.imageCanonicalText
        InputKind.IMAGE to OutputMode.REBUILT_IMAGE -> BuiltInPresets.imageFullRebuild
        InputKind.IMAGE to OutputMode.BOTH -> BuiltInPresets.imageTextAndRebuiltImage
        else -> error("UNSUPPORTED_INPUT_OUTPUT_PRESET")
    }

    private fun ocrReviewFinding(inputKind: InputKind): Finding? = if (inputKind == InputKind.IMAGE) {
        Finding(
            findingId = FindingId("finding-ocr-transcription"),
            blockId = BlockId("REV-003"),
            category = FindingCategory.SEMANTIC,
            severity = Severity.HIGH,
            confidenceClass = ConfidenceClass.UNKNOWN,
            sourceLocation = null,
            canonicalLocation = null,
            title = SafeSummary("PROVISIONAL_LOCAL_OCR_REQUIRES_REVIEW"),
            explanation = SafeSummary("Every OCR character and reading-order line requires user review"),
            suggestedAction = DecisionAction.LOCK_EXACT_WORDING,
            semanticRisk = SemanticRisk.POSSIBLE_MEANING_CHANGE,
            requiresUserDecision = true,
            status = FindingStatus.REVIEW_REQUIRED,
            evidenceSummary = SafeSummary("Bundled local OCR transcription is untrusted until reviewed"),
        )
    } else {
        null
    }

    private fun imageExclusionFinding(inputKind: InputKind): Finding? = if (inputKind == InputKind.IMAGE) {
        Finding(
            findingId = FindingId("finding-image-source-exclusion"),
            blockId = BlockId("REV-006"),
            category = FindingCategory.IMAGE_REGION,
            severity = Severity.HIGH,
            confidenceClass = ConfidenceClass.CERTAIN_BY_PARSER,
            sourceLocation = null,
            canonicalLocation = null,
            title = SafeSummary("ORIGINAL_IMAGE_PIXELS_WILL_BE_REMOVED"),
            explanation = SafeSummary(
                "Canonical text and rebuilt-image outputs exclude the complete original raster, including non-text visuals",
            ),
            suggestedAction = DecisionAction.EXCLUDE_REGION,
            semanticRisk = SemanticRisk.POSSIBLE_MEANING_CHANGE,
            requiresUserDecision = true,
            status = FindingStatus.REVIEW_REQUIRED,
            evidenceSummary = SafeSummary("Whole-image unknown-region fallback prevents silent source-pixel retention"),
        )
    } else {
        null
    }

    private fun ocrScripts(scripts: Set<ScriptCode>): Set<OcrScript> = buildSet {
        val specific = scripts - setOf(ScriptCode.COMMON, ScriptCode.INHERITED)
        if (scripts.any { it in setOf(ScriptCode.LATIN, ScriptCode.GREEK, ScriptCode.CYRILLIC) } ||
            specific.isEmpty()
        ) {
            add(OcrScript.LATIN)
        }
        if (ScriptCode.DEVANAGARI in scripts) add(OcrScript.DEVANAGARI)
        if (ScriptCode.HAN in scripts) add(OcrScript.CHINESE)
        if (ScriptCode.KANA in scripts) add(OcrScript.JAPANESE)
        if (ScriptCode.HANGUL in scripts) add(OcrScript.KOREAN)
    }

    private fun randomToken(): String {
        val bytes = ByteArray(12).also(random::nextBytes)
        return try {
            bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } finally {
            bytes.fill(0)
        }
    }
}
