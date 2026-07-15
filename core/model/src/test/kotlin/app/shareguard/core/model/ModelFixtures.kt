package app.shareguard.core.model

internal object ModelFixtures {
    val revision = CanonicalRevision(1)
    val artifactRevision = ArtifactRevision(1)
    val findingId = FindingId("finding-1")
    val decisionId = DecisionId("decision-1")
    val paragraphId = CanonicalBlockId("paragraph-1")

    fun digest(character: Char = 'a'): ContentDigest = ContentDigest(character.toString().repeat(64))

    fun textRun(text: String = "Canonical text"): TextRun = TextRun.create(
        canonicalText = text,
        languageTag = LanguageTag("en"),
        scriptSet = listOf(ScriptCode.LATIN),
        semanticRole = SemanticRole.BODY,
        confidenceClass = ConfidenceClass.CERTAIN_BY_PARSER,
    )

    fun finding(): Finding = Finding(
        findingId = findingId,
        blockId = BlockId("TXT-003"),
        category = FindingCategory.UNICODE,
        severity = Severity.MEDIUM,
        confidenceClass = ConfidenceClass.CERTAIN_BY_PARSER,
        sourceLocation = SourceLocation(0, 1, null, SafeSummary("first scalar")),
        canonicalLocation = CanonicalLocation(paragraphId, 0),
        title = SafeSummary("Invisible character"),
        explanation = SafeSummary("A default-ignorable scalar requires review"),
        suggestedAction = DecisionAction.ACCEPT_PROPOSED_CHANGE,
        semanticRisk = SemanticRisk.POSSIBLE_MEANING_CHANGE,
        requiresUserDecision = true,
        status = FindingStatus.REVIEW_REQUIRED,
        evidenceSummary = SafeSummary("Detected by the pinned Unicode parser"),
    )

    fun decision(revision: CanonicalRevision = this.revision): UserDecision = UserDecision.create(
        decisionId = decisionId,
        findingIds = listOf(findingId),
        action = DecisionAction.ACCEPT_PROPOSED_CHANGE,
        status = DecisionStatus.APPROVED,
        semanticImpact = SemanticImpact.POSSIBLE,
        rationale = SafeSummary("Reviewed with surrounding context"),
        canonicalRevision = revision,
    )

    fun dependencyMap(
        revision: CanonicalRevision = this.revision,
        retainedRegionId: ImageRegionId? = null,
    ): SourceDependencyMap {
        val entries = buildList {
            add(
                SourceDependency(
                    dependencyId = DependencyId("dependency-revision"),
                    type = DependencyType.CANONICAL_DOCUMENT_REVISION,
                    origin = DependencyOrigin.CANONICAL_DOCUMENT,
                    canonicalRevision = revision,
                    canonicalBlockId = paragraphId,
                    reason = SafeSummary("Artifact derives from the approved revision"),
                ),
            )
            if (retainedRegionId != null) {
                add(
                    SourceDependency(
                        dependencyId = DependencyId("dependency-pixels"),
                        type = DependencyType.RETAINED_SOURCE_PIXELS,
                        origin = DependencyOrigin.SOURCE,
                        canonicalRevision = revision,
                        imageRegionId = retainedRegionId,
                        decisionId = decisionId,
                        sourcePixelRetained = true,
                        reason = SafeSummary("User approved the retained region"),
                    ),
                )
            }
        }
        return SourceDependencyMap.create(revision, entries)
    }

    fun canonicalDocument(): CanonicalDocument {
        val block = ParagraphBlock(paragraphId, immutableListOf(textRun()))
        return CanonicalDocument(
            schemaVersion = SchemaVersion(1),
            revision = revision,
            declaredLanguagePolicy = LanguagePolicy.create(
                primaryLanguage = LanguageTag("en"),
                allowedScripts = listOf(ScriptCode.LATIN),
            ),
            rootBlocks = immutableListOf(block),
            urlTokens = ImmutableList.empty(),
            imageRegions = ImmutableList.empty(),
            layoutModel = LayoutModel(
                LayoutKind.PLAIN_DOCUMENT,
                immutableListOf(LayoutElement(paragraphId, null, 0)),
            ),
            readingOrder = ReadingOrder(immutableListOf(paragraphId)),
            userDecisions = immutableListOf(decision()),
            sourceDependencyMap = dependencyMap(),
            changeLedgerReference = ChangeLedgerId("ledger-1"),
        )
    }

    fun textArtifact(
        canonicalRevision: CanonicalRevision = revision,
        artifactRevision: ArtifactRevision = this.artifactRevision,
    ): TextArtifact = TextArtifact(
        artifactId = ArtifactId("artifact-text"),
        reference = ArtifactReference("managed-text"),
        artifactRevision = artifactRevision,
        canonicalRevision = canonicalRevision,
        mimeType = MimeType("text/plain"),
        digest = digest('a'),
        byteCount = ByteCount(14),
        canonicalText = "Canonical text",
    )

    fun imageArtifact(
        canonicalRevision: CanonicalRevision = revision,
        artifactRevision: ArtifactRevision = this.artifactRevision,
        dependencyMap: SourceDependencyMap = dependencyMap(canonicalRevision),
    ): ImageArtifact = ImageArtifact(
        artifactId = ArtifactId("artifact-image"),
        reference = ArtifactReference("managed-image"),
        artifactRevision = artifactRevision,
        canonicalRevision = canonicalRevision,
        mimeType = MimeType("image/png"),
        digest = digest('b'),
        byteCount = ByteCount(1_024),
        pixelSize = PixelSize(PixelDimension(800), PixelDimension(600)),
        sourceDependencyMap = dependencyMap,
    )

    fun bothBundle(): OutputBundle = OutputBundle(
        outputMode = OutputMode.BOTH,
        canonicalRevision = revision,
        textArtifact = textArtifact(),
        imageArtifact = imageArtifact(),
    )

    fun verificationResult(
        type: VerificationType = VerificationType.EXECUTED_BLOCK_MANIFEST,
        id: String = "verification-manifest",
        required: Boolean = true,
        status: VerificationStatus = VerificationStatus.PASS,
    ): VerificationResult = VerificationResult(
        verificationId = VerificationId(id),
        type = type,
        status = status,
        artifactRevision = artifactRevision,
        required = required,
        summary = SafeSummary("Verification completed"),
    )

    fun verificationReport(
        assuranceClass: AssuranceClass = AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE,
        sourcePixelRegions: ImmutableList<ImageRegionId> = ImmutableList.empty(),
    ): VerificationReport = VerificationReport(
        reportVersion = SchemaVersion(1),
        artifactRevision = artifactRevision,
        canonicalRevision = revision,
        executedBlockManifest = immutableListOf(
            ExecutedBlockManifestEntry(BlockId("SYS-001"), BlockVersion(1), ExecutionRevision(0), 0),
            ExecutedBlockManifestEntry(BlockId("VER-014"), BlockVersion(1), ExecutionRevision(1), 1),
        ),
        results = immutableListOf(verificationResult()),
        finalMetadataInventory = immutableListOf(MetadataInventoryEntry("IHDR", true, "PNG_REQUIRED")),
        finalUnicodeFindings = ImmutableList.empty(),
        finalUrlFindings = ImmutableList.empty(),
        ocrRoundTripFindings = ImmutableList.empty(),
        sourceReferenceAudit = verificationResult(
            type = VerificationType.SOURCE_REFERENCE,
            id = "verification-source-reference",
        ),
        sourcePixelRegionList = sourcePixelRegions,
        unresolvedFindingList = ImmutableList.empty(),
        assuranceClass = assuranceClass,
        assuranceRationale = SafeSummary("Required final-artifact checks passed"),
        verificationFailures = ImmutableList.empty(),
        generatedAtSessionTime = WallClockInstant(2_000),
    )

    fun importAnchor(): ImportAnchor = ImportAnchor(
        wallClock = WallClockInstant(1_000),
        monotonic = MonotonicInstant(5_000_000),
        bootSessionReference = BootSessionReference("boot-1"),
        clockConfidence = ImportClockConfidence.MONOTONIC_ACTIVE,
    )

    fun savedResult(
        assuranceClass: AssuranceClass = AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE,
    ): SavedResult {
        val verifiedBundle = bothBundle().withVerification(verificationReport(assuranceClass))
        val manifest = ArtifactManifest.fromBundle(verifiedBundle)
        return SavedResult.committed(
            savedResultId = SavedResultId("saved-result-1"),
            schemaVersion = SchemaVersion(1),
            displayLabel = DisplayLabel("Result 1"),
            outputMode = OutputMode.BOTH,
            artifactManifest = manifest,
            assuranceClass = assuranceClass,
            assuranceRationaleSummary = SafeSummary("Required checks passed"),
            verificationSummaryReference = VerificationSummaryReference("verification-summary-1"),
            verificationSummary = verifiedBundle.verificationReport?.compactSummary(),
            importAnchor = importAnchor(),
            persistedAtWallClock = WallClockInstant(2_100),
            contentDigest = digest('c'),
            previewReference = PreviewReference("preview-1"),
            favourite = false,
            createdByAppBuild = AppBuildId("build-1"),
        )
    }

    fun textSavedResult(): SavedResult {
        val report = verificationReport(AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT)
        val bundle = OutputBundle(
            outputMode = OutputMode.TEXT,
            canonicalRevision = revision,
            textArtifact = textArtifact(),
            verificationReport = report,
        )
        return SavedResult.committed(
            savedResultId = SavedResultId("saved-text-1"),
            schemaVersion = SchemaVersion(1),
            displayLabel = DisplayLabel("Text result"),
            outputMode = OutputMode.TEXT,
            artifactManifest = ArtifactManifest.fromBundle(bundle),
            assuranceClass = AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT,
            assuranceRationaleSummary = SafeSummary("Canonical text checks passed"),
            verificationSummaryReference = VerificationSummaryReference("verification-summary-text"),
            verificationSummary = report.compactSummary(),
            importAnchor = importAnchor(),
            persistedAtWallClock = WallClockInstant(2_100),
            contentDigest = digest('d'),
            previewReference = null,
            favourite = false,
            createdByAppBuild = AppBuildId("build-1"),
        )
    }
}
