package app.shareguard.canonical

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import app.shareguard.core.model.ArtifactKind
import app.shareguard.core.model.BoundedDelayPolicy
import app.shareguard.core.model.BoundedDelayPurpose
import app.shareguard.core.model.DecisionAction
import app.shareguard.core.model.DisplayLabel
import app.shareguard.core.model.DurationMillis
import app.shareguard.core.model.Finding
import app.shareguard.core.model.FindingCategory
import app.shareguard.core.model.ImportAnchor
import app.shareguard.core.model.ImportMethod
import app.shareguard.core.model.InputKind
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.SavedResult
import app.shareguard.core.model.SavedResultId
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SemanticRisk
import app.shareguard.core.model.WallClockInstant
import app.shareguard.core.session.SealedSourceSnapshot
import app.shareguard.core.session.PolicyBoundedRandomDelay
import app.shareguard.core.storage.ManagedShareDescriptor
import app.shareguard.core.ui.formatElapsedMillis
import app.shareguard.feature.output.ManagedShareIntentFactory
import app.shareguard.feature.output.NeutralExportName
import app.shareguard.feature.review.ReviewGroup
import app.shareguard.feature.review.ReviewItemUiModel
import app.shareguard.feature.saved.SavedFilter
import app.shareguard.feature.saved.SavedLayout
import app.shareguard.feature.saved.SavedResultCardUiModel
import app.shareguard.feature.saved.SavedResultDetailUiState
import app.shareguard.feature.saved.SavedSort
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareGuardViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ShareGuardApplication
    private val container = app.container
    private val textWorkflow = CanonicalTextWorkflow(
        context = application,
        repository = container.repository,
        cleanupEvidence = container::awaitStartupMaintenance,
    )
    private val imageImportWorkflow = LocalImageImportWorkflow()
    private val boundedRandomDelay = PolicyBoundedRandomDelay()
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Application.MODE_PRIVATE)
    private val _state = MutableStateFlow(persistentUiDefaults())
    val state: StateFlow<ShareGuardUiState> = _state.asStateFlow()
    private val _effects = MutableSharedFlow<AppEffect>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<AppEffect> = _effects

    private var importMethod: ImportMethod = ImportMethod.DIRECT_ENTRY
    private var inputKind: InputKind = InputKind.TEXT
    private var outputChoiceReturnRoute: AppRoute = AppRoute.TEXT_INPUT
    private var shareReturnRoute: AppRoute = AppRoute.SAVED_RESULTS
    private var activeSession: app.shareguard.core.session.ManagedSession? = null
    private var sourceSnapshot: SealedSourceSnapshot? = null
    private var reviewPlan: TextReviewPlan? = null
    private var approvedPlan: ApprovedTextPlan? = null
    private var allSavedResults: List<SavedResult> = emptyList()
    private var pendingExternalExport: ManagedShareDescriptor? = null
    private var shareJitterEnabled: Boolean = _state.value.shareJitterEnabled
    private var referenceTimerJob: Job? = null
    private var activeImportAnchor: ImportAnchor? = null

    fun openTextEntry(initialText: String = "", method: ImportMethod = ImportMethod.DIRECT_ENTRY) {
        clearTransientSession()
        importMethod = method
        inputKind = InputKind.TEXT
        _state.value = persistentUiDefaults().copy(
            route = AppRoute.TEXT_INPUT,
            text = initialText,
            inputKind = InputKind.TEXT,
            selectedOutput = OutputMode.TEXT,
            shareJitterEnabled = shareJitterEnabled,
        )
    }

    fun openOutputChoice() {
        outputChoiceReturnRoute = _state.value.route
        _state.value = _state.value.copy(route = AppRoute.OUTPUT_CHOICE)
    }

    fun chooseOutput(outputMode: OutputMode) {
        if (outputMode == OutputMode.DERIVATIVE_IMAGE) return
        _state.value = _state.value.copy(selectedOutput = outputMode)
    }

    fun finishOutputChoice() {
        _state.value = _state.value.copy(route = outputChoiceReturnRoute)
    }

    fun updateText(value: String) {
        if (_state.value.route == AppRoute.TEXT_INPUT) _state.value = _state.value.copy(text = value)
    }

    fun importImage(uri: Uri, method: ImportMethod = ImportMethod.PHOTO_PICKER) {
        clearTransientSession()
        importMethod = method
        inputKind = InputKind.IMAGE
        _state.value = persistentUiDefaults().copy(
            route = AppRoute.PROCESSING,
            inputKind = InputKind.IMAGE,
            selectedOutput = OutputMode.REBUILT_IMAGE,
            shareJitterEnabled = shareJitterEnabled,
        )
        viewModelScope.launch {
            runCatching {
                val session = container.sessionWorkspaceManager.startSession().session
                activeSession = session
                val resolver = getApplication<Application>().contentResolver
                val stream = resolver.openInputStream(uri) ?: error("IMAGE_SOURCE_UNAVAILABLE")
                val snapshot = session.snapshots.sealAcceptedProviderSource(
                    stream,
                    BoundedDelayPolicy(
                        enabled = false,
                        purpose = BoundedDelayPurpose.PROVIDER_SNAPSHOT_RECHECK,
                        minimum = DurationMillis(0),
                        maximum = DurationMillis(0),
                        validationReference = SafeSummary("provider-copy-is-authoritative-v1"),
                    ),
                )
                sourceSnapshot = snapshot
                startReferenceTimer(snapshot.descriptor.importAnchor)
                val bytes = snapshot.readVerified()
                try {
                    imageImportWorkflow.inspect(bytes, resolver.getType(uri))
                } finally {
                    bytes.fill(0)
                }
            }.onSuccess { inspection ->
                _state.value = _state.value.copy(
                    route = AppRoute.IMAGE_PREVIEW,
                    imageSummary = inspection.summary,
                    text = inspection.provisionalOcrText,
                    imageOcrWarning = if (inspection.ocrViewsAgreed) {
                        "OCR is local and provisional. Review every character and reading-order line."
                    } else {
                        "Local OCR views disagreed. Correct every character and reading-order line before continuing."
                    },
                    transientImagePreview = inspection.transientPreview,
                )
            }.onFailure { failure ->
                activeSession?.lifecycle?.failFatal()
                activeSession = null
                sourceSnapshot = null
                showError(failure)
            }
        }
    }

    fun continueImageReview() {
        if (_state.value.route != AppRoute.IMAGE_PREVIEW) return
        _state.value = _state.value.copy(route = AppRoute.TEXT_INPUT)
    }

    fun backFromTextInput() {
        if (inputKind == InputKind.IMAGE && _state.value.imageSummary != null) {
            _state.value = _state.value.copy(route = AppRoute.IMAGE_PREVIEW)
        } else {
            goHome()
        }
    }

    fun toggleReveal(value: Boolean) {
        _state.value = _state.value.copy(revealCharacters = value)
    }

    fun submitText() {
        val text = _state.value.text
        if (text.isEmpty() || _state.value.route != AppRoute.TEXT_INPUT) return
        _state.value = _state.value.copy(route = AppRoute.PROCESSING, errorCode = null)
        viewModelScope.launch {
            runCatching {
                if (inputKind == InputKind.TEXT) {
                    discardActiveSession()
                    val start = container.sessionWorkspaceManager.startSession()
                    activeSession = start.session
                    sourceSnapshot = if (importMethod == ImportMethod.ANDROID_SHARE) {
                        start.session.snapshots.sealAcceptedSharedText(text)
                    } else {
                        start.session.snapshots.sealAcceptedDirectText(text)
                    }
                    startReferenceTimer(requireNotNull(sourceSnapshot).descriptor.importAnchor)
                } else {
                    checkNotNull(activeSession) { "IMAGE_SESSION_MISSING" }
                    checkNotNull(sourceSnapshot) { "IMAGE_SNAPSHOT_MISSING" }
                }
                val plan = withContext(Dispatchers.Default) { textWorkflow.inspect(text, inputKind) }
                reviewPlan = plan
                val reviewItems = plan.toReviewItems()
                if (plan.requiresReview) {
                    require(reviewItems.isNotEmpty()) { "UNRESOLVED_REVIEW_REQUIRES_MANUAL_EDIT" }
                    _state.value = _state.value.copy(
                        route = AppRoute.FINDING_REVIEW,
                        reviewItems = reviewItems,
                        reviewSelections = emptyMap(),
                    )
                } else {
                    openSemanticDiff(textWorkflow.approve(plan, allReviewItemsApproved = false))
                }
            }.onFailure(::showError)
        }
    }

    fun chooseReviewAction(findingId: String, action: DecisionAction) {
        val current = _state.value
        if (current.reviewItems.none { it.id == findingId && action in it.allowedActions }) return
        val selections = current.reviewSelections + (findingId to action)
        _state.value = current.copy(
            reviewSelections = selections,
            reviewItems = current.reviewItems.map {
                if (it.id == findingId) it.copy(selectedAction = action) else it
            },
        )
    }

    fun applyReviewDecisions() {
        val current = _state.value
        if (current.reviewItems.any { it.selectedAction == null }) return
        val plan = reviewPlan ?: return showError(IllegalStateException("REVIEW_PLAN_MISSING"))
        runCatching { textWorkflow.approve(plan, allReviewItemsApproved = true) }
            .onSuccess(::openSemanticDiff)
            .onFailure(::showError)
    }

    fun editSourceFromReview() {
        reviewPlan = null
        approvedPlan = null
        _state.value = _state.value.copy(
            route = AppRoute.TEXT_INPUT,
            reviewItems = emptyList(),
            reviewSelections = emptyMap(),
        )
    }

    private fun openSemanticDiff(plan: ApprovedTextPlan) {
        approvedPlan = plan
        val entries = plan.textResult.ledgerEntries + plan.urlResult.canonicalizations.flatMap { it.ledgerEntries } +
            plan.supplementalLedgerEntries
        _state.value = _state.value.copy(
            route = AppRoute.SEMANTIC_DIFF,
            canonicalPreview = plan.canonicalText,
            ledgerRows = entries.map { entry ->
                LedgerReviewRow(
                    blockId = entry.blockId.value,
                    before = entry.beforeRepresentation?.value,
                    after = entry.afterRepresentation?.value,
                    reason = entry.reason.value,
                    semanticImpact = entry.semanticImpact.name.lowercase().replace('_', ' '),
                )
            },
        )
    }

    fun verifyAndSave() {
        val session = activeSession ?: return showError(IllegalStateException("SESSION_MISSING"))
        val snapshot = sourceSnapshot ?: return showError(IllegalStateException("SOURCE_SNAPSHOT_MISSING"))
        val plan = approvedPlan ?: return showError(IllegalStateException("APPROVED_PLAN_MISSING"))
        _state.value = _state.value.copy(route = AppRoute.PROCESSING)
        viewModelScope.launch {
            runCatching {
                textWorkflow.verifyAndPersist(
                    session = session,
                    sourceHandle = snapshot.descriptor.sourceHandle,
                    importAnchor = snapshot.descriptor.importAnchor,
                    plan = plan,
                    displayLabel = DisplayLabel("Text result"),
                    semanticDiffApproved = true,
                    assuranceConsequenceApproved = true,
                    inputKind = inputKind,
                    outputMode = _state.value.selectedOutput,
                )
            }.onSuccess { completion ->
                val exactImagePreview = completion.exactImagePreviewBytes?.let { encoded ->
                    try {
                        BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
                    } finally {
                        encoded.fill(0)
                    }
                }
                session.lifecycle.complete()
                activeSession = null
                sourceSnapshot = null
                reviewPlan = null
                approvedPlan = null
                clearTransientPreview()
                _state.value = _state.value.copy(
                    route = AppRoute.RESULT,
                    text = "",
                    imageSummary = null,
                    imageOcrWarning = null,
                    transientImagePreview = null,
                    exactResultImagePreview = exactImagePreview,
                    canonicalPreview = "",
                    ledgerRows = emptyList(),
                    result = ResultUiState(
                        savedResultId = completion.persisted?.savedResult?.savedResultId?.value,
                        canonicalText = completion.canonicalText,
                        assuranceClass = completion.verification.report.assuranceClass,
                        assuranceLabel = completion.verification.humanReadableReport.assuranceLabel,
                        statusLines = completion.verification.humanReadableReport.statusLines,
                        limitationLines = completion.verification.humanReadableReport.limitationLines,
                        blockingChecks = completion.verification.blockingVerificationTypes.map { it.name },
                    ),
                )
            }.onFailure { failure ->
                session.lifecycle.failFatal()
                activeSession = null
                sourceSnapshot = null
                clearTransientPreview()
                showError(failure)
            }
        }
    }

    fun shareCurrentResult() {
        _state.value.result?.savedResultId?.let(::shareSavedResult)
    }

    fun openSavedResults() {
        clearTransientSession()
        clearResultPreview()
        _state.value = _state.value.copy(
            route = AppRoute.SAVED_RESULTS,
            savedDetail = null,
            errorCode = null,
        )
        startReferenceTimer(null)
        refreshSavedResults()
    }

    fun refreshSavedResults() {
        viewModelScope.launch {
            runCatching { container.repository.listVisible() }
                .onSuccess {
                    allSavedResults = it
                    applySavedView()
                }
                .onFailure(::showError)
        }
    }

    fun updateSavedQuery(query: String) {
        _state.value = _state.value.copy(savedQuery = query)
        applySavedView()
    }

    fun chooseSavedSort(sort: SavedSort) {
        preferences.edit { putString(KEY_SAVED_SORT, sort.name) }
        _state.value = _state.value.copy(savedSort = sort, sortMenuExpanded = false)
        applySavedView()
    }

    fun chooseSavedFilter(filter: SavedFilter) {
        _state.value = _state.value.copy(savedFilter = filter, filterMenuExpanded = false)
        applySavedView()
    }

    fun toggleSavedLayout() {
        val updated = if (_state.value.savedLayout == SavedLayout.LIST) SavedLayout.GRID else SavedLayout.LIST
        preferences.edit { putString(KEY_SAVED_LAYOUT, updated.name) }
        _state.value = _state.value.copy(
            savedLayout = updated,
        )
    }

    fun toggleSavedSelection(id: String) {
        val selected = _state.value.selectedSavedIds.toMutableSet()
        if (!selected.add(id)) selected.remove(id)
        _state.value = _state.value.copy(selectedSavedIds = selected)
    }

    fun requestDeleteSelectedSavedResults() {
        val ids = _state.value.selectedSavedIds.map(::SavedResultId)
        if (ids.isEmpty()) return
        openDeleteConfirmation(ids)
    }

    fun openSavedSettings() {
        _state.value = _state.value.copy(
            route = AppRoute.SAVED_SETTINGS,
            savedItems = allSavedResults.map { it.toCardUi() },
        )
    }

    fun setShowSavedPreviews(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_SHOW_PREVIEWS, enabled) }
        _state.value = _state.value.copy(showSavedPreviews = enabled)
    }

    fun setProtectSensitiveScreens(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_PROTECT_SCREENS, enabled) }
        _state.value = _state.value.copy(protectSensitiveScreens = enabled)
    }

    fun setSavedLayout(layout: SavedLayout) {
        preferences.edit { putString(KEY_SAVED_LAYOUT, layout.name) }
        _state.value = _state.value.copy(savedLayout = layout)
    }

    fun setConfirmShareBeforeWaitingTarget(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_CONFIRM_BEFORE_TARGET, enabled) }
        _state.value = _state.value.copy(confirmShareBeforeWaitingTarget = enabled)
    }

    fun openWaitingTargetEditor() {
        _state.value = _state.value.copy(
            route = AppRoute.WAITING_TARGET,
            waitingTargetInputMinutes = _state.value.waitingTargetMillis?.div(60_000L)?.toString().orEmpty(),
        )
    }

    fun updateWaitingTargetMinutes(value: String) {
        if (value.length <= 12 && value.all(Char::isDigit)) {
            _state.value = _state.value.copy(waitingTargetInputMinutes = value)
        }
    }

    fun saveWaitingTarget() {
        val input = _state.value.waitingTargetInputMinutes
        val targetMillis = if (input.isBlank()) {
            null
        } else {
            val minutes = input.toLongOrNull()?.takeIf { it > 0L }
                ?: return showError(IllegalArgumentException("INVALID_WAITING_TARGET"))
            runCatching { Math.multiplyExact(minutes, 60_000L) }.getOrElse {
                return showError(IllegalArgumentException("INVALID_WAITING_TARGET"))
            }
        }
        preferences.edit { putLong(KEY_WAITING_TARGET_MILLIS, targetMillis ?: 0L) }
        _state.value = _state.value.copy(
            route = AppRoute.SAVED_SETTINGS,
            waitingTargetMillis = targetMillis,
            waitingTargetInputMinutes = "",
        )
    }

    fun requestDeleteAllSavedResults() {
        if (allSavedResults.isNotEmpty()) {
            openDeleteConfirmation(allSavedResults.map { it.savedResultId })
        }
    }

    fun confirmDeletion() {
        val ids = _state.value.pendingDeletionIds.map(::SavedResultId)
        if (ids.isEmpty() || _state.value.deletionInProgress) return
        _state.value = _state.value.copy(deletionInProgress = true)
        viewModelScope.launch {
            runCatching { container.deletionService.deleteBulk(ids) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        selectedSavedIds = emptySet(),
                        pendingDeletionIds = emptySet(),
                        deletionInProgress = false,
                    )
                    openSavedResults()
                }
                .onFailure(::showError)
        }
    }

    fun cancelDeletion() {
        val returnToDetail = _state.value.pendingDeletionIds.size == 1 && _state.value.savedDetail != null
        _state.value = _state.value.copy(
            route = if (returnToDetail) AppRoute.SAVED_DETAIL else AppRoute.SAVED_RESULTS,
            pendingDeletionIds = emptySet(),
            pendingDeletionLabels = emptyList(),
            pendingDeletionExternalCopyMayExist = false,
            deletionInProgress = false,
        )
    }

    fun openSavedDetail(id: String) {
        val item = allSavedResults.singleOrNull { it.savedResultId.value == id } ?: return
        clearResultPreview()
        _state.value = _state.value.copy(
            route = AppRoute.SAVED_DETAIL,
            showImportDate = false,
            savedDetail = item.toDetailUi(),
        )
        viewModelScope.launch {
            runCatching { loadSavedDetailContent(item) }
                .onSuccess { (textPreview, imagePreview) ->
                    if (_state.value.route == AppRoute.SAVED_DETAIL && _state.value.savedDetail?.item?.id == id) {
                        _state.value = _state.value.copy(
                            savedDetail = _state.value.savedDetail?.copy(canonicalTextPreview = textPreview),
                            exactResultImagePreview = imagePreview,
                        )
                    } else {
                        imagePreview?.recycle()
                    }
                }
                .onFailure {
                    val detail = _state.value.savedDetail
                    if (_state.value.route == AppRoute.SAVED_DETAIL && detail?.item?.id == id) {
                        _state.value = _state.value.copy(
                            savedDetail = detail.copy(
                                unresolvedLimitations = detail.unresolvedLimitations +
                                    "The local preview could not be reopened; share/export remains gated by revalidation.",
                            ),
                        )
                    }
                }
        }
    }

    fun openRenameSavedResult(id: String) {
        val result = allSavedResults.singleOrNull { it.savedResultId.value == id } ?: return
        _state.value = _state.value.copy(
            route = AppRoute.RENAME_SAVED,
            editingSavedId = id,
            editingSavedLabel = result.displayLabel.value,
        )
    }

    fun updateSavedLabel(value: String) {
        if (_state.value.route == AppRoute.RENAME_SAVED && value.length <= 120) {
            _state.value = _state.value.copy(editingSavedLabel = value)
        }
    }

    fun confirmRenameSavedResult() {
        val id = _state.value.editingSavedId ?: return
        val label = runCatching { DisplayLabel(_state.value.editingSavedLabel) }.getOrElse {
            return showError(IllegalArgumentException("INVALID_DISPLAY_LABEL"))
        }
        val current = allSavedResults.singleOrNull { it.savedResultId.value == id } ?: return
        viewModelScope.launch {
            runCatching {
                container.repository.renameAndFavourite(current.savedResultId, label, current.favourite)
            }.onSuccess { updated ->
                replaceSavedResult(updated)
                openSavedDetail(id)
            }.onFailure(::showError)
        }
    }

    fun toggleSavedFavourite(id: String) {
        val current = allSavedResults.singleOrNull { it.savedResultId.value == id } ?: return
        viewModelScope.launch {
            runCatching {
                container.repository.renameAndFavourite(
                    current.savedResultId,
                    current.displayLabel,
                    !current.favourite,
                )
            }.onSuccess { updated ->
                replaceSavedResult(updated)
                openSavedDetail(id)
            }.onFailure(::showError)
        }
    }

    private fun replaceSavedResult(updated: SavedResult) {
        allSavedResults = allSavedResults.map { existing ->
            if (existing.savedResultId == updated.savedResultId) updated else existing
        }
    }

    private suspend fun loadSavedDetailContent(result: SavedResult): Pair<String?, android.graphics.Bitmap?> {
        if (!result.canManagedShare) return null to null
        val prepared = mutableListOf<ManagedShareDescriptor>()
        return try {
            val text = if (result.outputMode in setOf(OutputMode.TEXT, OutputMode.BOTH)) {
                val descriptor = container.managedShareCache.prepare(
                    result.savedResultId,
                    ArtifactKind.CANONICAL_TEXT,
                ).also(prepared::add)
                val encoded = container.managedShareCache.openReadOnly(descriptor.cacheToken).use { it.readBytes() }
                try {
                    encoded.toString(Charsets.UTF_8)
                } finally {
                    encoded.fill(0)
                }
            } else {
                null
            }
            val image = if (result.outputMode in setOf(OutputMode.REBUILT_IMAGE, OutputMode.BOTH)) {
                val descriptor = container.managedShareCache.prepare(
                    result.savedResultId,
                    ArtifactKind.REBUILT_IMAGE,
                ).also(prepared::add)
                val encoded = container.managedShareCache.openReadOnly(descriptor.cacheToken).use { it.readBytes() }
                try {
                    BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
                        ?: error("SAVED_IMAGE_PREVIEW_DECODE_FAILED")
                } finally {
                    encoded.fill(0)
                }
            } else {
                null
            }
            text to image
        } finally {
            prepared.forEach { container.managedShareCache.clear(it.cacheToken) }
        }
    }

    fun shareSavedResult(id: String) {
        val result = allSavedResults.singleOrNull { it.savedResultId.value == id }
        viewModelScope.launch {
            val authoritative = result ?: container.repository.findVisible(SavedResultId(id))
            if (authoritative == null || !authoritative.canManagedShare) {
                showError(IllegalStateException("SAVED_RESULT_NOT_AVAILABLE"))
                return@launch
            }
            if (allSavedResults.none { it.savedResultId == authoritative.savedResultId }) {
                allSavedResults = allSavedResults + authoritative
            }
            shareReturnRoute = _state.value.route
            _state.value = _state.value.copy(
                route = AppRoute.PRE_SHARE,
                pendingShareId = id,
                pendingShareElapsed = formatElapsedMillis(
                    authoritative.importAnchor.elapsedMillis(
                        WallClockInstant(System.currentTimeMillis().coerceAtLeast(0)),
                    ),
                ),
                pendingShareTargetStatus = waitingTargetStatus(
                    authoritative,
                    WallClockInstant(System.currentTimeMillis().coerceAtLeast(0)),
                ),
            )
        }
    }

    fun confirmManagedShare() {
        val id = _state.value.pendingShareId ?: return
        _state.value = _state.value.copy(
            route = shareReturnRoute,
            pendingShareId = null,
            pendingShareElapsed = null,
            pendingShareTargetStatus = null,
        )
        launchSavedResultShare(id)
    }

    fun cancelManagedShare() {
        _state.value = _state.value.copy(
            route = shareReturnRoute,
            pendingShareId = null,
            pendingShareElapsed = null,
            pendingShareTargetStatus = null,
        )
    }

    private fun launchSavedResultShare(id: String) {
        viewModelScope.launch {
            runCatching {
                val savedId = SavedResultId(id)
                val result = container.repository.findVisible(savedId)
                    ?: error("SAVED_RESULT_NOT_AVAILABLE")
                val send = if (result.outputMode == OutputMode.BOTH) {
                    val textDescriptor = container.managedShareCache.prepare(savedId, ArtifactKind.CANONICAL_TEXT)
                    val imageDescriptor = container.managedShareCache.prepare(savedId, ArtifactKind.REBUILT_IMAGE)
                    val text = readManagedText(textDescriptor)
                    val uri = imageDescriptor.contentUri()
                    ManagedShareIntentFactory.textAndImage(text, uri, imageDescriptor.mimeType.value)
                } else {
                    val descriptor = container.managedShareCache.prepare(savedId, result.preferredSingleArtifactKind())
                    if (descriptor.artifactKind == ArtifactKind.CANONICAL_TEXT) {
                        val text = readManagedText(descriptor)
                        ManagedShareIntentFactory.canonicalText(text)
                    } else {
                        ManagedShareIntentFactory.image(descriptor.contentUri(), descriptor.mimeType.value)
                    }
                }
                awaitOptionalShareJitter()
                ManagedShareIntentFactory.chooser(send)
            }.onSuccess { _effects.emit(AppEffect.LaunchIntent(it)) }
                .onFailure(::showError)
        }
    }

    fun requestExternalExport(id: String, artifactKind: ArtifactKind) {
        viewModelScope.launch {
            runCatching {
                pendingExternalExport?.let { container.managedShareCache.clear(it.cacheToken) }
                pendingExternalExport = null
                val savedId = SavedResultId(id)
                val result = container.repository.findVisible(savedId)
                    ?: error("SAVED_RESULT_NOT_AVAILABLE")
                val allowedKinds = when (result.outputMode) {
                    OutputMode.TEXT -> setOf(ArtifactKind.CANONICAL_TEXT)
                    OutputMode.REBUILT_IMAGE -> setOf(ArtifactKind.REBUILT_IMAGE)
                    OutputMode.BOTH -> setOf(ArtifactKind.CANONICAL_TEXT, ArtifactKind.REBUILT_IMAGE)
                    OutputMode.DERIVATIVE_IMAGE -> setOf(ArtifactKind.DERIVATIVE_IMAGE)
                }
                require(artifactKind in allowedKinds) { "EXPORT_ARTIFACT_KIND_NOT_AVAILABLE" }
                val descriptor = container.managedShareCache.prepare(savedId, artifactKind)
                pendingExternalExport = descriptor
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = descriptor.mimeType.value
                    putExtra(
                        Intent.EXTRA_TITLE,
                        NeutralExportName.create(id, descriptor.exportExtension()),
                    )
                }
            }.onSuccess { _effects.emit(AppEffect.CreateExternalCopy(it)) }
                .onFailure(::showError)
        }
    }

    fun completeExternalExport(destination: Uri?) {
        val descriptor = pendingExternalExport ?: return
        pendingExternalExport = null
        viewModelScope.launch {
            if (destination == null) {
                container.managedShareCache.clear(descriptor.cacheToken)
                return@launch
            }
            runCatching {
                // Once a destination is granted, even a later write error may leave a partial external copy.
                val exportNoted = container.repository.noteExternalExport(descriptor.savedResultId)
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val parcel = resolver.openFileDescriptor(destination, "w")
                        ?: error("EXPORT_DESTINATION_UNAVAILABLE")
                    parcel.use { destinationFile ->
                        container.managedShareCache.openReadOnly(descriptor.cacheToken).use { input ->
                            FileOutputStream(destinationFile.fileDescriptor).use { output ->
                                val copied = input.copyTo(output)
                                output.flush()
                                destinationFile.fileDescriptor.sync()
                                check(copied == descriptor.byteCount.value) { "EXTERNAL_EXPORT_LENGTH_MISMATCH" }
                            }
                        }
                    }
                }
                exportNoted
            }.onSuccess { updated ->
                allSavedResults = allSavedResults.map { existing ->
                    if (existing.savedResultId == updated.savedResultId) updated else existing
                }
                _state.value = _state.value.copy(savedDetail = updated.toDetailUi())
            }.onFailure(::showError)
            container.managedShareCache.clear(descriptor.cacheToken)
        }
    }

    fun requestDeleteSavedResult(id: String) {
        openDeleteConfirmation(listOf(SavedResultId(id)))
    }

    private fun openDeleteConfirmation(ids: List<SavedResultId>) {
        val records = allSavedResults.filter { it.savedResultId in ids }
        if (records.size != ids.size) return
        _state.value = _state.value.copy(
            route = AppRoute.DELETE_CONFIRMATION,
            pendingDeletionIds = ids.map { it.value }.toSet(),
            pendingDeletionLabels = records.map { it.displayLabel.value },
            pendingDeletionExternalCopyMayExist = records.any { it.artifactManifest.externalExportKnown },
            deletionInProgress = false,
        )
    }

    fun revalidateSavedResult(id: String) {
        viewModelScope.launch {
            runCatching { container.revalidator.revalidate(SavedResultId(id)) }
                .onSuccess { openSavedDetail(id) }
                .onFailure(::showError)
        }
    }

    fun goHome() {
        clearTransientSession()
        clearResultPreview()
        _state.value = persistentUiDefaults()
    }

    fun navigateBack() {
        when (_state.value.route) {
            AppRoute.OUTPUT_CHOICE -> finishOutputChoice()
            AppRoute.TEXT_INPUT -> backFromTextInput()
            AppRoute.SAVED_DETAIL -> openSavedResults()
            AppRoute.SAVED_SETTINGS -> openSavedResults()
            AppRoute.WAITING_TARGET -> _state.value = _state.value.copy(route = AppRoute.SAVED_SETTINGS)
            AppRoute.DELETE_CONFIRMATION -> cancelDeletion()
            AppRoute.RENAME_SAVED -> _state.value = _state.value.copy(route = AppRoute.SAVED_DETAIL)
            AppRoute.PRE_SHARE -> cancelManagedShare()
            else -> goHome()
        }
    }

    fun openAbout() {
        _state.value = _state.value.copy(route = AppRoute.ABOUT)
    }

    fun toggleShareJitter(enabled: Boolean) {
        shareJitterEnabled = enabled
        preferences.edit { putBoolean(KEY_SHARE_JITTER, enabled) }
        _state.value = _state.value.copy(shareJitterEnabled = enabled)
    }

    fun toggleSortMenu() {
        _state.value = _state.value.copy(sortMenuExpanded = !_state.value.sortMenuExpanded)
    }

    fun toggleFilterMenu() {
        _state.value = _state.value.copy(filterMenuExpanded = !_state.value.filterMenuExpanded)
    }

    fun toggleImportDate() {
        _state.value = _state.value.copy(showImportDate = !_state.value.showImportDate)
    }

    fun consumeIncomingIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val sharedImage = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (sharedText.isNotEmpty() && sharedImage != null) {
            showError(IllegalArgumentException("MULTIPLE_SOURCE_KINDS_REQUIRE_EXPLICIT_CHOICE"))
        } else if (intent.type?.startsWith("text/") == true) {
            if (sharedText.isNotEmpty()) openTextEntry(sharedText, ImportMethod.ANDROID_SHARE)
        } else if (intent.type?.startsWith("image/") == true && sharedImage != null) {
            importImage(sharedImage, ImportMethod.ANDROID_SHARE)
        }
    }

    private fun applySavedView() {
        val state = _state.value
        var results = allSavedResults.filter {
            state.savedQuery.isBlank() || it.displayLabel.value.contains(state.savedQuery, ignoreCase = true)
        }.filter {
            when (state.savedFilter) {
                SavedFilter.ALL -> true
                SavedFilter.TEXT -> it.outputMode == OutputMode.TEXT
                SavedFilter.IMAGE -> it.outputMode in setOf(OutputMode.REBUILT_IMAGE, OutputMode.BOTH)
                SavedFilter.DERIVATIVE -> it.outputMode == OutputMode.DERIVATIVE_IMAGE
                SavedFilter.REVALIDATION_REQUIRED -> !it.canManagedShare
            }
        }
        results = when (state.savedSort) {
            SavedSort.NEWEST -> results.sortedByDescending { it.persistedAtWallClock.epochMillis }
            SavedSort.OLDEST -> results.sortedBy { it.persistedAtWallClock.epochMillis }
            SavedSort.NAME -> results.sortedBy { it.displayLabel.value.lowercase() }
            SavedSort.SIZE -> results.sortedByDescending { it.storageByteCount.value }
        }
        _state.value = state.copy(savedItems = results.map { it.toCardUi() })
    }

    private fun SavedResult.toCardUi(): SavedResultCardUiModel = SavedResultCardUiModel(
        id = savedResultId.value,
        displayLabel = displayLabel.value,
        outputMode = outputMode,
        assuranceClass = assuranceClass,
        savedAtLabel = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(persistedAtWallClock.epochMillis)),
        elapsedSinceImport = formatElapsedMillis(
            importAnchor.elapsedMillis(WallClockInstant(System.currentTimeMillis().coerceAtLeast(0))),
        ),
        storageByteCount = storageByteCount.value,
        storageBytesLabel = formatBytes(storageByteCount.value),
        previewDescription = null,
        integrityState = artifactManifest.integrityState,
        verificationState = verificationState,
        lifecycleState = lifecycleState,
        favourite = favourite,
        canManagedShare = canManagedShare,
    )

    private fun SavedResult.toDetailUi(): SavedResultDetailUiState {
        val elapsedMillis = importAnchor.elapsedMillis(
            WallClockInstant(System.currentTimeMillis().coerceAtLeast(0)),
        )
        val waitingTarget = _state.value.waitingTargetMillis
        return SavedResultDetailUiState(
            item = toCardUi(),
            canonicalTextPreview = null,
            imagePreviewDescription = null,
            verificationSummary = verificationSummary?.resultStatuses?.map { it.type.name to it.status.name }.orEmpty(),
            unresolvedLimitations = buildList {
                if (!canManagedShare) add("This result must pass revalidation before managed sharing.")
                if (artifactManifest.externalExportKnown) add("An external export was previously requested.")
            },
            retainedSourceRegionCount = artifactManifest.sourcePixelDependencySummary.retainedSourcePixelRegions.size,
            localImportDateLabel = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG)
                .format(Date(importAnchor.wallClock.epochMillis)),
            waitingTargetLabel = waitingTarget?.let { formatElapsedMillis(it) },
            remainingToTargetLabel = waitingTarget
                ?.minus(elapsedMillis)
                ?.coerceAtLeast(0L)
                ?.takeIf { it > 0L }
                ?.let(::formatElapsedMillis),
            exportedExternalCopyMayExist = artifactManifest.externalExportKnown,
        )
    }

    private fun startReferenceTimer(anchor: ImportAnchor?) {
        activeImportAnchor = anchor
        referenceTimerJob?.cancel()
        refreshReferenceDisplays()
        referenceTimerJob = viewModelScope.launch {
            while (true) {
                delay(60_000L)
                refreshReferenceDisplays()
            }
        }
    }

    private fun refreshReferenceDisplays() {
        val now = WallClockInstant(System.currentTimeMillis().coerceAtLeast(0))
        val activeLabel = activeImportAnchor?.let { formatElapsedMillis(it.elapsedMillis(now)) }
        val current = _state.value
        val refreshedItems = current.savedItems.map { card ->
            val result = allSavedResults.firstOrNull { it.savedResultId.value == card.id }
            if (result == null) card else card.copy(
                elapsedSinceImport = formatElapsedMillis(result.importAnchor.elapsedMillis(now)),
            )
        }
        val refreshedDetail = current.savedDetail?.let { detail ->
            val result = allSavedResults.firstOrNull { it.savedResultId.value == detail.item.id }
            if (result == null) detail else detail.copy(
                item = detail.item.copy(
                    elapsedSinceImport = formatElapsedMillis(result.importAnchor.elapsedMillis(now)),
                ),
            )
        }
        val pendingShareElapsed = current.pendingShareId?.let { id ->
            allSavedResults.firstOrNull { it.savedResultId.value == id }?.let { result ->
                formatElapsedMillis(result.importAnchor.elapsedMillis(now))
            }
        }
        val pendingShareTargetStatus = current.pendingShareId?.let { id ->
            allSavedResults.firstOrNull { it.savedResultId.value == id }?.let { result ->
                waitingTargetStatus(result, now)
            }
        }
        _state.value = current.copy(
            elapsedSinceImport = activeLabel,
            savedItems = refreshedItems,
            savedDetail = refreshedDetail,
            pendingShareElapsed = pendingShareElapsed,
            pendingShareTargetStatus = pendingShareTargetStatus,
        )
    }

    private fun waitingTargetStatus(result: SavedResult, now: WallClockInstant): String? {
        val target = _state.value.waitingTargetMillis ?: return null
        val remaining = (target - result.importAnchor.elapsedMillis(now)).coerceAtLeast(0L)
        return if (remaining == 0L) {
            "Your user-defined waiting target has been reached. This does not change assurance."
        } else {
            "${formatElapsedMillis(remaining)} remains to your user-defined waiting target. You may share now."
        }
    }

    private fun TextReviewPlan.toReviewItems(): List<ReviewItemUiModel> {
        val source = sourceText
        return (textResult.findings + urlResult.analysisBatch.findings + listOfNotNull(ocrReviewFinding))
            .filter { it.requiresUserDecision || it.semanticRisk != SemanticRisk.NONE }
            .distinctBy { it.findingId }
            .map { finding -> finding.toReviewItem(source) }
    }

    private fun Finding.toReviewItem(source: String): ReviewItemUiModel {
        val range = sourceLocation?.let { location ->
            val start = location.scalarStart ?: return@let null
            val end = location.scalarEndExclusive ?: return@let null
            runCatching {
                val utfStart = source.offsetByCodePoints(0, start.coerceAtMost(source.codePointCount(0, source.length)))
                val utfEnd = source.offsetByCodePoints(0, end.coerceAtMost(source.codePointCount(0, source.length)))
                source.substring(utfStart, utfEnd)
            }.getOrNull()
        }
        return ReviewItemUiModel(
            id = findingId.value,
            group = when {
                category == FindingCategory.URL -> ReviewGroup.LINK
                category == FindingCategory.CONFUSABLE -> ReviewGroup.MEANING
                category == FindingCategory.UNKNOWN -> ReviewGroup.UNKNOWN_DATA
                else -> ReviewGroup.HIDDEN_FORMATTING
            },
            title = title.value,
            category = category,
            severity = severity,
            confidence = confidenceClass,
            locationDescription = sourceLocation?.safeDescription?.value ?: "Canonical document",
            surroundingContext = range,
            before = range ?: if (semanticRisk != SemanticRisk.NONE) "Affected source span" else null,
            after = if (semanticRisk != SemanticRisk.NONE) "See the complete semantic diff before verification" else null,
            semanticRisk = semanticRisk,
            allowedActions = setOf(suggestedAction ?: DecisionAction.ACCEPT_PROPOSED_CHANGE),
        )
    }

    private fun SavedResult.preferredSingleArtifactKind(): ArtifactKind = when (outputMode) {
        OutputMode.TEXT, OutputMode.BOTH -> ArtifactKind.CANONICAL_TEXT
        OutputMode.REBUILT_IMAGE -> ArtifactKind.REBUILT_IMAGE
        OutputMode.DERIVATIVE_IMAGE -> ArtifactKind.DERIVATIVE_IMAGE
    }

    private fun ManagedShareDescriptor.exportExtension(): String = when (mimeType.value) {
        "text/plain" -> "txt"
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> if (artifactKind == ArtifactKind.CANONICAL_TEXT) "txt" else "bin"
    }

    private fun ManagedShareDescriptor.contentUri(): Uri = FileProvider.getUriForFile(
        getApplication(),
        "${getApplication<Application>().packageName}.managed-share",
        container.managedShareCache.requirePreparedFile(this),
    )

    private suspend fun readManagedText(descriptor: ManagedShareDescriptor): String {
        val encoded = container.managedShareCache.openReadOnly(descriptor.cacheToken).use { it.readBytes() }
        return try {
            encoded.toString(Charsets.UTF_8)
        } finally {
            encoded.fill(0)
        }
    }

    private suspend fun awaitOptionalShareJitter() {
        boundedRandomDelay.await(
            BoundedDelayPolicy(
                enabled = shareJitterEnabled,
                purpose = BoundedDelayPurpose.OPTIONAL_PRE_SHARE_JITTER,
                minimum = DurationMillis(100),
                maximum = DurationMillis(500),
                validationReference = SafeSummary("ui-share-jitter-envelope-v1"),
            ),
        )
    }

    private fun formatBytes(value: Long): String = when {
        value >= 1024L * 1024L -> "%.1f MiB".format(value / (1024.0 * 1024.0))
        value >= 1024L -> "%.1f KiB".format(value / 1024.0)
        else -> "$value B"
    }

    private fun persistentUiDefaults(): ShareGuardUiState = ShareGuardUiState(
        savedSort = runCatching {
            SavedSort.valueOf(preferences.getString(KEY_SAVED_SORT, null).orEmpty())
        }.getOrDefault(SavedSort.NEWEST),
        savedLayout = runCatching {
            SavedLayout.valueOf(preferences.getString(KEY_SAVED_LAYOUT, null).orEmpty())
        }.getOrDefault(SavedLayout.LIST),
        showSavedPreviews = preferences.getBoolean(KEY_SHOW_PREVIEWS, false),
        protectSensitiveScreens = preferences.getBoolean(KEY_PROTECT_SCREENS, true),
        confirmShareBeforeWaitingTarget = preferences.getBoolean(KEY_CONFIRM_BEFORE_TARGET, false),
        waitingTargetMillis = preferences.getLong(KEY_WAITING_TARGET_MILLIS, 0L).takeIf { it > 0L },
        shareJitterEnabled = preferences.getBoolean(KEY_SHARE_JITTER, false),
    )

    private fun showError(failure: Throwable) {
        _state.value = _state.value.copy(
            route = AppRoute.ERROR,
            transientImagePreview = null,
            errorCode = failure.message?.takeIf { it.matches(Regex("[A-Z0-9_ -]{1,96}")) }
                ?: "LOCAL_PROCESSING_FAILED",
        )
    }

    private suspend fun discardActiveSession() {
        activeSession?.lifecycle?.discard()
        activeSession = null
        sourceSnapshot = null
        reviewPlan = null
        approvedPlan = null
    }

    private fun clearTransientSession() {
        referenceTimerJob?.cancel()
        referenceTimerJob = null
        activeImportAnchor = null
        clearTransientPreview()
        activeSession?.lifecycle?.discard()
        activeSession = null
        sourceSnapshot = null
        reviewPlan = null
        approvedPlan = null
    }

    private fun clearTransientPreview() {
        _state.value.transientImagePreview?.let { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.eraseColor(0)
                bitmap.recycle()
            }
        }
        if (_state.value.transientImagePreview != null) {
            _state.value = _state.value.copy(transientImagePreview = null)
        }
    }

    private fun clearResultPreview() {
        _state.value.exactResultImagePreview?.let { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.eraseColor(0)
                bitmap.recycle()
            }
        }
        if (_state.value.exactResultImagePreview != null) {
            _state.value = _state.value.copy(exactResultImagePreview = null)
        }
    }

    override fun onCleared() {
        clearTransientSession()
        clearResultPreview()
        pendingExternalExport = null
        super.onCleared()
    }

    private companion object {
        const val PREFERENCES_NAME = "shareguard-noncontent-settings-v1"
        const val KEY_SAVED_SORT = "saved_sort"
        const val KEY_SAVED_LAYOUT = "saved_layout"
        const val KEY_SHOW_PREVIEWS = "show_previews"
        const val KEY_PROTECT_SCREENS = "protect_sensitive_screens"
        const val KEY_CONFIRM_BEFORE_TARGET = "confirm_before_waiting_target"
        const val KEY_WAITING_TARGET_MILLIS = "waiting_target_millis"
        const val KEY_SHARE_JITTER = "share_jitter"
    }
}
