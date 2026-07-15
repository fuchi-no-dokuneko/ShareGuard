package app.shareguard.testcorpus

import kotlinx.serialization.Serializable

/**
 * Versioned, declarative authority for adversarial fixtures.
 *
 * These models intentionally do not depend on pipeline model types. The corpus can therefore be loaded
 * while those models evolve, and adapters can be added at the test boundary of the consuming module.
 */
@Serializable
data class AdversarialCorpus(
    val schemaVersion: Int,
    val corpusVersion: String,
    val description: String,
    val requiredTextCases: List<CorpusCase>,
    val requiredUrlCases: List<CorpusCase>,
    val convergenceFamilies: List<ConvergenceFamily>,
    val canaryPrivacyCases: List<CanaryPrivacyCase>,
    val mutationCases: List<MutationCase>,
    val generatorCatalog: List<GeneratorDescriptor>,
)

@Serializable
data class CorpusCase(
    val caseId: String,
    val threatCategory: String,
    val languageScript: String,
    val sourceRepresentation: SourceRepresentation,
    val intendedVisibleMeaning: String,
    val expectedCanonicalText: String?,
    val expectedUrlDecisions: List<UrlDecisionExpectation>,
    val expectedRegionDecisions: List<RegionDecisionExpectation>,
    val expectedAssuranceCeiling: String,
    val expectedMandatoryReviews: List<String>,
    val prohibitedOutputResidue: List<String>,
    val referenceRendererVersion: String,
    val licenceProvenance: LicenceProvenance,
)

@Serializable
data class SourceRepresentation(
    val format: String,
    val value: String,
    val attributes: Map<String, String>,
)

@Serializable
data class UrlDecisionExpectation(
    val input: String,
    val decision: String,
    val expectedOutput: String?,
    val requiresReview: Boolean,
    val rationale: String,
)

@Serializable
data class RegionDecisionExpectation(
    val region: String,
    val decision: String,
    val rationale: String,
)

@Serializable
data class LicenceProvenance(
    val originType: String,
    val creator: String,
    val licenceExpression: String,
    val sourceDescription: String,
    val thirdParty: Boolean,
    val attribution: List<String>,
    val reviewStatus: String,
)

@Serializable
data class ConvergenceFamily(
    val familyId: String,
    val threatCategories: List<String>,
    val presetId: String,
    val intendedSemanticContent: String,
    val canonicalComparison: String,
    val expectedCanonicalText: String?,
    val compareRebuiltImageSemanticStructure: Boolean,
    val requireRendererDeterminism: Boolean,
    val variants: List<ConvergenceVariant>,
    val prohibitedSourceSpecificResidue: List<String>,
    val intentionalDifferences: List<String>,
    val licenceProvenance: LicenceProvenance,
)

@Serializable
data class ConvergenceVariant(
    val variantId: String,
    val sourceRepresentation: SourceRepresentation,
    val expectedCanonicalText: String,
    val sourceSpecificResidue: List<String>,
)

@Serializable
data class CanaryPrivacyCase(
    val caseId: String,
    val injectionPoint: String,
    val canaryValue: String,
    val injectedRepresentation: String,
    val searchSurfaces: List<String>,
    val approvedLocations: List<String>,
    val acceptance: String,
    val licenceProvenance: LicenceProvenance,
)

@Serializable
data class MutationCase(
    val caseId: String,
    val mutationCategory: String,
    val target: String,
    val mutation: String,
    val expectedControl: String,
    val expectedOutcome: String,
    val licenceProvenance: LicenceProvenance,
)

@Serializable
data class GeneratorDescriptor(
    val generatorId: String,
    val covers: List<String>,
    val implementation: String,
    val deterministic: Boolean,
    val parameters: List<String>,
)

@Serializable
data class GeneratedVariant(
    val variantId: String,
    val value: String,
    val attributes: Map<String, String> = emptyMap(),
)
