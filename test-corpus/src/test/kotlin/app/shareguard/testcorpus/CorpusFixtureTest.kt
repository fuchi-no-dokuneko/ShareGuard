package app.shareguard.testcorpus

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CorpusFixtureTest {
    private val corpus by lazy { CorpusLoader.loadDefault() }

    @Test
    fun requiredTextAndUrlIdsExistExactlyOnce() {
        val expectedTextIds = (1..20).map { "TC-TXT-%03d".format(it) }
        val expectedUrlIds = (1..15).map { "TC-URL-%03d".format(it) }

        assertThat(corpus.requiredTextCases.map { it.caseId }).containsExactlyElementsIn(expectedTextIds)
        assertThat(corpus.requiredUrlCases.map { it.caseId }).containsExactlyElementsIn(expectedUrlIds)

        val allIds = (corpus.requiredTextCases + corpus.requiredUrlCases).map { it.caseId }
        assertThat(allIds.toSet()).hasSize(35)
        assertThat(allIds).containsNoDuplicates()
    }

    @Test
    fun everyFixtureIsParseableAndCarriesRequiredMetadata() {
        assertThat(corpus.schemaVersion).isEqualTo(1)
        assertThat(corpus.corpusVersion).isNotEmpty()

        (corpus.requiredTextCases + corpus.requiredUrlCases).forEach { fixture ->
            assertThat(fixture.caseId).isNotEmpty()
            assertThat(fixture.threatCategory).isNotEmpty()
            assertThat(fixture.languageScript).isNotEmpty()
            assertThat(fixture.sourceRepresentation.format).isNotEmpty()
            assertThat(fixture.sourceRepresentation.value).isNotEmpty()
            assertThat(fixture.intendedVisibleMeaning).isNotEmpty()
            assertThat(fixture.expectedCanonicalText).isNotNull()
            assertThat(fixture.expectedAssuranceCeiling).isAnyOf("AS-0", "AS-1", "AS-2", "AS-3", "AS-4")
            assertThat(fixture.referenceRendererVersion).isNotEmpty()
            assertValidLicence(fixture.licenceProvenance)
        }

        corpus.requiredUrlCases.forEach { fixture ->
            assertThat(fixture.expectedUrlDecisions).isNotEmpty()
        }
    }

    @Test
    fun convergenceFamiliesDeclareEqualityDeterminismAndResidueExpectations() {
        assertThat(corpus.convergenceFamilies.size).isAtLeast(6)
        assertThat(corpus.convergenceFamilies.map { it.familyId }).containsNoDuplicates()

        corpus.convergenceFamilies.forEach { family ->
            assertThat(family.variants.size).isAtLeast(2)
            assertThat(family.presetId).isNotEmpty()
            assertThat(family.intendedSemanticContent).isNotEmpty()
            assertThat(family.compareRebuiltImageSemanticStructure).isTrue()
            assertThat(family.requireRendererDeterminism).isTrue()
            assertThat(family.prohibitedSourceSpecificResidue).isNotEmpty()
            assertValidLicence(family.licenceProvenance)

            if (family.canonicalComparison == "EXACT") {
                assertThat(family.expectedCanonicalText).isNotNull()
                assertThat(family.variants.map { it.expectedCanonicalText }.toSet())
                    .containsExactly(family.expectedCanonicalText)
            } else {
                assertThat(family.canonicalComparison).isEqualTo("REVIEWED_DIFFERENCES")
                assertThat(family.intentionalDifferences).isNotEmpty()
            }
        }
    }

    @Test
    fun canaryInventoryCoversEveryInjectionPointAndRequiredSearchSurface() {
        val requiredInjectionPoints = setOf(
            "SOURCE_FILENAME",
            "SOURCE_URI",
            "METADATA",
            "URL_QUERY",
            "HIDDEN_UNICODE",
            "OCR_CROP",
            "SESSION_PATH",
        )
        val requiredSearchSurfaces = setOf(
            "LOGS",
            "CRASH_OUTPUT",
            "REPORTS",
            "SAVED_OUTPUT",
            "OUTGOING_INTENT",
            "APP_CACHE_AFTER_PURGE",
            "SAVED_RESULT_MANAGEMENT_METADATA",
            "PREVIEWS",
            "EXPORTED_ARTIFACTS",
        )

        assertThat(corpus.canaryPrivacyCases.map { it.injectionPoint }.toSet())
            .containsExactlyElementsIn(requiredInjectionPoints)
        assertThat(corpus.canaryPrivacyCases.map { it.canaryValue }).containsNoDuplicates()
        corpus.canaryPrivacyCases.forEach { fixture ->
            assertThat(fixture.injectedRepresentation).contains(fixture.canaryValue)
            assertThat(fixture.searchSurfaces).containsAtLeastElementsIn(requiredSearchSurfaces)
            assertThat(fixture.approvedLocations).containsExactly("SESSION_LIMITED_REVIEW")
            assertThat(fixture.acceptance)
                .isEqualTo("ABSENT_OUTSIDE_APPROVED_VISIBLE_CONTENT_AND_SESSION_REVIEW")
            assertValidLicence(fixture.licenceProvenance)
        }
    }

    @Test
    fun mutationInventoryCoversBaselineAndPersistentResultMutations() {
        val requiredCategories = setOf(
            "BLOCK_ORDER",
            "BLOCK_VERSION",
            "METADATA_ALLOWLIST",
            "URL_PARSER_OUTPUT",
            "CANONICAL_DOCUMENT_REVISION",
            "RENDERER_SOURCE_DEPENDENCY_FLAGS",
            "OUTGOING_INTENT_MIME",
            "CLEANUP_POLICY",
            "STORED_ARTIFACT_BYTES",
            "PREVIEW_SUBSTITUTION",
            "ORPHAN_METADATA",
            "ORPHAN_FILE",
            "INTERRUPTED_DELETION",
            "INTERRUPTED_TRANSACTIONAL_SAVE",
            "INVALIDATED_RESULT_SHARE",
            "MIGRATION_BYTE_CHANGE",
            "PERSISTED_SOURCE_REFERENCE",
        )

        assertThat(corpus.mutationCases.map { it.mutationCategory }.toSet())
            .containsExactlyElementsIn(requiredCategories)
        assertThat(corpus.mutationCases.map { it.caseId }).containsNoDuplicates()
        corpus.mutationCases.forEach { mutation ->
            assertThat(mutation.expectedControl).isNotEmpty()
            assertThat(mutation.expectedOutcome).isAnyOf("REJECT", "INVALIDATE", "QUARANTINE", "DETECT")
            assertValidLicence(mutation.licenceProvenance)
        }
    }

    @Test
    fun generatorCatalogIsDeterministicAndDefinesNoThresholds() {
        val requiredGeneratorIds = setOf(
            "GEN-UNICODE-ZERO-WIDTH",
            "GEN-UNICODE-SPACES",
            "GEN-NEWLINES",
            "GEN-COMBINING-MARKS",
            "GEN-PUNCTUATION",
            "GEN-URL-TRACKING",
            "GEN-URL-PERCENT-ENCODING",
            "GEN-METADATA",
        )
        assertThat(corpus.generatorCatalog.map { it.generatorId }.toSet())
            .containsExactlyElementsIn(requiredGeneratorIds)
        corpus.generatorCatalog.forEach { descriptor ->
            assertThat(descriptor.deterministic).isTrue()
            assertThat(descriptor.parameters.none { "threshold" in it.lowercase() }).isTrue()
            assertThat(descriptor.implementation).startsWith("CorpusGenerators.")
        }
    }

    private fun assertValidLicence(metadata: LicenceProvenance) {
        assertThat(metadata.originType).isEqualTo("PROJECT_AUTHORED_SYNTHETIC")
        assertThat(metadata.creator).isNotEmpty()
        assertThat(metadata.licenceExpression).startsWith("LicenseRef-")
        assertThat(metadata.sourceDescription).isNotEmpty()
        assertThat(metadata.thirdParty).isFalse()
        assertThat(metadata.reviewStatus).isEqualTo("PROJECT_REVIEW_REQUIRED_BEFORE_EXTERNAL_REUSE")
    }
}
