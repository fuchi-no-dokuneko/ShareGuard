package app.shareguard.block.verify

import app.shareguard.core.model.DecisionAction
import app.shareguard.core.model.DecisionId
import app.shareguard.core.model.DecisionStatus
import app.shareguard.core.model.DependencyId
import app.shareguard.core.model.DependencyOrigin
import app.shareguard.core.model.DependencyType
import app.shareguard.core.model.Finding
import app.shareguard.core.model.FindingCategory
import app.shareguard.core.model.FindingId
import app.shareguard.core.model.FindingStatus
import app.shareguard.core.model.ImageRegion
import app.shareguard.core.model.ImageRegionId
import app.shareguard.core.model.ImageRegionPolicy
import app.shareguard.core.model.ImageRegionType
import app.shareguard.core.model.ImmutableList
import app.shareguard.core.model.NormalizedRect
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SemanticImpact
import app.shareguard.core.model.SemanticRisk
import app.shareguard.core.model.Severity
import app.shareguard.core.model.SourceDependency
import app.shareguard.core.model.UserDecision
import app.shareguard.core.model.VerificationStatus
import app.shareguard.core.model.VerificationType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class VerificationAssuranceAndFailureTest {
    private val coordinator = FinalVerificationCoordinator()

    @Test
    fun `contradictory text assurance with retained source pixels is AS0 and classifier failure`() = runBlocking {
        val finding = Finding(
            findingId = FindingId("finding-retained"),
            blockId = app.shareguard.core.model.BlockId("REV-006"),
            category = FindingCategory.IMAGE_REGION,
            severity = Severity.HIGH,
            confidenceClass = app.shareguard.core.model.ConfidenceClass.CERTAIN_BY_PARSER,
            sourceLocation = null,
            canonicalLocation = null,
            title = SafeSummary("SOURCE_REGION_RETENTION"),
            explanation = SafeSummary("SOURCE_REGION_RETAINED_BY_USER"),
            suggestedAction = DecisionAction.ACCEPT_LOWER_ASSURANCE,
            semanticRisk = SemanticRisk.NONE,
            requiresUserDecision = true,
            status = FindingStatus.ACCEPTED,
            evidenceSummary = SafeSummary("CONTENT_FREE_REGION_EVIDENCE"),
        )
        val decision = UserDecision(
            decisionId = DecisionId("decision-retained"),
            findingIds = ImmutableList.of(finding.findingId),
            action = DecisionAction.ACCEPT_LOWER_ASSURANCE,
            status = DecisionStatus.APPROVED,
            semanticImpact = SemanticImpact.NONE,
            rationale = SafeSummary("USER_ACCEPTED_SOURCE_REGION"),
            canonicalRevision = VerificationFixtures.revision,
        )
        val region = ImageRegion(
            regionId = ImageRegionId("region-retained"),
            regionType = ImageRegionType.PHOTOGRAPH,
            sourceBounds = NormalizedRect(0f, 0f, 1f, 1f),
            canonicalBounds = NormalizedRect(0f, 0f, 1f, 1f),
            policy = ImageRegionPolicy.RETAIN_SOURCE_PIXELS,
            sourcePixelRetained = true,
            replacementAssetId = null,
            userApproved = true,
            dependencyReason = SafeSummary("USER_APPROVED_SOURCE_REGION"),
        )
        val dependency = SourceDependency(
            dependencyId = DependencyId("dep-retained"),
            type = DependencyType.RETAINED_SOURCE_PIXELS,
            origin = DependencyOrigin.SOURCE,
            canonicalRevision = VerificationFixtures.revision,
            imageRegionId = region.regionId,
            decisionId = decision.decisionId,
            sourcePixelRetained = true,
            reason = SafeSummary("DECLARED_RETAINED_SOURCE_PIXELS"),
        )
        val fixture = VerificationFixtures.text(
            findings = listOf(finding),
            decisions = listOf(decision),
            imageRegions = listOf(region),
            sourceDependencies = listOf(dependency),
        )

        val outcome = coordinator.verify(fixture.request, fixture.providers)

        val classifier = outcome.result(VerificationType.ASSURANCE_CLASSIFIER)
        assertThat(classifier.status).isEqualTo(VerificationStatus.FAIL)
        assertThat(classifier.failures.map { it.code })
            .containsAtLeast("ASSURANCE_EVIDENCE_CONTRADICTION", "PRESENTED_ASSURANCE_MISMATCH")
        assertThat(outcome.report.assuranceClass)
            .isEqualTo(app.shareguard.core.model.AssuranceClass.AS_0_UNVERIFIED)
        assertThat(outcome.canPersistVerifiedResult).isFalse()
    }

    @Test
    fun `provider exception becomes content-free ERROR and does not leak message`() = runBlocking {
        val fixture = VerificationFixtures.text()
        val secret = "content://provider/private/exception-secret.txt"
        val providers = fixture.providers.copy(
            artifactReopener = ArtifactReopener { throw IllegalStateException(secret) },
        )

        val outcome = coordinator.verify(fixture.request, providers)

        assertThat(outcome.result(VerificationType.PERSISTENT_REOPEN_AND_DIGEST).status)
            .isEqualTo(VerificationStatus.ERROR)
        assertThat(outcome.result(VerificationType.PERSISTENT_REOPEN_AND_DIGEST).failures.single().code)
            .isEqualTo("PROVIDER_EXECUTION_ERROR")
        assertThat(outcome.humanReadableReport.asPlainText()).doesNotContain(secret)
        assertThat(outcome.report.toString()).doesNotContain(secret)
        assertThat(outcome.canManagedShare).isFalse()
    }

    @Test
    fun `presented assurance can never override computed lower class`() = runBlocking {
        val fixture = VerificationFixtures.text()
        val providers = fixture.providers.copy(
            sensitiveLoggingInspector = SensitiveLoggingInspector {
                ProviderResult.Completed(SensitiveLoggingInspection(true, true, 1, 1, false))
            },
        )

        val outcome = coordinator.verify(fixture.request, providers)

        assertThat(outcome.report.assuranceClass)
            .isEqualTo(app.shareguard.core.model.AssuranceClass.AS_0_UNVERIFIED)
        assertThat(outcome.canManagedShare).isFalse()
    }

    private fun FinalVerificationOutcome.result(type: VerificationType) =
        if (type == VerificationType.SOURCE_REFERENCE) report.sourceReferenceAudit
        else report.results.single { it.type == type }
}
