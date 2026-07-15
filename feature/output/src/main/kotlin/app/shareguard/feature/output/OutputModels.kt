package app.shareguard.feature.output

import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.OutputMode

enum class OutputTab(val label: String) {
    CANONICAL_TEXT("Canonical text"),
    REBUILT_IMAGE("Rebuilt image"),
    DERIVATIVE_IMAGE("Derivative image"),
    DIFF("Diff"),
    VERIFICATION("Verification"),
}

data class OutputPreviewUiState(
    val mode: OutputMode,
    val selectedTab: OutputTab,
    val canonicalText: String?,
    val hasImage: Boolean,
    val imageDescription: String?,
    val diffSummary: List<String>,
    val verificationComplete: Boolean,
    val assuranceClass: AssuranceClass?,
    val verifierSummary: List<Pair<String, String>>,
    val retainedSourceRegionCount: Int,
    val fatalVerificationReason: String? = null,
) {
    init {
        require(retainedSourceRegionCount >= 0)
        require((mode == OutputMode.TEXT || mode == OutputMode.BOTH) == (canonicalText != null))
        require((mode != OutputMode.TEXT) == hasImage)
        if (verificationComplete) require(assuranceClass != null)
    }
}

data class VerificationReportUiState(
    val assuranceClass: AssuranceClass,
    val executedBlocks: List<String>,
    val findingsDetected: Int,
    val changesApplied: Int,
    val unresolvedFindings: List<String>,
    val retainedSourceRegions: List<String>,
    val finalMetadataSummary: String,
    val finalUnicodeSummary: String,
    val finalUrlSummary: String,
    val ocrRoundTripSummary: String?,
    val rebuiltOrDerived: String,
    val limitations: List<String>,
) {
    init { require(findingsDetected >= 0 && changesApplied >= 0) }
}

data class SavedConfirmationUiState(
    val resultId: String,
    val displayLabel: String,
    val mode: OutputMode,
    val assuranceClass: AssuranceClass,
    val elapsedSinceImport: String,
    val saved: Boolean,
    val persistenceError: String? = null,
)
