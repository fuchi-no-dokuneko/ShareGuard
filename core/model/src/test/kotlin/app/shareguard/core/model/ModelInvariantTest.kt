package app.shareguard.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelInvariantTest {
    @Test
    fun canonicalDocument_rejectsUnknownReadingOrderReference() {
        val document = ModelFixtures.canonicalDocument()

        assertThrows(IllegalArgumentException::class.java) {
            document.copy(
                readingOrder = ReadingOrder(immutableListOf(CanonicalBlockId("unknown-block"))),
            )
        }
    }

    @Test
    fun canonicalDocument_rejectsUnknownUrlReference() {
        val document = ModelFixtures.canonicalDocument()
        val badLink = LinkBlock(
            id = ModelFixtures.paragraphId,
            urlTokenId = UrlTokenId("missing-url"),
            displayRuns = immutableListOf(ModelFixtures.textRun()),
        )

        assertThrows(IllegalArgumentException::class.java) {
            document.copy(rootBlocks = immutableListOf(badLink))
        }
    }

    @Test
    fun canonicalDocument_rejectsUnknownDependencyDecision() {
        val document = ModelFixtures.canonicalDocument()
        val dependency = SourceDependency(
            dependencyId = DependencyId("unknown-decision-dependency"),
            type = DependencyType.USER_DECISION,
            origin = DependencyOrigin.USER_DECISION,
            canonicalRevision = document.revision,
            decisionId = DecisionId("missing-decision"),
            reason = SafeSummary("Test invalid reference"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            document.copy(
                sourceDependencyMap = SourceDependencyMap.create(document.revision, listOf(dependency)),
            )
        }
    }

    @Test
    fun context_rejectsDecisionForUnknownFinding() {
        val context = ExecutionContext.create(
            sessionId = SessionId("session-invalid-decision"),
            workflowId = WorkflowId("workflow-invalid-decision"),
            workflowVersion = WorkflowVersion(1),
            inputKind = InputKind.TEXT,
            requestedOutput = OutputMode.TEXT,
            sourceHandle = SourceHandle("source-invalid-decision"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            context.appendDecision(ModelFixtures.decision())
        }
    }

    @Test
    fun semanticChange_requiresReviewLinkage() {
        assertThrows(IllegalArgumentException::class.java) {
            ChangeEntry(
                changeId = ChangeId("change-without-review"),
                blockId = BlockId("TXT-012"),
                blockVersion = BlockVersion(1),
                canonicalRevision = ModelFixtures.revision,
                category = FindingCategory.CONFUSABLE,
                sourceLocation = null,
                beforeRepresentation = SensitiveRepresentation("A"),
                afterRepresentation = SensitiveRepresentation("Α"),
                reason = SafeSummary("Confusable token changed"),
                reversibleBeforeExport = true,
                semanticImpact = SemanticImpact.POSSIBLE,
                reviewLink = null,
                verificationId = null,
            )
        }
    }

    @Test
    fun context_rejectsChangeFromFutureCanonicalRevision() {
        val context = contextWithDocument()
        val change = ChangeEntry(
            changeId = ChangeId("future-change"),
            blockId = BlockId("TXT-013"),
            blockVersion = BlockVersion(1),
            canonicalRevision = CanonicalRevision(2),
            category = FindingCategory.PUNCTUATION,
            sourceLocation = null,
            beforeRepresentation = SensitiveRepresentation("  "),
            afterRepresentation = SensitiveRepresentation(" "),
            reason = SafeSummary("Whitespace normalized"),
            reversibleBeforeExport = true,
            semanticImpact = SemanticImpact.NONE,
            reviewLink = null,
            verificationId = null,
        )

        assertThrows(IllegalArgumentException::class.java) {
            context.appendChange(change)
        }
    }

    @Test
    fun verificationReport_rejectsHighAssuranceWhenRequiredVerifierDidNotRun() {
        val report = ModelFixtures.verificationReport()
        val notRun = ModelFixtures.verificationResult(
            type = VerificationType.FINAL_UNICODE,
            id = "verification-not-run",
            status = VerificationStatus.NOT_RUN,
        )

        assertThrows(IllegalArgumentException::class.java) {
            report.copy(results = immutableListOf(notRun))
        }
    }

    @Test
    fun verificationResult_requiresResidualForResidualPass() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelFixtures.verificationResult(
                status = VerificationStatus.PASS_WITH_DECLARED_RESIDUAL,
            )
        }
    }

    @Test
    fun referenceTimer_prefersSameBootMonotonicAndNeverReturnsNegative() {
        val anchor = ModelFixtures.importAnchor()

        assertThat(
            anchor.elapsedMillis(
                nowWallClock = WallClockInstant(999_999),
                nowMonotonic = MonotonicInstant(8_000_000),
                currentBootSessionReference = BootSessionReference("boot-1"),
            ),
        ).isEqualTo(3)
        assertThat(
            anchor.elapsedMillis(
                nowWallClock = WallClockInstant(1),
                currentBootSessionReference = BootSessionReference("different-boot"),
            ),
        ).isEqualTo(0)
    }

    private fun contextWithDocument(): ExecutionContext = ExecutionContext.create(
        sessionId = SessionId("session-document"),
        workflowId = WorkflowId("workflow-document"),
        workflowVersion = WorkflowVersion(1),
        inputKind = InputKind.TEXT,
        requestedOutput = OutputMode.TEXT,
        sourceHandle = SourceHandle("source-document"),
    )
        .appendFinding(ModelFixtures.finding())
        .appendDecision(ModelFixtures.decision())
        .withCanonicalDocument(ModelFixtures.canonicalDocument())
}
