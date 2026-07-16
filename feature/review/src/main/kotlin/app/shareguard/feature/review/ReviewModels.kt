package app.shareguard.feature.review

import app.shareguard.core.model.ConfidenceClass
import app.shareguard.core.model.DecisionAction
import app.shareguard.core.model.FindingCategory
import app.shareguard.core.model.SemanticRisk
import app.shareguard.core.model.Severity

enum class ReviewGroup(val label: String) {
    MEANING("Meaning may change"),
    LINK("Link may stop working"),
    OCR("OCR is ambiguous"),
    SOURCE_PIXELS("Source pixels would remain"),
    HIDDEN_FORMATTING("Hidden formatting was found"),
    UNKNOWN_DATA("Unknown data was found"),
    NO_AUTOMATIC_RULE("No safe automatic rule exists"),
}

data class ReviewItemUiModel(
    val id: String,
    val group: ReviewGroup,
    val title: String,
    val category: FindingCategory,
    val severity: Severity,
    val confidence: ConfidenceClass,
    val locationDescription: String,
    val surroundingContext: String?,
    val before: String?,
    val after: String?,
    val explanation: String = "",
    val evidenceSummary: String = "",
    val expertDetails: List<Pair<String, String>> = emptyList(),
    val semanticRisk: SemanticRisk,
    val allowedActions: Set<DecisionAction>,
    val selectedAction: DecisionAction? = null,
) {
    init {
        require(id.isNotBlank() && title.isNotBlank())
        require(allowedActions.isNotEmpty())
        require(selectedAction == null || selectedAction in allowedActions)
        if (semanticRisk == SemanticRisk.POSSIBLE_MEANING_CHANGE || semanticRisk == SemanticRisk.HIGH_IMPACT) {
            require(before != null || after != null)
        }
    }
}

data class CharacterReviewUiModel(
    val findingId: String,
    val glyph: String,
    val unicodeName: String,
    val codePoint: String,
    val script: String,
    val neighbors: String,
    val suggestion: String?,
    val ocrDisagreed: Boolean,
    val confusableSkeleton: String?,
    val identifierOrUrlImpact: String?,
)

data class UrlReviewUiModel(
    val findingId: String,
    val displayText: String,
    val scheme: String?,
    val host: String,
    val registrableDomain: String?,
    val subdomain: String?,
    val path: String,
    val query: String?,
    val fragment: String?,
    val userInfoPresent: Boolean,
    val highEntropyComponents: List<String>,
    val proposedResult: String,
    val functionalityImpact: String,
)

data class ImageRegionReviewUiModel(
    val findingId: String,
    val regionLabel: String,
    val boundsDescription: String,
    val selectedPolicy: DecisionAction?,
    val sourcePixelRetention: Boolean,
)
