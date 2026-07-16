package app.shareguard.feature.saved

import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.IntegrityState
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.SavedResultLifecycleState
import app.shareguard.core.model.VerificationState

enum class SavedSort { NEWEST, OLDEST, NAME, SIZE }
enum class SavedLayout { LIST, GRID }
enum class SavedFilter { ALL, TEXT, IMAGE, DERIVATIVE, REVALIDATION_REQUIRED }

data class SavedResultCardUiModel(
    val id: String,
    val displayLabel: String,
    val outputMode: OutputMode,
    val assuranceClass: AssuranceClass,
    val savedAtLabel: String,
    val elapsedSinceImport: String,
    val storageByteCount: Long,
    val storageBytesLabel: String,
    val previewDescription: String?,
    val integrityState: IntegrityState,
    val verificationState: VerificationState,
    val lifecycleState: SavedResultLifecycleState,
    val favourite: Boolean,
    val canManagedShare: Boolean,
) {
    init {
        require(id.isNotBlank() && displayLabel.isNotBlank())
        require(storageByteCount >= 0L)
        if (canManagedShare) {
            require(integrityState == IntegrityState.VALID)
            require(verificationState == VerificationState.VERIFIED)
            require(lifecycleState == SavedResultLifecycleState.AVAILABLE)
        }
    }
}

data class SavedResultsUiState(
    val query: String,
    val sort: SavedSort,
    val filter: SavedFilter,
    val layout: SavedLayout,
    val storageUsageLabel: String,
    val showPreviews: Boolean,
    val items: List<SavedResultCardUiModel>,
    val selectedIds: Set<String> = emptySet(),
    val loading: Boolean = false,
    val storageErrorCode: String? = null,
) {
    init { require(selectedIds.all { id -> items.any { it.id == id } }) }
}

data class SavedResultDetailUiState(
    val item: SavedResultCardUiModel,
    val canonicalTextPreview: String?,
    val imagePreviewDescription: String?,
    val verificationSummary: List<Pair<String, String>>,
    val unresolvedLimitations: List<String>,
    val retainedSourceRegionCount: Int,
    val localImportDateLabel: String,
    val waitingTargetLabel: String?,
    val remainingToTargetLabel: String?,
    val exportedExternalCopyMayExist: Boolean,
) {
    init { require(retainedSourceRegionCount >= 0) }
}

data class SavedSettingsUiState(
    val showContentPreviews: Boolean,
    val protectSensitiveScreens: Boolean,
    val defaultLayout: SavedLayout,
    val defaultSort: SavedSort,
    val waitingTargetLabel: String?,
    val confirmShareBeforeTarget: Boolean,
    val requireDeviceAuthentication: Boolean,
    val deviceAuthenticationSupported: Boolean,
    val storageUsageLabel: String,
)
