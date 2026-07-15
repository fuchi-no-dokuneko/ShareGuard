package app.shareguard.block.verify

import app.shareguard.block.text.TextProcessingInput
import app.shareguard.block.text.TextSourceKind
import app.shareguard.block.text.UnicodeTextInspector
import app.shareguard.block.url.CanonicalUrlSerializer
import app.shareguard.block.url.StandardsUrlAnalyzer
import app.shareguard.block.url.UrlCandidateExtractor
import app.shareguard.block.url.UrlProcessingInput
import app.shareguard.block.url.UrlSourceKind
import app.shareguard.core.model.ArtifactReference
import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.BlockId
import app.shareguard.core.model.DeclaredResidual
import app.shareguard.core.model.DependencyType
import app.shareguard.core.model.DerivativeArtifact
import app.shareguard.core.model.FindingCategory
import app.shareguard.core.model.FindingId
import app.shareguard.core.model.FindingStatus
import app.shareguard.core.model.ImageRegionPolicy
import app.shareguard.core.model.ImmutableList
import app.shareguard.core.model.MetadataInventoryEntry
import app.shareguard.core.model.OutputArtifact
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SchemaVersion
import app.shareguard.core.model.TextArtifact
import app.shareguard.core.model.UrlPolicy
import app.shareguard.core.model.VerificationFailure
import app.shareguard.core.model.VerificationFinding
import app.shareguard.core.model.VerificationReport
import app.shareguard.core.model.VerificationResult
import app.shareguard.core.model.VerificationStatus
import app.shareguard.core.model.VerificationType
import app.shareguard.core.model.toImmutableList
import app.shareguard.core.pipeline.AssuranceEvidence
import app.shareguard.core.pipeline.DeterministicAssuranceClassifier
import app.shareguard.core.pipeline.NormativeBlockCatalog
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * Runs final verification over exact reopened artifacts. Provider-backed checks are explicit: an absent
 * image/OCR/runtime implementation produces NOT_RUN and therefore cannot yield verified assurance.
 */
class FinalVerificationCoordinator(
    private val unicodeInspector: UnicodeTextInspector = UnicodeTextInspector(),
    private val urlExtractor: UrlCandidateExtractor = UrlCandidateExtractor(),
    private val urlAnalyzer: StandardsUrlAnalyzer = StandardsUrlAnalyzer(),
    private val urlSerializer: CanonicalUrlSerializer = CanonicalUrlSerializer(),
) {
    suspend fun verify(
        request: VerificationRequest,
        providers: VerificationProviders = VerificationProviders(),
    ): FinalVerificationOutcome {
        val document = requireNotNull(request.context.canonicalDocument) {
            "Final verification requires an approved Canonical Document"
        }
        require(document.revision == request.outputBundle.canonicalRevision) {
            "Verification request has mixed canonical revisions"
        }

        val reviewBundle = ReviewAuditor.audit(request)
        val evidence = collectEvidence(request, providers)
        val products = linkedMapOf<VerificationType, CheckProduct>()

        products[VerificationType.EXECUTED_BLOCK_MANIFEST] = manifestAudit(request)
        products[VerificationType.CANONICAL_REVISION_LINK] = revisionAudit(request, reviewBundle, evidence)
        products[VerificationType.FINAL_METADATA] = metadataAudit(request, evidence)
        products[VerificationType.OCR_ROUND_TRIP] = ocrAudit(request, evidence)
        products[VerificationType.FINAL_UNICODE] = unicodeAudit(request, evidence)
        products[VerificationType.FINAL_URL] = urlAudit(request, evidence)
        products[VerificationType.SOURCE_REFERENCE] = sourceReferenceAudit(request, evidence)
        products[VerificationType.SOURCE_PIXEL_DEPENDENCY] = dependencyAudit(request, evidence)
        products[VerificationType.MACHINE_READABLE_CODE] = barcodeAudit(request, evidence)
        products[VerificationType.VISUAL_REGION_COVERAGE] = regionCoverageAudit(request, evidence)
        products[VerificationType.IDEMPOTENCE] = idempotenceAudit(request, evidence)
        products[VerificationType.NO_NETWORK_RUNTIME] = runtimeAudit(request, evidence)
        products[VerificationType.SENSITIVE_LOGGING] = loggingAudit(request, evidence)
        products[VerificationType.PERSISTENT_REOPEN_AND_DIGEST] = reopenAudit(request, evidence)

        // VER-015 is deterministic and initially assumed PASS so VER-014 can classify the full required set.
        products[VerificationType.HUMAN_READABLE_REPORT] = pass(
            request,
            VerificationType.HUMAN_READABLE_REPORT,
            "REPORT_CROSSCHECK_PASSED",
        )
        products[VerificationType.ASSURANCE_CLASSIFIER] = assuranceAudit(request, products, evidence, reviewBundle)

        var assurance = products.getValue(VerificationType.ASSURANCE_CLASSIFIER).assuranceClass
            ?: AssuranceClass.AS_0_UNVERIFIED
        var humanReport = renderHumanReport(assurance, products, reviewBundle.results, request.dependencyScope)
        var reportValidationFailures = validateHumanReport(humanReport, products, request.sourceCanaries)

        if (reportValidationFailures.isNotEmpty()) {
            products[VerificationType.HUMAN_READABLE_REPORT] = fail(
                request,
                VerificationType.HUMAN_READABLE_REPORT,
                reportValidationFailures,
                "REPORT_VALIDATION_FAILED",
            )
            if (reportValidationFailures.contains("REPORT_CANARY_DETECTED")) {
                products[VerificationType.SOURCE_REFERENCE] = fail(
                    request,
                    VerificationType.SOURCE_REFERENCE,
                    listOf("REFERENCE_CANARY_DETECTED"),
                    "SOURCE_REFERENCE_AUDIT_FAILED",
                )
            }
            products[VerificationType.ASSURANCE_CLASSIFIER] = assuranceAudit(
                request,
                products,
                evidence,
                reviewBundle,
            )
            assurance = AssuranceClass.AS_0_UNVERIFIED
            humanReport = renderHumanReport(assurance, products, reviewBundle.results, request.dependencyScope)
        }

        val report = buildMachineReport(request, products, evidence, assurance)
        val summary = report.compactSummary()
        val persistableSurface = persistableSummarySurface(summary)
        if (containsAnyCanary(persistableSurface, request.sourceCanaries)) {
            products[VerificationType.HUMAN_READABLE_REPORT] = fail(
                request,
                VerificationType.HUMAN_READABLE_REPORT,
                listOf("PERSISTABLE_SUMMARY_CANARY_DETECTED"),
                "REPORT_VALIDATION_FAILED",
            )
            products[VerificationType.SOURCE_REFERENCE] = fail(
                request,
                VerificationType.SOURCE_REFERENCE,
                listOf("REFERENCE_CANARY_DETECTED"),
                "SOURCE_REFERENCE_AUDIT_FAILED",
            )
            products[VerificationType.ASSURANCE_CLASSIFIER] = assuranceAudit(
                request,
                products,
                evidence,
                reviewBundle,
            )
            assurance = AssuranceClass.AS_0_UNVERIFIED
            humanReport = renderHumanReport(assurance, products, reviewBundle.results, request.dependencyScope)
            val blockedReport = buildMachineReport(request, products, evidence, assurance)
            return outcome(request, blockedReport, humanReport, reviewBundle, evidence)
        }

        return outcome(request, report, humanReport, reviewBundle, evidence)
    }

    private suspend fun collectEvidence(
        request: VerificationRequest,
        providers: VerificationProviders,
    ): EvidenceBundle {
        val reopened = linkedMapOf<ArtifactReference, ProviderResult<ReopenedArtifact>>()
        request.outputBundle.artifacts.forEach { artifact ->
            reopened[artifact.reference] = callProvider("ARTIFACT_REOPENER_NOT_CONFIGURED") {
                providers.artifactReopener?.reopen(artifact)
            }
        }

        val imageInspections = linkedMapOf<ArtifactReference, ProviderResult<FinalImageInspection>>()
        val ocrInspections = linkedMapOf<ArtifactReference, ProviderResult<OcrRoundTripInspection>>()
        val barcodeInspections = linkedMapOf<ArtifactReference, ProviderResult<BarcodeInspection>>()
        val regionInspections = linkedMapOf<ArtifactReference, ProviderResult<RegionCoverageInspection>>()
        val approvedText = request.approvedCanonicalText
        val approvedOrder = request.context.canonicalDocument?.readingOrder?.blockIds ?: ImmutableList.empty()
        request.outputBundle.artifacts.filter { it.mimeType.value.startsWith("image/") }.forEach { artifact ->
            val exact = reopened.getValue(artifact.reference)
            imageInspections[artifact.reference] = dependentProvider(exact, "IMAGE_REQUIRES_REOPEN") { bytes ->
                providers.finalImageInspector?.inspect(bytes, request.policy.imagePolicy)
            }
            if (request.outputBundle.outputMode in setOf(OutputMode.REBUILT_IMAGE, OutputMode.BOTH) && approvedText != null) {
                ocrInspections[artifact.reference] = dependentProvider(exact, "OCR_REQUIRES_REOPEN") { bytes ->
                    providers.ocrRoundTripInspector?.inspect(bytes, approvedText, approvedOrder)
                }
            }
            if (request.policy.strictImageProfile) {
                barcodeInspections[artifact.reference] = dependentProvider(exact, "BARCODE_SCAN_REQUIRES_REOPEN") { bytes ->
                    providers.barcodeInspector?.inspect(bytes)
                }
            }
            regionInspections[artifact.reference] = dependentProvider(exact, "REGION_SCAN_REQUIRES_REOPEN") { bytes ->
                providers.regionCoverageInspector?.inspect(bytes)
            }
        }

        val idempotence = if (request.outputBundle.outputMode != OutputMode.DERIVATIVE_IMAGE && approvedText != null) {
            callProvider("IDEMPOTENCE_INSPECTOR_NOT_CONFIGURED") {
                providers.idempotenceInspector?.inspect(approvedText, request.outputBundle.canonicalRevision)
            }
        } else {
            ProviderResult.NotRun("IDEMPOTENCE_NOT_APPLICABLE")
        }
        val runtime = callProvider("RUNTIME_INSPECTOR_NOT_CONFIGURED") {
            providers.runtimePrivacyInspector?.inspect()
        }
        val logging = callProvider("LOGGING_INSPECTOR_NOT_CONFIGURED") {
            providers.sensitiveLoggingInspector?.inspect()
        }
        return EvidenceBundle(
            reopened = reopened,
            imageInspections = imageInspections,
            ocrInspections = ocrInspections,
            barcodeInspections = barcodeInspections,
            regionInspections = regionInspections,
            idempotence = idempotence,
            runtime = runtime,
            logging = logging,
        )
    }

    private suspend fun <T> callProvider(
        missingCode: String,
        call: suspend () -> ProviderResult<T>?,
    ): ProviderResult<T> = try {
        call() ?: ProviderResult.NotRun(missingCode)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        ProviderResult.Error("PROVIDER_EXECUTION_ERROR")
    }

    private suspend fun <T> dependentProvider(
        prerequisite: ProviderResult<ReopenedArtifact>,
        prerequisiteCode: String,
        call: suspend (ReopenedArtifact) -> ProviderResult<T>?,
    ): ProviderResult<T> = when (prerequisite) {
        is ProviderResult.Completed -> callProvider("PROVIDER_NOT_CONFIGURED") { call(prerequisite.evidence) }
        is ProviderResult.NotRun -> ProviderResult.NotRun(prerequisiteCode)
        is ProviderResult.Error -> ProviderResult.Error("ARTIFACT_REOPEN_ERROR")
    }

    private fun manifestAudit(request: VerificationRequest): CheckProduct {
        val actual = request.executedBlockManifest
        val persistIndex = request.preset.blockReferences.indexOfFirst { it.blockId == BlockId("PST-002") }
        val expected = if (persistIndex >= 0) {
            request.preset.blockReferences.take(persistIndex)
        } else {
            request.preset.blockReferences
        }
        val failures = buildList {
            if (request.context.workflowVersion != request.preset.presetVersion) add("WORKFLOW_VERSION_MISMATCH")
            if (request.context.inputKind != request.preset.inputKind) add("PRESET_INPUT_KIND_MISMATCH")
            if (request.outputBundle.outputMode != request.preset.outputMode) add("PRESET_OUTPUT_MODE_MISMATCH")
            if (actual.map { it.order } != actual.indices.toList()) add("MANIFEST_ORDER_NOT_CONTIGUOUS")
            if (!actual.zipWithNext().all { (a, b) -> a.executionRevision.value < b.executionRevision.value }) {
                add("MANIFEST_EXECUTION_REVISION_ORDER_INVALID")
            }
            if (actual.any { it.executionRevision.value > request.context.executionRevision.value }) {
                add("MANIFEST_FUTURE_EXECUTION_REVISION")
            }
            if (actual.size != expected.size) add("MANIFEST_BLOCK_COUNT_MISMATCH")
            val paired = actual.zip(expected)
            if (paired.any { (entry, reference) -> entry.blockId != reference.blockId }) {
                add("MANIFEST_BLOCK_ORDER_MISMATCH")
            }
            if (paired.any { (entry, reference) -> entry.blockVersion != reference.blockVersion }) {
                add("MANIFEST_BLOCK_VERSION_MISMATCH")
            }
            actual.forEach { entry ->
                val known = NormativeBlockCatalog.registry.find(entry.blockId)
                if (known == null) add("MANIFEST_UNKNOWN_BLOCK")
                else if (known.blockVersion != entry.blockVersion) add("MANIFEST_UNKNOWN_BLOCK_VERSION")
            }
        }.distinct()
        return if (failures.isEmpty()) {
            pass(request, VerificationType.EXECUTED_BLOCK_MANIFEST, "EXECUTED_MANIFEST_VALID")
        } else {
            fail(request, VerificationType.EXECUTED_BLOCK_MANIFEST, failures, "EXECUTED_MANIFEST_INVALID")
        }
    }

    private fun revisionAudit(
        request: VerificationRequest,
        review: ReviewAuditBundle,
        evidence: EvidenceBundle,
    ): CheckProduct {
        val document = requireNotNull(request.context.canonicalDocument)
        val failures = buildList {
            if (request.outputBundle.canonicalRevision != document.revision) add("BUNDLE_CANONICAL_REVISION_MISMATCH")
            if (request.changeLedger.canonicalRevision != document.revision) add("CHANGE_LEDGER_REVISION_MISMATCH")
            if (request.changeLedger.ledgerId != document.changeLedgerReference) add("CHANGE_LEDGER_REFERENCE_MISMATCH")
            if (request.context.sourceDependencyMap != document.sourceDependencyMap) {
                add("CONTEXT_DEPENDENCY_MAP_MISMATCH")
            }
            val documentDecisionIds = document.userDecisions.map { it.decisionId }.toSet()
            val approvedContextDecisionIds = request.context.decisions.filter {
                it.status == app.shareguard.core.model.DecisionStatus.APPROVED
            }.map { it.decisionId }.toSet()
            if (!documentDecisionIds.containsAll(approvedContextDecisionIds)) {
                add("CANONICAL_DECISION_LINK_MISMATCH")
            }
            if (request.context.changes.map { it.changeId }.toSet() !=
                request.changeLedger.entries.map { it.changeId }.toSet()
            ) {
                add("CONTEXT_CHANGE_LEDGER_MISMATCH")
            }
            val contextArtifacts = listOfNotNull(
                request.context.textArtifact,
                request.context.imageArtifact,
                request.context.derivativeArtifact,
            )
            if (contextArtifacts.map { it.artifactId }.toSet() !=
                request.outputBundle.artifacts.map { it.artifactId }.toSet()
            ) {
                add("CONTEXT_OUTPUT_BUNDLE_MISMATCH")
            }
            if (request.outputBundle.artifacts.any { it.canonicalRevision != document.revision }) {
                add("ARTIFACT_CANONICAL_REVISION_MISMATCH")
            }
            evidence.reopened.values.filterIsInstance<ProviderResult.Completed<ReopenedArtifact>>().forEach {
                if (it.evidence.canonicalRevision != document.revision) add("REOPENED_CANONICAL_REVISION_MISMATCH")
                if (it.evidence.artifactRevision != request.outputBundle.artifactRevision) {
                    add("REOPENED_ARTIFACT_REVISION_MISMATCH")
                }
            }
            val firstVerifier = request.executedBlockManifest.indexOfFirst { it.blockId.value.startsWith("VER-") }
            if (firstVerifier >= 0) {
                request.executedBlockManifest.drop(firstVerifier + 1).forEach { entry ->
                    if (NormativeBlockCatalog.registry.find(entry.blockId)?.contentTransforming == true) {
                        add("POST_VERIFICATION_TRANSFORMATION")
                    }
                }
            }
            addAll(review.structuralFailureCodes)
        }.distinct()
        if (failures.isNotEmpty()) {
            return fail(request, VerificationType.CANONICAL_REVISION_LINK, failures, "REVISION_LINK_AUDIT_FAILED")
        }
        val pending = review.results.filter { it.blocksLock }
        return if (pending.isNotEmpty()) {
            reviewRequired(request, VerificationType.CANONICAL_REVISION_LINK, "REQUIRED_REVIEW_INCOMPLETE")
        } else {
            pass(request, VerificationType.CANONICAL_REVISION_LINK, "CANONICAL_REVISION_LINK_VALID")
        }
    }

    private fun reopenAudit(request: VerificationRequest, evidence: EvidenceBundle): CheckProduct {
        if (!request.policy.requirePersistentReopen) {
            return notApplicable(request, VerificationType.PERSISTENT_REOPEN_AND_DIGEST, "REOPEN_POLICY_NOT_REQUIRED")
        }
        providerTerminal(evidence.reopened.values)?.let { terminal ->
            return providerProduct(request, VerificationType.PERSISTENT_REOPEN_AND_DIGEST, terminal)
        }
        val failures = buildList {
            request.outputBundle.artifacts.forEach { artifact ->
                val reopened = (evidence.reopened.getValue(artifact.reference) as
                    ProviderResult.Completed<ReopenedArtifact>).evidence
                val bytes = reopened.bytesCopy()
                val calculated = sha256(bytes)
                if (calculated != artifact.digest || calculated != reopened.digest) add("REOPEN_DIGEST_MISMATCH")
                if (reopened.byteCount != artifact.byteCount.value) add("REOPEN_BYTE_COUNT_MISMATCH")
                if (reopened.detectedMimeType != artifact.mimeType) add("REOPEN_MIME_MISMATCH")
                if (reopened.artifactRevision != artifact.artifactRevision) add("REOPEN_ARTIFACT_REVISION_MISMATCH")
                if (reopened.canonicalRevision != artifact.canonicalRevision) add("REOPEN_CANONICAL_REVISION_MISMATCH")
                if (!reopened.appPrivateLocation) add("ARTIFACT_LOCATION_NOT_PRIVATE")
                if (artifact is TextArtifact && !bytes.contentEquals(artifact.canonicalText.toByteArray(Charsets.UTF_8))) {
                    add("TEXT_EXACT_REPRESENTATION_MISMATCH")
                }
            }
        }.distinct()
        return if (failures.isEmpty()) {
            pass(request, VerificationType.PERSISTENT_REOPEN_AND_DIGEST, "EXACT_REOPEN_AND_DIGEST_PASSED")
        } else {
            fail(request, VerificationType.PERSISTENT_REOPEN_AND_DIGEST, failures, "EXACT_REOPEN_AND_DIGEST_FAILED")
        }
    }

    private fun metadataAudit(request: VerificationRequest, evidence: EvidenceBundle): CheckProduct {
        if (request.outputBundle.outputMode == OutputMode.TEXT) {
            return notApplicable(request, VerificationType.FINAL_METADATA, "IMAGE_METADATA_NOT_APPLICABLE")
        }
        providerTerminal(evidence.imageInspections.values)?.let { terminal ->
            return providerProduct(request, VerificationType.FINAL_METADATA, terminal)
        }
        val policy = request.policy.imagePolicy
        val inventory = mutableListOf<MetadataInventoryEntry>()
        val failures = buildList {
            evidence.imageInspections.values.filterIsInstance<ProviderResult.Completed<FinalImageInspection>>().forEach {
                val inspection = it.evidence
                if (inspection.artifactRevision != request.outputBundle.artifactRevision) add("IMAGE_INSPECTION_REVISION_MISMATCH")
                if (inspection.detectedMimeType !in policy.allowedMimeTypes) add("IMAGE_MIME_NOT_ALLOWED")
                if (policy.requireIndependentDecode && !inspection.independentlyDecodes) add("INDEPENDENT_IMAGE_DECODE_FAILED")
                inspection.metadataFieldCodes.sorted().forEach { code ->
                    val allowed = code in policy.allowedMetadataFieldCodes
                    inventory += MetadataInventoryEntry(code, allowed, "CONTENT_FREE_CLASS")
                    if (!allowed) add("UNEXPECTED_IMAGE_METADATA")
                }
                inspection.containerChunkCodes.sorted().forEach { code ->
                    val allowed = code in policy.allowedContainerChunkCodes
                    inventory += MetadataInventoryEntry(code, allowed, "CONTAINER_CHUNK")
                    if (!allowed) add("UNEXPECTED_CONTAINER_CHUNK")
                }
                if (inspection.embeddedThumbnailCount > policy.maximumEmbeddedThumbnails) {
                    add("EMBEDDED_THUMBNAIL_NOT_ALLOWED")
                }
                if (inspection.channelModelCode !in policy.allowedChannelModelCodes) add("CHANNEL_MODEL_NOT_ALLOWED")
                if (inspection.alphaModelCode !in policy.allowedAlphaModelCodes) add("ALPHA_MODEL_NOT_ALLOWED")
                if (inspection.colourProfileCode !in policy.allowedColourProfileCodes) add("COLOUR_PROFILE_NOT_ALLOWED")
            }
        }.distinct()
        return if (failures.isEmpty()) {
            pass(request, VerificationType.FINAL_METADATA, "FINAL_CONTAINER_POLICY_PASSED", metadata = inventory)
        } else {
            fail(request, VerificationType.FINAL_METADATA, failures, "FINAL_CONTAINER_POLICY_FAILED", metadata = inventory)
        }
    }

    private fun ocrAudit(request: VerificationRequest, evidence: EvidenceBundle): CheckProduct {
        if (request.outputBundle.outputMode !in setOf(OutputMode.REBUILT_IMAGE, OutputMode.BOTH)) {
            return notApplicable(request, VerificationType.OCR_ROUND_TRIP, "OCR_ROUND_TRIP_NOT_APPLICABLE")
        }
        val approvedText = request.approvedCanonicalText
            ?: return notRun(request, VerificationType.OCR_ROUND_TRIP, "APPROVED_TEXT_NOT_AVAILABLE")
        providerTerminal(evidence.ocrInspections.values)?.let { terminal ->
            return providerProduct(request, VerificationType.OCR_ROUND_TRIP, terminal)
        }
        val order = requireNotNull(request.context.canonicalDocument).readingOrder.blockIds
        val findings = mutableListOf<VerificationFinding>()
        val failures = buildList {
            evidence.ocrInspections.values.filterIsInstance<ProviderResult.Completed<OcrRoundTripInspection>>()
                .forEachIndexed { index, result ->
                    val inspection = result.evidence
                    if (inspection.artifactRevision != request.outputBundle.artifactRevision) add("OCR_REVISION_MISMATCH")
                    if (inspection.recognizedText != approvedText) add("OCR_TEXT_DIVERGENCE")
                    if (inspection.recognizedReadingOrder != order) add("OCR_READING_ORDER_DIVERGENCE")
                    if (inspection.differenceCodes.isNotEmpty()) add("OCR_CLASSIFIED_DIFFERENCE_PRESENT")
                    inspection.differenceCodes.forEachIndexed { differenceIndex, code ->
                        findings += VerificationFinding(
                            findingId = FindingId("ocr-$index-$differenceIndex"),
                            category = FindingCategory.SEMANTIC,
                            status = FindingStatus.DETECTED,
                            summary = SafeSummary(code),
                        )
                    }
                }
        }.distinct()
        return if (failures.isEmpty()) {
            pass(request, VerificationType.OCR_ROUND_TRIP, "OCR_ROUND_TRIP_PASSED", ocrFindings = findings)
        } else {
            fail(request, VerificationType.OCR_ROUND_TRIP, failures, "OCR_ROUND_TRIP_FAILED", ocrFindings = findings)
        }
    }

    private fun unicodeAudit(request: VerificationRequest, evidence: EvidenceBundle): CheckProduct {
        if (request.outputBundle.outputMode == OutputMode.DERIVATIVE_IMAGE) {
            return notApplicable(request, VerificationType.FINAL_UNICODE, "FINAL_UNICODE_NOT_APPLICABLE")
        }
        val approvedText = request.approvedCanonicalText
            ?: return notRun(request, VerificationType.FINAL_UNICODE, "APPROVED_TEXT_NOT_AVAILABLE")
        val representations = finalTextRepresentations(request, evidence)
        representations.terminal?.let { return providerProduct(request, VerificationType.FINAL_UNICODE, it) }
        val findings = mutableListOf<VerificationFinding>()
        val failures = mutableListOf<String>()
        representations.texts.forEachIndexed { representationIndex, text ->
            if (text != approvedText) failures += "FINAL_TEXT_NOT_APPROVED_TEXT"
            val inspection = try {
                unicodeInspector.inspect(
                    TextProcessingInput.create(text, TextSourceKind.PLAIN_TEXT),
                    requireNotNull(request.context.canonicalDocument).declaredLanguagePolicy,
                    "verify-unicode-$representationIndex",
                )
            } catch (_: Throwable) {
                failures += "FINAL_UNICODE_INSPECTION_ERROR"
                null
            }
            if (inspection != null) {
                if (inspection.failures.isNotEmpty()) failures += "FINAL_UNICODE_SCALAR_FAILURE"
                if (runCatching { unicodeInspector.reserializeScalars(inspection) }.getOrNull() != text) {
                    failures += "FINAL_UNICODE_SCALAR_REPARSE_MISMATCH"
                }
                inspection.findings.forEach { finding ->
                    val code = finding.title.value
                    val approved = code in request.policy.approvedUnicodeFindingCodes
                    findings += VerificationFinding(
                        findingId = finding.findingId,
                        category = finding.category,
                        status = if (approved) FindingStatus.ACCEPTED else FindingStatus.DETECTED,
                        summary = SafeSummary(code),
                    )
                    if (!approved) failures += "UNAPPROVED_FINAL_UNICODE_FINDING"
                }
            }
        }
        return if (failures.isEmpty()) {
            pass(request, VerificationType.FINAL_UNICODE, "FINAL_UNICODE_RESCAN_PASSED", unicodeFindings = findings)
        } else {
            fail(request, VerificationType.FINAL_UNICODE, failures.distinct(), "FINAL_UNICODE_RESCAN_FAILED", unicodeFindings = findings)
        }
    }

    private fun urlAudit(request: VerificationRequest, evidence: EvidenceBundle): CheckProduct {
        if (request.outputBundle.outputMode == OutputMode.DERIVATIVE_IMAGE) {
            return notApplicable(request, VerificationType.FINAL_URL, "FINAL_URL_NOT_APPLICABLE")
        }
        val representations = finalTextRepresentations(request, evidence)
        representations.terminal?.let { return providerProduct(request, VerificationType.FINAL_URL, it) }
        val document = requireNotNull(request.context.canonicalDocument)
        val expected = document.urlTokens.filter {
            it.chosenPolicy != UrlPolicy.REMOVE && it.finalText.isNotBlank()
        }
        val findings = mutableListOf<VerificationFinding>()
        val failures = mutableListOf<String>()
        representations.texts.forEachIndexed { representationIndex, text ->
            val candidates = try {
                urlExtractor.extract(
                    UrlProcessingInput.create(text, UrlSourceKind.PLAIN_TEXT),
                    "verify-url-$representationIndex",
                )
            } catch (_: Throwable) {
                failures += "FINAL_URL_EXTRACTION_ERROR"
                ImmutableList.empty()
            }
            val analyses = candidates.map(urlAnalyzer::analyze)
            if (analyses.size != expected.size) failures += "FINAL_URL_COUNT_MISMATCH"
            analyses.zip(expected).forEachIndexed { index, (analysis, token) ->
                val parsed = analysis.parsed
                if (parsed == null || analysis.failures.isNotEmpty()) {
                    failures += "FINAL_URL_PARSE_FAILED"
                } else {
                    if (parsed.parsedComponents != token.normalizedComponents) {
                        failures += "FINAL_URL_COMPONENT_MISMATCH"
                    }
                    if (parsed.candidate.parseTarget.value != token.finalText) {
                        failures += "FINAL_URL_SERIALIZATION_MISMATCH"
                    }
                    if (runCatching { urlSerializer.verifyStable(token.finalText, token.normalizedComponents) }.isFailure) {
                        failures += "FINAL_URL_REPARSE_UNSTABLE"
                    }
                }
                analysis.findings.forEachIndexed { findingIndex, finding ->
                    findings += VerificationFinding(
                        findingId = FindingId("url-$representationIndex-$index-$findingIndex"),
                        category = FindingCategory.URL,
                        status = FindingStatus.ACCEPTED,
                        summary = finding.title,
                    )
                }
            }
            if (analyses.size > expected.size) {
                analyses.drop(expected.size).forEachIndexed { index, analysis ->
                    findings += VerificationFinding(
                        findingId = FindingId("url-unexpected-$representationIndex-$index"),
                        category = FindingCategory.URL,
                        status = FindingStatus.DETECTED,
                        summary = SafeSummary("UNEXPECTED_FINAL_URL"),
                    )
                    if (analysis.parsed != null) failures += "UNAPPROVED_FINAL_URL"
                }
            }
        }
        return if (failures.isEmpty()) {
            pass(request, VerificationType.FINAL_URL, "FINAL_URL_RESCAN_PASSED", urlFindings = findings)
        } else {
            fail(request, VerificationType.FINAL_URL, failures.distinct(), "FINAL_URL_RESCAN_FAILED", urlFindings = findings)
        }
    }

    private fun sourceReferenceAudit(request: VerificationRequest, evidence: EvidenceBundle): CheckProduct {
        providerTerminal(listOf(evidence.runtime))?.let { terminal ->
            return providerProduct(request, VerificationType.SOURCE_REFERENCE, terminal)
        }
        val failures = buildList {
            val payloads = buildList {
                evidence.reopened.values.filterIsInstance<ProviderResult.Completed<ReopenedArtifact>>()
                    .forEach { add(it.evidence.bytesCopy()) }
                request.referenceSurfaces.forEach { add(it.payloadCopy()) }
                request.outputBundle.artifacts.forEach {
                    add(it.reference.value.toByteArray(Charsets.UTF_8))
                    add(it.mimeType.value.toByteArray(Charsets.UTF_8))
                }
            }
            if (payloads.any { containsAnyCanary(it, request.sourceCanaries) }) add("REFERENCE_CANARY_DETECTED")
            val runtime = (evidence.runtime as ProviderResult.Completed<RuntimePrivacyInspection>).evidence
            if (!runtime.appPrivateArtifactRoot) add("ARTIFACT_ROOT_NOT_PRIVATE")
            if (!runtime.outgoingMimeMatchesArtifact) add("OUTGOING_MIME_MISMATCH")
            if (!runtime.outgoingDigestMatchesArtifact) add("OUTGOING_DIGEST_MISMATCH")
            if (!runtime.outgoingContentUriAppScoped) add("OUTGOING_URI_SCOPE_INVALID")
            if (!runtime.temporaryReadGrantLeastPrivilege) add("TEMPORARY_PERMISSION_SCOPE_INVALID")
        }.distinct()
        return if (failures.isEmpty()) {
            pass(request, VerificationType.SOURCE_REFERENCE, "SOURCE_REFERENCE_AUDIT_PASSED")
        } else {
            fail(request, VerificationType.SOURCE_REFERENCE, failures, "SOURCE_REFERENCE_AUDIT_FAILED")
        }
    }

    private fun dependencyAudit(request: VerificationRequest, evidence: EvidenceBundle): CheckProduct {
        if (request.outputBundle.outputMode == OutputMode.TEXT) {
            return notApplicable(request, VerificationType.SOURCE_PIXEL_DEPENDENCY, "PIXEL_DEPENDENCY_NOT_APPLICABLE")
        }
        providerTerminal(evidence.regionInspections.values)?.let { terminal ->
            return providerProduct(request, VerificationType.SOURCE_PIXEL_DEPENDENCY, terminal)
        }
        val document = requireNotNull(request.context.canonicalDocument)
        val map = document.sourceDependencyMap
        val actual = map.entries.map {
            DependencyExpectation(
                type = it.type,
                canonicalBlockId = it.canonicalBlockId,
                imageRegionId = it.imageRegionId,
                decisionIdValue = it.decisionId?.value,
            )
        }.toSet()
        val retainedByOperations = evidence.regionInspections.values
            .filterIsInstance<ProviderResult.Completed<RegionCoverageInspection>>()
            .flatMap { it.evidence.sourcePixelOperationRegionIds }
            .toSet()
        val retainedByMap = map.retainedRegionIds.toSet()
        val retainedByPolicy = document.imageRegions.filter { it.sourcePixelRetained }.map { it.regionId }.toSet()
        val failures = buildList {
            if (actual != request.dependencyScope.expectedEntries) add("DEPENDENCY_EXPECTATION_MISMATCH")
            if (!actual.map { it.type }.toSet().containsAll(request.dependencyScope.exercisedTypes)) {
                add("EXERCISED_DEPENDENCY_TYPE_MISSING")
            }
            if (request.policy.requireExplicitPlatformLimitations &&
                request.dependencyScope.platformLimitationCodes.isEmpty()
            ) {
                add("PLATFORM_DEPENDENCY_LIMITATION_UNDECLARED")
            }
            if (retainedByMap != retainedByPolicy) add("RETAINED_REGION_POLICY_MAP_MISMATCH")
            if (retainedByOperations != retainedByMap) add("SOURCE_PIXEL_OPERATION_UNDECLARED")
            request.outputBundle.artifacts.forEach {
                val artifactMap = when (it) {
                    is app.shareguard.core.model.ImageArtifact -> it.sourceDependencyMap
                    is DerivativeArtifact -> it.sourceDependencyMap
                    else -> null
                }
                if (artifactMap != null && artifactMap != map) add("ARTIFACT_DEPENDENCY_MAP_MISMATCH")
            }
        }.distinct()
        if (failures.isNotEmpty()) {
            return fail(request, VerificationType.SOURCE_PIXEL_DEPENDENCY, failures, "SOURCE_DEPENDENCY_AUDIT_FAILED")
        }
        return if (request.dependencyScope.platformLimitationCodes.isNotEmpty()) {
            passWithResiduals(
                request,
                VerificationType.SOURCE_PIXEL_DEPENDENCY,
                request.dependencyScope.platformLimitationCodes,
                "SOURCE_DEPENDENCY_SCOPE_PASSED",
            )
        } else {
            pass(request, VerificationType.SOURCE_PIXEL_DEPENDENCY, "SOURCE_DEPENDENCY_SCOPE_PASSED")
        }
    }

    private fun barcodeAudit(request: VerificationRequest, evidence: EvidenceBundle): CheckProduct {
        if (request.outputBundle.outputMode == OutputMode.TEXT || !request.policy.strictImageProfile) {
            return notApplicable(request, VerificationType.MACHINE_READABLE_CODE, "BARCODE_RESCAN_NOT_APPLICABLE")
        }
        providerTerminal(evidence.barcodeInspections.values)?.let { terminal ->
            return providerProduct(request, VerificationType.MACHINE_READABLE_CODE, terminal)
        }
        val actual = evidence.barcodeInspections.values
            .filterIsInstance<ProviderResult.Completed<BarcodeInspection>>()
            .flatMap { it.evidence.codes }
            .toSet()
        val failures = buildList {
            if (actual != request.policy.approvedMachineReadableCodes) add("MACHINE_READABLE_CODE_MISMATCH")
            evidence.barcodeInspections.values.filterIsInstance<ProviderResult.Completed<BarcodeInspection>>()
                .forEach { if (it.evidence.artifactRevision != request.outputBundle.artifactRevision) add("BARCODE_REVISION_MISMATCH") }
        }.distinct()
        return if (failures.isEmpty()) {
            pass(request, VerificationType.MACHINE_READABLE_CODE, "MACHINE_READABLE_CODE_RESCAN_PASSED")
        } else {
            fail(request, VerificationType.MACHINE_READABLE_CODE, failures, "MACHINE_READABLE_CODE_RESCAN_FAILED")
        }
    }

    private fun regionCoverageAudit(request: VerificationRequest, evidence: EvidenceBundle): CheckProduct {
        if (request.outputBundle.outputMode == OutputMode.TEXT) {
            return notApplicable(request, VerificationType.VISUAL_REGION_COVERAGE, "REGION_COVERAGE_NOT_APPLICABLE")
        }
        providerTerminal(evidence.regionInspections.values)?.let { terminal ->
            return providerProduct(request, VerificationType.VISUAL_REGION_COVERAGE, terminal)
        }
        val expected = requireNotNull(request.context.canonicalDocument).imageRegions.associate { it.regionId to it.policy }
        val failures = buildList {
            evidence.regionInspections.values.filterIsInstance<ProviderResult.Completed<RegionCoverageInspection>>()
                .forEach {
                    if (it.evidence.artifactRevision != request.outputBundle.artifactRevision) add("REGION_COVERAGE_REVISION_MISMATCH")
                    if (it.evidence.terminalPolicies != expected) add("REGION_TERMINAL_POLICY_MISMATCH")
                }
        }.distinct()
        return if (failures.isEmpty()) {
            pass(request, VerificationType.VISUAL_REGION_COVERAGE, "VISUAL_REGION_COVERAGE_PASSED")
        } else {
            fail(request, VerificationType.VISUAL_REGION_COVERAGE, failures, "VISUAL_REGION_COVERAGE_FAILED")
        }
    }

    private fun idempotenceAudit(request: VerificationRequest, evidence: EvidenceBundle): CheckProduct {
        if (request.outputBundle.outputMode == OutputMode.DERIVATIVE_IMAGE) {
            return notApplicable(request, VerificationType.IDEMPOTENCE, "IDEMPOTENCE_NOT_APPLICABLE")
        }
        val approved = request.approvedCanonicalText
            ?: return notRun(request, VerificationType.IDEMPOTENCE, "APPROVED_TEXT_NOT_AVAILABLE")
        return when (val result = evidence.idempotence) {
            is ProviderResult.NotRun -> notRun(request, VerificationType.IDEMPOTENCE, result.reasonCode)
            is ProviderResult.Error -> error(request, VerificationType.IDEMPOTENCE, result.reasonCode)
            is ProviderResult.Completed -> {
                val failures = buildList {
                    if (result.evidence.canonicalRevision != request.outputBundle.canonicalRevision) {
                        add("IDEMPOTENCE_REVISION_MISMATCH")
                    }
                    if (result.evidence.secondPassText != approved) add("IDEMPOTENCE_TEXT_CHANGED")
                    if (result.evidence.secondPassChangeCount != 0) add("IDEMPOTENCE_LEDGER_NOT_EMPTY")
                }
                if (failures.isEmpty()) pass(request, VerificationType.IDEMPOTENCE, "IDEMPOTENCE_PASSED")
                else fail(request, VerificationType.IDEMPOTENCE, failures, "IDEMPOTENCE_FAILED")
            }
        }
    }

    private fun runtimeAudit(request: VerificationRequest, evidence: EvidenceBundle): CheckProduct {
        if (!request.policy.requireReleaseControls) {
            return notApplicable(request, VerificationType.NO_NETWORK_RUNTIME, "RELEASE_RUNTIME_CHECK_NOT_REQUIRED")
        }
        return when (val result = evidence.runtime) {
            is ProviderResult.NotRun -> notRun(request, VerificationType.NO_NETWORK_RUNTIME, result.reasonCode)
            is ProviderResult.Error -> error(request, VerificationType.NO_NETWORK_RUNTIME, result.reasonCode)
            is ProviderResult.Completed -> {
                val facts = result.evidence
                val failures = buildList {
                    if (!facts.networkEvidenceCaptured) add("NETWORK_EVIDENCE_NOT_CAPTURED")
                    if (facts.networkAttemptCount != 0) add("NETWORK_ATTEMPT_DETECTED")
                    if (facts.onDemandModelDownloadCount != 0) add("MODEL_DOWNLOAD_ATTEMPT_DETECTED")
                    if (facts.internetPermissionPresent) add("INTERNET_PERMISSION_PRESENT")
                    if (facts.broadStoragePermissionPresent) add("BROAD_STORAGE_PERMISSION_PRESENT")
                    if (!facts.appPrivateArtifactRoot) add("APP_PRIVATE_STORAGE_NOT_CONFIRMED")
                    if (!facts.cleanupCompleted) add("SESSION_CLEANUP_NOT_CONFIRMED")
                    if (!facts.temporaryReadGrantLeastPrivilege) add("TEMPORARY_PERMISSION_SCOPE_INVALID")
                }
                if (failures.isEmpty()) pass(request, VerificationType.NO_NETWORK_RUNTIME, "OFFLINE_RUNTIME_POLICY_PASSED")
                else fail(request, VerificationType.NO_NETWORK_RUNTIME, failures, "OFFLINE_RUNTIME_POLICY_FAILED")
            }
        }
    }

    private fun loggingAudit(request: VerificationRequest, evidence: EvidenceBundle): CheckProduct {
        if (!request.policy.requireReleaseControls) {
            return notApplicable(request, VerificationType.SENSITIVE_LOGGING, "LOGGING_RELEASE_AUDIT_NOT_REQUIRED")
        }
        return when (val result = evidence.logging) {
            is ProviderResult.NotRun -> notRun(request, VerificationType.SENSITIVE_LOGGING, result.reasonCode)
            is ProviderResult.Error -> error(request, VerificationType.SENSITIVE_LOGGING, result.reasonCode)
            is ProviderResult.Completed -> {
                val facts = result.evidence
                val failures = buildList {
                    if (!facts.staticScanCompleted) add("LOGGING_STATIC_SCAN_NOT_COMPLETED")
                    if (!facts.dynamicCanarySessionCompleted) add("LOGGING_CANARY_SESSION_NOT_COMPLETED")
                    if (facts.prohibitedPayloadMatchCount != 0) add("SENSITIVE_LOG_PAYLOAD_DETECTED")
                    if (facts.persistentProductionTracingEnabled) add("PERSISTENT_PRODUCTION_TRACE_ENABLED")
                }
                if (failures.isEmpty()) pass(request, VerificationType.SENSITIVE_LOGGING, "SENSITIVE_LOGGING_AUDIT_PASSED")
                else fail(request, VerificationType.SENSITIVE_LOGGING, failures, "SENSITIVE_LOGGING_AUDIT_FAILED")
            }
        }
    }

    private fun assuranceAudit(
        request: VerificationRequest,
        products: Map<VerificationType, CheckProduct>,
        evidence: EvidenceBundle,
        review: ReviewAuditBundle,
    ): CheckProduct {
        val excluded = setOf(VerificationType.ASSURANCE_CLASSIFIER)
        val requiredProducts = products.filterKeys { it !in excluded }.values.filter { it.result.required }
        val allRequiredPassed = requiredProducts.all { it.result.satisfiesRequirement }
        val failurePresent = products.values.any {
            it.result.status in setOf(VerificationStatus.FAIL, VerificationStatus.ERROR)
        }
        val dependencyPassed = products[VerificationType.SOURCE_PIXEL_DEPENDENCY]?.result?.let {
            it.status in setOf(
                VerificationStatus.PASS,
                VerificationStatus.PASS_WITH_DECLARED_RESIDUAL,
                VerificationStatus.NOT_APPLICABLE,
            )
        } == true
        val map = requireNotNull(request.context.canonicalDocument).sourceDependencyMap
        val retained = map.retainsSourcePixels
        val retainedDeclared = map.retainedRegionIds.isNotEmpty()
        val finalUnicodePassed = products[VerificationType.FINAL_UNICODE]?.result?.satisfiesRequirement == true
        val finalUrlPassed = products[VerificationType.FINAL_URL]?.result?.satisfiesRequirement == true
        val metadataPassed = products[VerificationType.FINAL_METADATA]?.result?.satisfiesRequirement == true
        val ocrPassed = products[VerificationType.OCR_ROUND_TRIP]?.result?.satisfiesRequirement == true
        val imageFacts = evidence.imageInspections.values
            .filterIsInstance<ProviderResult.Completed<FinalImageInspection>>()
            .map { it.evidence }
        val reviewApproved = review.results.none { it.blocksLock }
        val decision = DeterministicAssuranceClassifier.classify(
            AssuranceEvidence(
                outputMode = request.outputBundle.outputMode,
                assuranceCeiling = request.context.assuranceCeiling,
                allRequiredVerificationPassed = allRequiredPassed,
                verificationFailurePresent = failurePresent,
                externallyEdited = false,
                sourceDependencyInformationComplete = dependencyPassed,
                retainedSourcePixels = retained,
                retainedSourceRegionsDeclared = retainedDeclared,
                canonicalTextFromApprovedDocument = request.approvedCanonicalText != null && reviewApproved,
                finalUnicodePassed = finalUnicodePassed,
                finalUrlPassed = finalUrlPassed,
                unresolvedTextAmbiguitiesApproved = reviewApproved,
                freshlyRenderedTextAndUi = imageFacts.isNotEmpty() && imageFacts.all { it.freshlyAllocatedCanvas },
                bundledRendererAssetsOnly = imageFacts.isNotEmpty() && imageFacts.all { it.bundledRendererAssetsOnly },
                finalMetadataPassed = metadataPassed,
                ocrRoundTripPassed = ocrPassed,
            ),
        )
        val failures = buildList {
            if (decision.contradictions.isNotEmpty()) add("ASSURANCE_EVIDENCE_CONTRADICTION")
            if (request.presentedAssuranceClass != null && request.presentedAssuranceClass != decision.assuranceClass) {
                add("PRESENTED_ASSURANCE_MISMATCH")
            }
            if (allRequiredPassed && decision.assuranceClass == AssuranceClass.AS_0_UNVERIFIED) {
                add("ASSURANCE_PRECONDITION_NOT_ESTABLISHED")
            }
            if (!decision.assuranceClass.isAtMost(request.context.assuranceCeiling)) {
                add("ASSURANCE_CEILING_EXCEEDED")
            }
            if (request.outputBundle.outputMode == OutputMode.DERIVATIVE_IMAGE &&
                decision.assuranceClass.level > AssuranceClass.AS_1_REENCODED_DERIVATIVE.level
            ) {
                add("DERIVATIVE_ASSURANCE_EXCEEDED")
            }
        }.distinct()
        return if (failures.isEmpty()) {
            pass(
                request,
                VerificationType.ASSURANCE_CLASSIFIER,
                "ASSURANCE_CLASSIFIER_CROSSCHECK_PASSED",
            ).copy(assuranceClass = decision.assuranceClass)
        } else {
            fail(
                request,
                VerificationType.ASSURANCE_CLASSIFIER,
                failures,
                "ASSURANCE_CLASSIFIER_CROSSCHECK_FAILED",
            ).copy(assuranceClass = AssuranceClass.AS_0_UNVERIFIED)
        }
    }

    private fun buildMachineReport(
        request: VerificationRequest,
        products: Map<VerificationType, CheckProduct>,
        evidence: EvidenceBundle,
        requestedAssurance: AssuranceClass,
    ): VerificationReport {
        val source = products.getValue(VerificationType.SOURCE_REFERENCE).result
        val ordered = FinalVerificationCatalog.descriptors.mapNotNull { descriptor ->
            if (descriptor.type == VerificationType.SOURCE_REFERENCE) null else products[descriptor.type]?.result
        }
        val allResults = ordered + source
        val blocking = allResults.any { it.required && !it.satisfiesRequirement }
        val assuranceFailure = products.getValue(VerificationType.ASSURANCE_CLASSIFIER).result.status in
            setOf(VerificationStatus.FAIL, VerificationStatus.ERROR, VerificationStatus.NOT_RUN)
        val assurance = if (blocking || assuranceFailure) AssuranceClass.AS_0_UNVERIFIED else requestedAssurance
        val unresolved = request.context.findings.filter { finding ->
            finding.requiresUserDecision && request.context.decisions.none { decision ->
                decision.status == app.shareguard.core.model.DecisionStatus.APPROVED &&
                    finding.findingId in decision.findingIds &&
                    decision.canonicalRevision == request.outputBundle.canonicalRevision
            }
        }.map { it.findingId }.distinct().toImmutableList()
        val failures = allResults.flatMap { it.failures }.toImmutableList()
        return VerificationReport(
            reportVersion = SchemaVersion(1),
            artifactRevision = request.outputBundle.artifactRevision,
            canonicalRevision = request.outputBundle.canonicalRevision,
            executedBlockManifest = request.executedBlockManifest,
            results = ordered.toImmutableList(),
            finalMetadataInventory = products.values.flatMap { it.metadata }.toImmutableList(),
            finalUnicodeFindings = products[VerificationType.FINAL_UNICODE]?.unicodeFindings?.toImmutableList()
                ?: ImmutableList.empty(),
            finalUrlFindings = products[VerificationType.FINAL_URL]?.urlFindings?.toImmutableList()
                ?: ImmutableList.empty(),
            ocrRoundTripFindings = products[VerificationType.OCR_ROUND_TRIP]?.ocrFindings?.toImmutableList()
                ?: ImmutableList.empty(),
            sourceReferenceAudit = source,
            sourcePixelRegionList = requireNotNull(request.context.canonicalDocument)
                .sourceDependencyMap.retainedRegionIds,
            unresolvedFindingList = unresolved,
            assuranceClass = assurance,
            assuranceRationale = SafeSummary(assuranceRationale(assurance)),
            verificationFailures = failures,
            generatedAtSessionTime = request.generatedAtSessionTime,
        )
    }

    private fun renderHumanReport(
        assurance: AssuranceClass,
        products: Map<VerificationType, CheckProduct>,
        reviewAudits: List<ReviewAuditResult>,
        dependencyScope: DependencyVerificationScope,
    ): HumanReadableVerificationReport {
        val lines = FinalVerificationCatalog.descriptors.map { descriptor ->
            val status = products[descriptor.type]?.result?.status ?: VerificationStatus.NOT_RUN
            "${humanLabel(descriptor.type)}: ${humanStatus(status)}"
        } + reviewAudits.map { audit ->
            "${audit.type.blockId} review: ${humanStatus(audit.status)}"
        }
        val limitations = buildList {
            dependencyScope.platformLimitationCodes.sorted().forEach {
                add("Limitation: ${humanLimitation(it)}")
            }
            add("Verification applies only to the exact managed artifact; external copies are not monitored.")
            add("No result claims guaranteed anonymity or absence of every unknown watermark channel.")
        }
        return HumanReadableVerificationReport(
            title = "Final verification report",
            assuranceLabel = "Managed artifact assurance: ${humanAssurance(assurance)}",
            statusLines = lines.toImmutableList(),
            limitationLines = limitations.toImmutableList(),
        )
    }

    private fun validateHumanReport(
        report: HumanReadableVerificationReport,
        products: Map<VerificationType, CheckProduct>,
        canaries: ImmutableList<SourceCanary>,
    ): List<String> = buildList {
        if (containsAnyCanary(report.asPlainText().toByteArray(Charsets.UTF_8), canaries)) {
            add("REPORT_CANARY_DETECTED")
        }
        if (FinalVerificationCatalog.descriptors.any { descriptor ->
                val status = products[descriptor.type]?.result?.status ?: VerificationStatus.NOT_RUN
                report.statusLines.none {
                    it == "${humanLabel(descriptor.type)}: ${humanStatus(status)}"
                }
            }
        ) {
            add("REPORT_MACHINE_STATUS_MISMATCH")
        }
    }

    private fun outcome(
        request: VerificationRequest,
        report: VerificationReport,
        humanReport: HumanReadableVerificationReport,
        review: ReviewAuditBundle,
        evidence: EvidenceBundle,
    ): FinalVerificationOutcome {
        val blockingTypes = (report.results + report.sourceReferenceAudit)
            .filter { it.required && !it.satisfiesRequirement }
            .map { it.type }
            .distinct()
            .toImmutableList()
        val reopenPassed = report.results.single {
            it.type == VerificationType.PERSISTENT_REOPEN_AND_DIGEST
        }.satisfiesRequirement
        val canVerify = report.assuranceClass != AssuranceClass.AS_0_UNVERIFIED &&
            report.requiredVerificationPassed && blockingTypes.isEmpty() && review.results.none { it.blocksLock }
        val digests = evidence.reopened.mapNotNull { (reference, result) ->
            (result as? ProviderResult.Completed)?.evidence?.let { reference to it.digest }
        }.toMap()
        return FinalVerificationOutcome(
            report = report,
            persistableSummary = report.compactSummary(),
            humanReadableReport = humanReport,
            reviewAudits = review.results.toImmutableList(),
            canPersistVerifiedResult = canVerify && reopenPassed && request.policy.requirePersistentReopen,
            canManagedShare = canVerify && reopenPassed,
            blockingVerificationTypes = blockingTypes,
            reopenedDigests = digests,
        )
    }

    private fun finalTextRepresentations(
        request: VerificationRequest,
        evidence: EvidenceBundle,
    ): TextRepresentations {
        val texts = mutableListOf<String>()
        request.outputBundle.textArtifact?.let { artifact ->
            when (val reopened = evidence.reopened[artifact.reference]) {
                is ProviderResult.Completed -> {
                    val decoded = decodeUtf8Strict(reopened.evidence.bytesCopy())
                    if (decoded == null) return TextRepresentations(emptyList(), ProviderTerminal.Error("FINAL_TEXT_UTF8_INVALID"))
                    texts += decoded
                }
                is ProviderResult.NotRun -> return TextRepresentations(emptyList(), ProviderTerminal.NotRun(reopened.reasonCode))
                is ProviderResult.Error -> return TextRepresentations(emptyList(), ProviderTerminal.Error(reopened.reasonCode))
                null -> return TextRepresentations(emptyList(), ProviderTerminal.NotRun("TEXT_REOPEN_NOT_AVAILABLE"))
            }
        }
        if (request.outputBundle.outputMode in setOf(OutputMode.REBUILT_IMAGE, OutputMode.BOTH)) {
            val terminal = providerTerminal(evidence.ocrInspections.values)
            if (terminal != null) return TextRepresentations(emptyList(), terminal)
            evidence.ocrInspections.values.filterIsInstance<ProviderResult.Completed<OcrRoundTripInspection>>()
                .forEach { texts += it.evidence.recognizedText }
        }
        return if (texts.isEmpty()) {
            TextRepresentations(emptyList(), ProviderTerminal.NotRun("FINAL_TEXT_REPRESENTATION_NOT_AVAILABLE"))
        } else {
            TextRepresentations(texts, null)
        }
    }

    private fun required(request: VerificationRequest, type: VerificationType): Boolean {
        val descriptor = FinalVerificationCatalog.require(type)
        if (!descriptor.appliesTo(request.outputBundle.outputMode, request.policy.requireReleaseControls)) return false
        if (type == VerificationType.PERSISTENT_REOPEN_AND_DIGEST && !request.policy.requirePersistentReopen) return false
        if (type == VerificationType.MACHINE_READABLE_CODE && !request.policy.strictImageProfile) return false
        return descriptor.requiredByDefault
    }

    private fun pass(
        request: VerificationRequest,
        type: VerificationType,
        summaryCode: String,
        metadata: List<MetadataInventoryEntry> = emptyList(),
        unicodeFindings: List<VerificationFinding> = emptyList(),
        urlFindings: List<VerificationFinding> = emptyList(),
        ocrFindings: List<VerificationFinding> = emptyList(),
    ): CheckProduct = product(
        request,
        type,
        VerificationStatus.PASS,
        summaryCode,
        metadata = metadata,
        unicodeFindings = unicodeFindings,
        urlFindings = urlFindings,
        ocrFindings = ocrFindings,
    )

    private fun passWithResiduals(
        request: VerificationRequest,
        type: VerificationType,
        residualCodes: Set<String>,
        summaryCode: String,
    ): CheckProduct {
        val descriptor = FinalVerificationCatalog.require(type)
        return CheckProduct(
            result = VerificationResult(
                verificationId = descriptor.verificationId,
                type = type,
                status = VerificationStatus.PASS_WITH_DECLARED_RESIDUAL,
                artifactRevision = request.outputBundle.artifactRevision,
                required = required(request, type),
                summary = SafeSummary(summaryCode),
                residuals = residualCodes.sorted().map {
                    DeclaredResidual(it, SafeSummary(it))
                }.toImmutableList(),
            ),
        )
    }

    private fun fail(
        request: VerificationRequest,
        type: VerificationType,
        failureCodes: List<String>,
        summaryCode: String,
        metadata: List<MetadataInventoryEntry> = emptyList(),
        unicodeFindings: List<VerificationFinding> = emptyList(),
        urlFindings: List<VerificationFinding> = emptyList(),
        ocrFindings: List<VerificationFinding> = emptyList(),
    ): CheckProduct {
        val descriptor = FinalVerificationCatalog.require(type)
        val distinct = failureCodes.distinct().ifEmpty { listOf("UNSPECIFIED_VERIFICATION_FAILURE") }
        distinct.forEach(::requireContentFreeCode)
        return CheckProduct(
            result = VerificationResult(
                verificationId = descriptor.verificationId,
                type = type,
                status = VerificationStatus.FAIL,
                artifactRevision = request.outputBundle.artifactRevision,
                required = required(request, type),
                summary = SafeSummary(summaryCode),
                failures = distinct.map {
                    VerificationFailure(descriptor.verificationId, it, SafeSummary(it))
                }.toImmutableList(),
            ),
            metadata = metadata,
            unicodeFindings = unicodeFindings,
            urlFindings = urlFindings,
            ocrFindings = ocrFindings,
        )
    }

    private fun error(request: VerificationRequest, type: VerificationType, code: String): CheckProduct {
        val descriptor = FinalVerificationCatalog.require(type)
        requireContentFreeCode(code)
        return CheckProduct(
            VerificationResult(
                verificationId = descriptor.verificationId,
                type = type,
                status = VerificationStatus.ERROR,
                artifactRevision = request.outputBundle.artifactRevision,
                required = required(request, type),
                summary = SafeSummary("VERIFIER_EXECUTION_ERROR"),
                failures = ImmutableList.of(
                    VerificationFailure(descriptor.verificationId, code, SafeSummary(code)),
                ),
            ),
        )
    }

    private fun notRun(request: VerificationRequest, type: VerificationType, code: String): CheckProduct =
        product(request, type, VerificationStatus.NOT_RUN, code)

    private fun reviewRequired(request: VerificationRequest, type: VerificationType, code: String): CheckProduct =
        product(request, type, VerificationStatus.REVIEW_REQUIRED, code)

    private fun notApplicable(request: VerificationRequest, type: VerificationType, code: String): CheckProduct =
        product(request, type, VerificationStatus.NOT_APPLICABLE, code, forceRequired = false)

    private fun product(
        request: VerificationRequest,
        type: VerificationType,
        status: VerificationStatus,
        summaryCode: String,
        forceRequired: Boolean? = null,
        metadata: List<MetadataInventoryEntry> = emptyList(),
        unicodeFindings: List<VerificationFinding> = emptyList(),
        urlFindings: List<VerificationFinding> = emptyList(),
        ocrFindings: List<VerificationFinding> = emptyList(),
    ): CheckProduct {
        requireContentFreeCode(summaryCode)
        val descriptor = FinalVerificationCatalog.require(type)
        return CheckProduct(
            result = VerificationResult(
                verificationId = descriptor.verificationId,
                type = type,
                status = status,
                artifactRevision = request.outputBundle.artifactRevision,
                required = forceRequired ?: required(request, type),
                summary = SafeSummary(summaryCode),
            ),
            metadata = metadata,
            unicodeFindings = unicodeFindings,
            urlFindings = urlFindings,
            ocrFindings = ocrFindings,
        )
    }

    private fun providerProduct(
        request: VerificationRequest,
        type: VerificationType,
        terminal: ProviderTerminal,
    ): CheckProduct = when (terminal) {
        is ProviderTerminal.NotRun -> notRun(request, type, terminal.code)
        is ProviderTerminal.Error -> error(request, type, terminal.code)
    }

    private fun providerTerminal(results: Collection<ProviderResult<*>>): ProviderTerminal? {
        if (results.isEmpty()) return ProviderTerminal.NotRun("APPLICABLE_PROVIDER_EVIDENCE_MISSING")
        val error = results.filterIsInstance<ProviderResult.Error>().firstOrNull()
        if (error != null) return ProviderTerminal.Error(error.reasonCode)
        val notRun = results.filterIsInstance<ProviderResult.NotRun>().firstOrNull()
        if (notRun != null) return ProviderTerminal.NotRun(notRun.reasonCode)
        return null
    }

    private fun sha256(bytes: ByteArray): app.shareguard.core.model.ContentDigest {
        val value = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return app.shareguard.core.model.ContentDigest(value)
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun containsAnyCanary(payload: ByteArray, canaries: ImmutableList<SourceCanary>): Boolean =
        canaries.any { payload.containsSubsequence(it.secretCopy()) }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) if (this[start + offset] != needle[offset]) continue@outer
            return true
        }
        return false
    }

    private fun persistableSummarySurface(summary: app.shareguard.core.model.VerificationSummary): ByteArray =
        buildString {
            append(summary.reportVersion.value).append('|')
            append(summary.artifactRevision.value).append('|')
            append(summary.canonicalRevision.value).append('|')
            append(summary.assuranceClass.name).append('|')
            append(summary.assuranceRationale.value).append('|')
            summary.resultStatuses.forEach { append(it.type.name).append(':').append(it.status.name).append('|') }
            append(summary.unresolvedFindingCount).append('|').append(summary.retainedSourceRegionCount)
        }.toByteArray(Charsets.UTF_8)

    private fun assuranceRationale(value: AssuranceClass): String = when (value) {
        AssuranceClass.AS_0_UNVERIFIED -> "REQUIRED_VERIFICATION_NOT_ESTABLISHED"
        AssuranceClass.AS_1_REENCODED_DERIVATIVE -> "VERIFIED_REENCODED_DERIVATIVE"
        AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT -> "VERIFIED_REVIEWED_CANONICAL_TEXT"
        AssuranceClass.AS_3_REBUILT_WITH_SOURCE_REGIONS -> "VERIFIED_REBUILD_WITH_DECLARED_SOURCE_REGIONS"
        AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE -> "VERIFIED_FULL_TEXTUAL_REBUILD"
    }

    private fun humanLabel(type: VerificationType): String = when (type) {
        VerificationType.EXECUTED_BLOCK_MANIFEST -> "Workflow manifest"
        VerificationType.CANONICAL_REVISION_LINK -> "Canonical revision linkage"
        VerificationType.FINAL_METADATA -> "Final container and metadata"
        VerificationType.FINAL_UNICODE -> "Final Unicode text"
        VerificationType.FINAL_URL -> "Final URL components"
        VerificationType.OCR_ROUND_TRIP -> "Rendered text round trip"
        VerificationType.SOURCE_REFERENCE -> "Source reference exposure"
        VerificationType.SOURCE_PIXEL_DEPENDENCY -> "Source dependency map"
        VerificationType.MACHINE_READABLE_CODE -> "QR and barcode scan"
        VerificationType.VISUAL_REGION_COVERAGE -> "Image region coverage"
        VerificationType.IDEMPOTENCE -> "Canonical idempotence"
        VerificationType.NO_NETWORK_RUNTIME -> "Offline runtime and permissions"
        VerificationType.SENSITIVE_LOGGING -> "Sensitive logging"
        VerificationType.ASSURANCE_CLASSIFIER -> "Assurance classification"
        VerificationType.HUMAN_READABLE_REPORT -> "Report consistency"
        VerificationType.PERSISTENT_REOPEN_AND_DIGEST -> "Managed artifact reopen and digest"
    }

    private fun humanStatus(status: VerificationStatus): String = when (status) {
        VerificationStatus.PASS -> "passed"
        VerificationStatus.PASS_WITH_DECLARED_RESIDUAL -> "passed with a declared limitation"
        VerificationStatus.REVIEW_REQUIRED -> "review required"
        VerificationStatus.NOT_APPLICABLE -> "not applicable"
        VerificationStatus.NOT_RUN -> "not run"
        VerificationStatus.FAIL -> "failed"
        VerificationStatus.ERROR -> "could not complete"
    }

    private fun humanAssurance(value: AssuranceClass): String = when (value) {
        AssuranceClass.AS_0_UNVERIFIED -> "unverified"
        AssuranceClass.AS_1_REENCODED_DERIVATIVE -> "reviewed derivative"
        AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT -> "reviewed canonical text"
        AssuranceClass.AS_3_REBUILT_WITH_SOURCE_REGIONS -> "rebuilt with declared source regions"
        AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE -> "fully rebuilt textual image"
    }

    private fun humanLimitation(code: String): String = when (code) {
        "PLATFORM_INTERNALS_NOT_ATTESTED" ->
            "dependencies inside platform codecs, native libraries, font shaping, and hardware are not fully attestable."
        else -> "a content-free implementation limitation is recorded as $code."
    }

    private data class EvidenceBundle(
        val reopened: Map<ArtifactReference, ProviderResult<ReopenedArtifact>>,
        val imageInspections: Map<ArtifactReference, ProviderResult<FinalImageInspection>>,
        val ocrInspections: Map<ArtifactReference, ProviderResult<OcrRoundTripInspection>>,
        val barcodeInspections: Map<ArtifactReference, ProviderResult<BarcodeInspection>>,
        val regionInspections: Map<ArtifactReference, ProviderResult<RegionCoverageInspection>>,
        val idempotence: ProviderResult<IdempotenceInspection>,
        val runtime: ProviderResult<RuntimePrivacyInspection>,
        val logging: ProviderResult<SensitiveLoggingInspection>,
    )

    private data class CheckProduct(
        val result: VerificationResult,
        val metadata: List<MetadataInventoryEntry> = emptyList(),
        val unicodeFindings: List<VerificationFinding> = emptyList(),
        val urlFindings: List<VerificationFinding> = emptyList(),
        val ocrFindings: List<VerificationFinding> = emptyList(),
        val assuranceClass: AssuranceClass? = null,
    )

    private sealed interface ProviderTerminal {
        val code: String
        data class NotRun(override val code: String) : ProviderTerminal
        data class Error(override val code: String) : ProviderTerminal
    }

    private data class TextRepresentations(
        val texts: List<String>,
        val terminal: ProviderTerminal?,
    )
}
