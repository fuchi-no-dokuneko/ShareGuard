package app.shareguard.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ExecutionContext(
    val sessionId: SessionId,
    val workflowId: WorkflowId,
    val workflowVersion: WorkflowVersion,
    val executionRevision: ExecutionRevision,
    val inputKind: InputKind,
    val requestedOutput: OutputMode,
    val sourceHandle: SourceHandle,
    val importAnchor: ImportAnchor?,
    val canonicalDocument: CanonicalDocument?,
    val textArtifact: TextArtifact?,
    val imageArtifact: ImageArtifact?,
    val derivativeArtifact: DerivativeArtifact?,
    val findings: ImmutableList<Finding>,
    val decisions: ImmutableList<UserDecision>,
    val changes: ImmutableList<ChangeEntry>,
    val warnings: ImmutableList<PipelineWarning>,
    val sourceDependencyMap: SourceDependencyMap?,
    val verificationReport: VerificationReport?,
    val assuranceCeiling: AssuranceClass,
    val currentAssurance: AssuranceClass,
    val lifecycleState: ExecutionLifecycleState,
) {
    init {
        require(currentAssurance.isAtMost(assuranceCeiling)) {
            "Current assurance cannot exceed the computed ceiling"
        }
        if (requestedOutput == OutputMode.DERIVATIVE_IMAGE) {
            require(assuranceCeiling.isAtMost(AssuranceClass.AS_1_REENCODED_DERIVATIVE)) {
                "Derivative workflows cannot exceed AS-1"
            }
        }
        if (sourceDependencyMap?.retainsSourcePixels == true) {
            require(assuranceCeiling.isAtMost(AssuranceClass.AS_3_REBUILT_WITH_SOURCE_REGIONS)) {
                "Source pixel retention prevents AS-4"
            }
        }
        if (canonicalDocument != null) {
            require(sourceDependencyMap == null || sourceDependencyMap.canonicalRevision == canonicalDocument.revision) {
                "Execution dependency map revision mismatch"
            }
            require(changes.all { it.canonicalRevision.value <= canonicalDocument.revision.value }) {
                "Change entry references a future canonical revision"
            }
        } else {
            require(changes.isEmpty()) { "Changes require a Canonical Document revision" }
        }
        val canonicalRevision = canonicalDocument?.revision
        require(listOfNotNull(textArtifact, imageArtifact, derivativeArtifact).all {
            canonicalRevision != null && it.canonicalRevision == canonicalRevision
        }) { "Artifacts must link to the active Canonical Document revision" }
        require(findings.map { it.findingId }.distinct().size == findings.size) { "Finding IDs must be unique" }
        require(decisions.map { it.decisionId }.distinct().size == decisions.size) { "Decision IDs must be unique" }
        require(changes.map { it.changeId }.distinct().size == changes.size) { "Change IDs must be unique" }
        val findingIds = findings.map { it.findingId }.toSet()
        val decisionIds = decisions.map { it.decisionId }.toSet()
        require(decisions.flatMap { it.findingIds }.all { it in findingIds }) {
            "Decision references an unknown finding"
        }
        require(changes.all { change ->
            change.reviewLink == null ||
                (change.reviewLink.decisionId in decisionIds && change.reviewLink.findingIds.all { it in findingIds })
        }) { "Change review link references an unknown finding or decision" }
        if (verificationReport != null) {
            val artifacts = listOfNotNull(textArtifact, imageArtifact, derivativeArtifact)
            require(artifacts.isNotEmpty()) { "Verification report requires final artifacts" }
            require(artifacts.all { it.artifactRevision == verificationReport.artifactRevision }) {
                "Verification report artifact revision mismatch"
            }
            require(verificationReport.canonicalRevision == canonicalRevision) {
                "Verification report canonical revision mismatch"
            }
            require(currentAssurance == verificationReport.assuranceClass) {
                "Current assurance must match the verification report"
            }
        } else {
            require(currentAssurance == AssuranceClass.AS_0_UNVERIFIED) {
                "Assurance requires a verification report"
            }
        }
        if (lifecycleState == ExecutionLifecycleState.VERIFIED) {
            require(verificationReport != null && currentAssurance != AssuranceClass.AS_0_UNVERIFIED) {
                "VERIFIED lifecycle requires successful verification"
            }
        }
    }

    fun appendFinding(finding: Finding): ExecutionContext = copy(
        executionRevision = executionRevision.next(),
        findings = findings.add(finding),
    )

    fun appendDecision(decision: UserDecision): ExecutionContext = copy(
        executionRevision = executionRevision.next(),
        decisions = decisions.add(decision),
    )

    fun appendChange(change: ChangeEntry): ExecutionContext = copy(
        executionRevision = executionRevision.next(),
        changes = changes.add(change),
        verificationReport = null,
        currentAssurance = AssuranceClass.AS_0_UNVERIFIED,
        lifecycleState = ExecutionLifecycleState.RUNNING,
    )

    /** A content revision invalidates every artifact and verification result derived from the prior revision. */
    fun withCanonicalDocument(document: CanonicalDocument): ExecutionContext = copy(
        executionRevision = executionRevision.next(),
        canonicalDocument = document,
        textArtifact = null,
        imageArtifact = null,
        derivativeArtifact = null,
        sourceDependencyMap = document.sourceDependencyMap,
        verificationReport = null,
        currentAssurance = AssuranceClass.AS_0_UNVERIFIED,
        lifecycleState = ExecutionLifecycleState.RUNNING,
    )

    fun withArtifacts(
        textArtifact: TextArtifact? = null,
        imageArtifact: ImageArtifact? = null,
        derivativeArtifact: DerivativeArtifact? = null,
    ): ExecutionContext = copy(
        executionRevision = executionRevision.next(),
        textArtifact = textArtifact,
        imageArtifact = imageArtifact,
        derivativeArtifact = derivativeArtifact,
        verificationReport = null,
        currentAssurance = AssuranceClass.AS_0_UNVERIFIED,
        lifecycleState = ExecutionLifecycleState.RUNNING,
    )

    fun withVerification(report: VerificationReport): ExecutionContext = copy(
        executionRevision = executionRevision.next(),
        verificationReport = report,
        currentAssurance = report.assuranceClass,
        lifecycleState = if (report.assuranceClass == AssuranceClass.AS_0_UNVERIFIED) {
            ExecutionLifecycleState.REVIEW_REQUIRED
        } else {
            ExecutionLifecycleState.VERIFIED
        },
    )

    companion object {
        fun create(
            sessionId: SessionId,
            workflowId: WorkflowId,
            workflowVersion: WorkflowVersion,
            inputKind: InputKind,
            requestedOutput: OutputMode,
            sourceHandle: SourceHandle,
            importAnchor: ImportAnchor? = null,
            assuranceCeiling: AssuranceClass = when (requestedOutput) {
                OutputMode.TEXT -> AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT
                OutputMode.REBUILT_IMAGE, OutputMode.BOTH -> AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE
                OutputMode.DERIVATIVE_IMAGE -> AssuranceClass.AS_1_REENCODED_DERIVATIVE
            },
        ): ExecutionContext = ExecutionContext(
            sessionId = sessionId,
            workflowId = workflowId,
            workflowVersion = workflowVersion,
            executionRevision = ExecutionRevision(0),
            inputKind = inputKind,
            requestedOutput = requestedOutput,
            sourceHandle = sourceHandle,
            importAnchor = importAnchor,
            canonicalDocument = null,
            textArtifact = null,
            imageArtifact = null,
            derivativeArtifact = null,
            findings = ImmutableList.empty(),
            decisions = ImmutableList.empty(),
            changes = ImmutableList.empty(),
            warnings = ImmutableList.empty(),
            sourceDependencyMap = null,
            verificationReport = null,
            assuranceCeiling = assuranceCeiling,
            currentAssurance = AssuranceClass.AS_0_UNVERIFIED,
            lifecycleState = ExecutionLifecycleState.CREATED,
        )
    }
}
