package app.shareguard.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelImmutabilityTest {
    @Test
    fun immutableList_snapshotsMutableInput() {
        val mutable = mutableListOf("first", "second")
        val snapshot = mutable.toImmutableList()

        mutable[0] = "changed"
        mutable += "third"

        assertThat(snapshot).containsExactly("first", "second").inOrder()
    }

    @Test
    fun persistentUpdates_doNotMutatePriorValue() {
        val original = immutableListOf("one")
        val updated = original.add("two")

        assertThat(original).containsExactly("one")
        assertThat(updated).containsExactly("one", "two").inOrder()
    }

    @Test
    fun contextAppendAndCopy_leavePreviousRevisionUntouched() {
        val original = ExecutionContext.create(
            sessionId = SessionId("session-copy"),
            workflowId = WorkflowId("workflow-copy"),
            workflowVersion = WorkflowVersion(1),
            inputKind = InputKind.TEXT,
            requestedOutput = OutputMode.TEXT,
            sourceHandle = SourceHandle("source-copy"),
        )

        val changed = original.appendFinding(ModelFixtures.finding())

        assertThat(original.executionRevision).isEqualTo(ExecutionRevision(0))
        assertThat(original.findings).isEmpty()
        assertThat(changed.executionRevision).isEqualTo(ExecutionRevision(1))
        assertThat(changed.findings).containsExactly(ModelFixtures.finding())
    }

    @Test
    fun nextCanonicalRevision_rebasesLinkedDecisionsAndDependencies() {
        val original = ModelFixtures.canonicalDocument()

        val revised = original.nextRevision()

        assertThat(original.revision).isEqualTo(CanonicalRevision(1))
        assertThat(original.userDecisions.single().canonicalRevision).isEqualTo(CanonicalRevision(1))
        assertThat(original.sourceDependencyMap.canonicalRevision).isEqualTo(CanonicalRevision(1))
        assertThat(revised.revision).isEqualTo(CanonicalRevision(2))
        assertThat(revised.userDecisions.single().canonicalRevision).isEqualTo(CanonicalRevision(2))
        assertThat(revised.sourceDependencyMap.canonicalRevision).isEqualTo(CanonicalRevision(2))
        assertThat(revised.sourceDependencyMap.entries.single().canonicalRevision).isEqualTo(CanonicalRevision(2))
    }

    @Test
    fun contentRevision_invalidatesPriorArtifactsAndVerification() {
        val context = verifiedContext()
        val oldDocument = context.canonicalDocument!!
        val nextRevision = oldDocument.revision.next()
        val newDependencyMap = SourceDependencyMap.create(
            canonicalRevision = nextRevision,
            entries = oldDocument.sourceDependencyMap.entries.map {
                it.copy(canonicalRevision = nextRevision)
            },
        )
        val newDecisions = oldDocument.userDecisions.map {
            it.copy(canonicalRevision = nextRevision)
        }.toImmutableList()
        val updatedDocument = oldDocument.copy(
            revision = nextRevision,
            userDecisions = newDecisions,
            sourceDependencyMap = newDependencyMap,
        )

        val invalidated = context.withCanonicalDocument(updatedDocument)

        assertThat(context.verificationReport).isNotNull()
        assertThat(invalidated.verificationReport).isNull()
        assertThat(invalidated.textArtifact).isNull()
        assertThat(invalidated.imageArtifact).isNull()
        assertThat(invalidated.currentAssurance).isEqualTo(AssuranceClass.AS_0_UNVERIFIED)
        assertThat(invalidated.canonicalDocument?.revision).isEqualTo(nextRevision)
    }

    @Test
    fun savedResultManagementChanges_doNotMutateArtifactOrAssurance() {
        val original = ModelFixtures.savedResult()
        val renamed = original.rename(DisplayLabel("Renamed result")).setFavourite(true)

        assertThat(original.displayLabel).isEqualTo(DisplayLabel("Result 1"))
        assertThat(original.favourite).isFalse()
        assertThat(renamed.displayLabel).isEqualTo(DisplayLabel("Renamed result"))
        assertThat(renamed.favourite).isTrue()
        assertThat(renamed.artifactManifest).isEqualTo(original.artifactManifest)
        assertThat(renamed.contentDigest).isEqualTo(original.contentDigest)
        assertThat(renamed.assuranceClass).isEqualTo(original.assuranceClass)
    }

    private fun verifiedContext(): ExecutionContext = ExecutionContext.create(
        sessionId = SessionId("session-verified"),
        workflowId = WorkflowId("workflow-verified"),
        workflowVersion = WorkflowVersion(1),
        inputKind = InputKind.TEXT,
        requestedOutput = OutputMode.BOTH,
        sourceHandle = SourceHandle("source-verified"),
        importAnchor = ModelFixtures.importAnchor(),
    )
        .appendFinding(ModelFixtures.finding())
        .appendDecision(ModelFixtures.decision())
        .withCanonicalDocument(ModelFixtures.canonicalDocument())
        .withArtifacts(
            textArtifact = ModelFixtures.textArtifact(),
            imageArtifact = ModelFixtures.imageArtifact(),
        )
        .withVerification(ModelFixtures.verificationReport())
}
