package app.shareguard.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class ModelSerializationTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        classDiscriminator = "modelType"
    }

    @Test
    fun canonicalDocument_roundTripsWithEveryBlockType() {
        val document = documentWithEveryBlockType()

        val encoded = json.encodeToString(document)
        val decoded = json.decodeFromString<CanonicalDocument>(encoded)

        assertThat(decoded).isEqualTo(document)
        assertThat(decoded.rootBlocks.map { it::class }).containsExactlyElementsIn(
            document.rootBlocks.map { it::class },
        ).inOrder()
    }

    @Test
    fun executionContext_roundTripsWithImmutableStateAndVerification() {
        val context = verifiedContext()

        val encoded = json.encodeToString(context)
        val decoded = json.decodeFromString<ExecutionContext>(encoded)

        assertThat(decoded).isEqualTo(context)
        assertThat(decoded.currentAssurance).isEqualTo(AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE)
        assertThat(decoded.lifecycleState).isEqualTo(ExecutionLifecycleState.VERIFIED)
    }

    @Test
    fun savedResult_roundTripsWithoutLosingSchemaOrIntegrityState() {
        val saved = ModelFixtures.savedResult()

        val encoded = json.encodeToString(saved)
        val decoded = json.decodeFromString<SavedResult>(encoded)

        assertThat(decoded).isEqualTo(saved)
        assertThat(decoded.artifactManifest.artifactRevision).isEqualTo(ModelFixtures.artifactRevision)
        assertThat(decoded.canManagedShare).isTrue()
    }

    @Test
    fun sourceModels_roundTripButRemainSeparateFromSavedResultSchema() {
        val text = TextSource.snapshot(
            internalId = SourceId("source-1"),
            sourceMime = MimeType("text/plain"),
            importMethod = ImportMethod.ANDROID_SHARE,
            plainText = "session-only source",
            sourceStylePresent = true,
        )
        val image = ImageSource.snapshot(
            internalId = SourceId("source-2"),
            sourceMimeClaim = MimeType("image/jpeg"),
            detectedFormat = MimeType("image/png"),
            internalSourceHandle = SourceHandle("source-handle-2"),
            importMethod = ImportMethod.PHOTO_PICKER,
            byteLengthMetadata = ByteCount(42),
        )

        assertThat(json.decodeFromString<SourceModel>(json.encodeToString<SourceModel>(text))).isEqualTo(text)
        assertThat(json.decodeFromString<SourceModel>(json.encodeToString<SourceModel>(image))).isEqualTo(image)
    }

    private fun verifiedContext(): ExecutionContext {
        val initial = ExecutionContext.create(
            sessionId = SessionId("session-1"),
            workflowId = WorkflowId("preset-both"),
            workflowVersion = WorkflowVersion(1),
            inputKind = InputKind.TEXT,
            requestedOutput = OutputMode.BOTH,
            sourceHandle = SourceHandle("source-handle-1"),
            importAnchor = ModelFixtures.importAnchor(),
        )
        return initial
            .appendFinding(ModelFixtures.finding())
            .appendDecision(ModelFixtures.decision())
            .withCanonicalDocument(ModelFixtures.canonicalDocument())
            .withArtifacts(
                textArtifact = ModelFixtures.textArtifact(),
                imageArtifact = ModelFixtures.imageArtifact(),
            )
            .withVerification(ModelFixtures.verificationReport())
    }

    private fun documentWithEveryBlockType(): CanonicalDocument {
        val ids = (1..12).map { CanonicalBlockId("block-$it") }
        val regionId = ImageRegionId("region-1")
        val urlId = UrlTokenId("url-1")
        val run = ModelFixtures.textRun()
        val blocks: ImmutableList<CanonicalBlock> = immutableListOf(
            ParagraphBlock(ids[0], immutableListOf(run)),
            HeadingBlock(ids[1], 2, immutableListOf(run.copy(semanticRole = SemanticRole.HEADING))),
            ListBlock(ids[2], true, immutableListOf(CanonicalListItem(immutableListOf(run)))),
            QuoteBlock(ids[3], immutableListOf(run.copy(semanticRole = SemanticRole.QUOTE))),
            MessageBlock(ids[4], MessageRole.AUTHOR, immutableListOf(run.copy(semanticRole = SemanticRole.MESSAGE))),
            CodeBlock(ids[5], "val answer = 42", "kotlin"),
            TableLikeBlock(ids[6], immutableListOf(TableRow(immutableListOf(TableCell(immutableListOf(run)))))),
            LinkBlock(ids[7], urlId, immutableListOf(run.copy(semanticRole = SemanticRole.LINK))),
            ImagePlaceholderBlock(ids[8], regionId, "Image removed"),
            RedactionBlock(ids[9], regionId, "Redacted"),
            GenericUiBlock(ids[10], "message_bubble", immutableListOf(run)),
            UnknownRegionBlock(ids[11], regionId, SafeSummary("Unsupported source region")),
        )
        val parsed = UrlComponents.create(
            scheme = "https",
            host = "example.test",
            registrableDomain = "example.test",
            pathSegments = listOf("article"),
        )
        val region = ImageRegion(
            regionId = regionId,
            regionType = ImageRegionType.UNKNOWN,
            sourceBounds = NormalizedRect(0f, 0f, 1f, 1f),
            canonicalBounds = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f),
            policy = ImageRegionPolicy.REPLACE_WITH_PLACEHOLDER,
            sourcePixelRetained = false,
            replacementAssetId = ArtifactReference("placeholder-generic"),
            userApproved = true,
            dependencyReason = SafeSummary("Source region is not retained"),
        )
        val dependencyMap = SourceDependencyMap.create(
            canonicalRevision = ModelFixtures.revision,
            entries = listOf(
                SourceDependency(
                    dependencyId = DependencyId("dependency-all-blocks"),
                    type = DependencyType.CANONICAL_DOCUMENT_REVISION,
                    origin = DependencyOrigin.CANONICAL_DOCUMENT,
                    canonicalRevision = ModelFixtures.revision,
                    canonicalBlockId = ids.first(),
                    reason = SafeSummary("Approved canonical structure"),
                ),
            ),
        )
        return CanonicalDocument(
            schemaVersion = SchemaVersion(1),
            revision = ModelFixtures.revision,
            declaredLanguagePolicy = LanguagePolicy.create(
                primaryLanguage = LanguageTag("en"),
                allowedScripts = listOf(ScriptCode.LATIN),
            ),
            rootBlocks = blocks,
            urlTokens = immutableListOf(
                UrlToken(
                    tokenId = urlId,
                    originalReference = null,
                    displayText = "example.test/article",
                    parsedComponents = parsed,
                    normalizedComponents = parsed,
                    chosenPolicy = UrlPolicy.KEEP_FULL,
                    finalText = "https://example.test/article",
                    functionalityWarning = null,
                    userApproved = true,
                ),
            ),
            imageRegions = immutableListOf(region),
            layoutModel = LayoutModel(
                LayoutKind.MANUAL,
                ids.mapIndexed { index, id -> LayoutElement(id, null, index) }.toImmutableList(),
            ),
            readingOrder = ReadingOrder(ids.toImmutableList()),
            userDecisions = immutableListOf(ModelFixtures.decision()),
            sourceDependencyMap = dependencyMap,
            changeLedgerReference = ChangeLedgerId("ledger-all-blocks"),
        )
    }
}
