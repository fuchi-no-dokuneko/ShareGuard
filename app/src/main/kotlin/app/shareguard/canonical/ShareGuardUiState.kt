package app.shareguard.canonical

import app.shareguard.core.model.DecisionAction
import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.InputKind
import app.shareguard.core.model.OutputMode
import app.shareguard.feature.review.ReviewItemUiModel
import app.shareguard.feature.entry.AcceptedImageSummary
import app.shareguard.feature.saved.SavedResultCardUiModel
import app.shareguard.feature.saved.SavedResultDetailUiState
import app.shareguard.feature.saved.SavedFilter
import app.shareguard.feature.saved.SavedLayout
import app.shareguard.feature.saved.SavedSort

enum class AppRoute {
    HOME,
    TEXT_INPUT,
    IMAGE_PREVIEW,
    OUTPUT_CHOICE,
    FINDING_REVIEW,
    SEMANTIC_DIFF,
    PROCESSING,
    RESULT,
    SAVED_RESULTS,
    SAVED_DETAIL,
    SAVED_SETTINGS,
    WAITING_TARGET,
    PRE_SHARE,
    DELETE_CONFIRMATION,
    RENAME_SAVED,
    ABOUT,
    ERROR,
}

data class LedgerReviewRow(
    val blockId: String,
    val before: String?,
    val after: String?,
    val reason: String,
    val semanticImpact: String,
)

data class ResultUiState(
    val savedResultId: String?,
    val canonicalText: String,
    val assuranceClass: AssuranceClass,
    val assuranceLabel: String,
    val statusLines: List<String>,
    val limitationLines: List<String>,
    val blockingChecks: List<String>,
)

data class ShareGuardUiState(
    val route: AppRoute = AppRoute.HOME,
    val text: String = "",
    val inputKind: InputKind? = null,
    val elapsedSinceImport: String? = null,
    val revealCharacters: Boolean = false,
    val selectedOutput: OutputMode = OutputMode.TEXT,
    val imageSummary: AcceptedImageSummary? = null,
    val imageOcrWarning: String? = null,
    val transientImagePreview: android.graphics.Bitmap? = null,
    val reviewItems: List<ReviewItemUiModel> = emptyList(),
    val reviewSelections: Map<String, DecisionAction> = emptyMap(),
    val canonicalPreview: String = "",
    val ledgerRows: List<LedgerReviewRow> = emptyList(),
    val result: ResultUiState? = null,
    val exactResultImagePreview: android.graphics.Bitmap? = null,
    val savedItems: List<SavedResultCardUiModel> = emptyList(),
    val savedQuery: String = "",
    val savedSort: SavedSort = SavedSort.NEWEST,
    val savedFilter: SavedFilter = SavedFilter.ALL,
    val savedLayout: SavedLayout = SavedLayout.LIST,
    val showSavedPreviews: Boolean = false,
    val protectSensitiveScreens: Boolean = true,
    val confirmShareBeforeWaitingTarget: Boolean = false,
    val waitingTargetMillis: Long? = null,
    val waitingTargetInputMinutes: String = "",
    val selectedSavedIds: Set<String> = emptySet(),
    val savedDetail: SavedResultDetailUiState? = null,
    val sortMenuExpanded: Boolean = false,
    val filterMenuExpanded: Boolean = false,
    val showImportDate: Boolean = false,
    val pendingDeletionIds: Set<String> = emptySet(),
    val pendingDeletionLabels: List<String> = emptyList(),
    val pendingDeletionExternalCopyMayExist: Boolean = false,
    val deletionInProgress: Boolean = false,
    val editingSavedId: String? = null,
    val editingSavedLabel: String = "",
    val pendingShareId: String? = null,
    val pendingShareElapsed: String? = null,
    val pendingShareTargetStatus: String? = null,
    val shareJitterEnabled: Boolean = false,
    val errorCode: String? = null,
)

sealed interface AppEffect {
    data class LaunchIntent(val intent: android.content.Intent) : AppEffect
    data class CreateExternalCopy(val intent: android.content.Intent) : AppEffect
}
