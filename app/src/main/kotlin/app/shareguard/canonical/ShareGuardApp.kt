package app.shareguard.canonical

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.ArtifactKind
import app.shareguard.core.model.BlockExecutionStatus
import app.shareguard.core.model.BlockId
import app.shareguard.core.pipeline.BuiltInPresets
import app.shareguard.core.pipeline.NormativeBlockCatalog
import app.shareguard.core.ui.ClaimLanguage
import app.shareguard.core.ui.LimitationCard
import app.shareguard.core.ui.PersistentStatusHeader
import app.shareguard.core.ui.RepresentationStage
import app.shareguard.core.ui.ShareGuardTheme
import app.shareguard.feature.entry.EntryScreen
import app.shareguard.feature.entry.ImageInputPreviewScreen
import app.shareguard.feature.entry.OutputChoiceScreen
import app.shareguard.feature.entry.PresetChoiceScreen
import app.shareguard.feature.entry.TextInputScreen
import app.shareguard.feature.entry.presetChoices
import app.shareguard.feature.review.FindingReviewScreen
import app.shareguard.feature.saved.SavedResultDetailScreen
import app.shareguard.feature.saved.DeleteSavedResultScreen
import app.shareguard.feature.saved.SavedResultsScreen
import app.shareguard.feature.saved.SavedSettingsScreen
import app.shareguard.feature.saved.SavedSettingsUiState
import app.shareguard.feature.workflow.BlockDetailScreen
import app.shareguard.feature.workflow.BlockDetailTab
import app.shareguard.feature.workflow.BlockDetailUiState
import app.shareguard.feature.workflow.WorkflowBlockUiModel
import app.shareguard.feature.workflow.WorkflowScreen
import app.shareguard.feature.workflow.WorkflowUiState

@Composable
fun ShareGuardApp(viewModel: ShareGuardViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.importImage(uri)
    }
    BackHandler(enabled = state.route != AppRoute.HOME) { viewModel.navigateBack() }
    ShareGuardTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                val representationStage = when (state.route) {
                    AppRoute.SOURCE_CHOICE, AppRoute.TEXT_INPUT, AppRoute.IMAGE_PREVIEW, AppRoute.OUTPUT_CHOICE,
                    AppRoute.PRESET_CHOICE, AppRoute.WORKFLOW, AppRoute.BLOCK_DETAIL,
                    -> RepresentationStage.SOURCE
                    AppRoute.FINDING_REVIEW, AppRoute.SEMANTIC_DIFF, AppRoute.PROCESSING -> RepresentationStage.CANONICAL
                    AppRoute.RESULT, AppRoute.VERIFICATION_REPORT -> RepresentationStage.OUTPUT
                    AppRoute.ERROR -> RepresentationStage.SOURCE
                    else -> null
                }
                if (representationStage != null) {
                    PersistentStatusHeader(
                        inputKind = state.inputKind,
                        outputMode = state.selectedOutput,
                        assuranceClass = state.result?.assuranceClass,
                        stage = representationStage,
                        elapsedLabel = state.elapsedSinceImport,
                        onClearSession = viewModel::goHome,
                    )
                }
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    when (state.route) {
                AppRoute.HOME -> EntryScreen(
                    onEnterText = viewModel::openTextEntry,
                    onChooseImage = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onOpenSavedResults = viewModel::openSavedResults,
                    onOpenThreatModel = viewModel::openAbout,
                )
                AppRoute.SOURCE_CHOICE -> SourceChoiceScreen(
                    onChooseText = viewModel::chooseIncomingText,
                    onChooseImage = viewModel::chooseIncomingImage,
                    onDiscard = viewModel::goHome,
                )
                AppRoute.TEXT_INPUT -> TextInputScreen(
                    text = state.text,
                    revealCharacters = state.revealCharacters,
                    selectedOutput = state.selectedOutput,
                    onTextChange = viewModel::updateText,
                    onRevealCharactersChange = viewModel::toggleReveal,
                    onChooseOutput = viewModel::openOutputChoice,
                    onSubmit = viewModel::submitText,
                    onBack = viewModel::backFromTextInput,
                )
                AppRoute.IMAGE_PREVIEW -> ImageInputPreviewScreen(
                    summary = requireNotNull(state.imageSummary),
                    reviewWarning = state.imageOcrWarning.orEmpty(),
                    transientPreview = state.transientImagePreview?.takeUnless { it.isRecycled }?.asImageBitmap(),
                    selectedOutput = state.selectedOutput,
                    onChooseOutput = viewModel::openOutputChoice,
                    onContinue = viewModel::continueImageReview,
                    onReject = viewModel::goHome,
                )
                AppRoute.OUTPUT_CHOICE -> OutputChoiceScreen(
                    selected = state.selectedOutput,
                    allowedModes = if (state.inputKind == app.shareguard.core.model.InputKind.IMAGE) {
                        OutputMode.entries.toSet()
                    } else {
                        setOf(OutputMode.TEXT, OutputMode.REBUILT_IMAGE, OutputMode.BOTH)
                    },
                    onSelect = viewModel::chooseOutput,
                    onContinue = viewModel::openPresetChoice,
                    onBack = viewModel::finishOutputChoice,
                )
                AppRoute.PRESET_CHOICE -> PresetChoiceScreen(
                    selectedId = state.selectedPresetId,
                    choices = presetChoices.filter { choice ->
                        BuiltInPresets.require(choice.id).let { preset ->
                            preset.inputKind == state.inputKind && preset.outputMode == state.selectedOutput
                        }
                    },
                    onSelect = viewModel::choosePreset,
                    onContinue = viewModel::finishPresetChoice,
                    onBack = viewModel::backToOutputChoice,
                )
                AppRoute.WORKFLOW -> WorkflowScreen(
                    state = state.toWorkflowUiState(),
                    onRun = viewModel::runWorkflow,
                    onCancel = viewModel::goHome,
                    onReview = {},
                    onOpenBlock = viewModel::openWorkflowBlock,
                    onChooseLowerCostPreset = viewModel::openOutputChoice,
                )
                AppRoute.BLOCK_DETAIL -> BlockDetailScreen(
                    state = state.toBlockDetailUiState(),
                    onSelectTab = viewModel::selectBlockDetailTab,
                    onBack = viewModel::closeWorkflowBlock,
                )
                AppRoute.FINDING_REVIEW -> FindingReviewScreen(
                    items = state.reviewItems,
                    onChooseAction = viewModel::chooseReviewAction,
                    onContinue = viewModel::applyReviewDecisions,
                    onEditSource = viewModel::editSourceFromReview,
                )
                AppRoute.SEMANTIC_DIFF -> SemanticDiffScreen(
                    canonicalText = state.canonicalPreview,
                    rows = state.ledgerRows,
                    outputMode = state.selectedOutput,
                    derivativeWarningAcknowledged = state.derivativeWarningAcknowledged,
                    onDerivativeWarningAcknowledged = viewModel::setDerivativeWarningAcknowledged,
                    onApprove = viewModel::verifyAndSave,
                    onCancel = viewModel::goHome,
                )
                AppRoute.PROCESSING -> ProcessingScreen()
                AppRoute.RESULT -> ResultScreen(
                    state = requireNotNull(state.result),
                    exactImagePreview = state.exactResultImagePreview?.takeUnless { it.isRecycled }?.asImageBitmap(),
                    onShare = viewModel::shareCurrentResult,
                    onExport = { kind ->
                        state.result?.savedResultId?.let { id -> viewModel.requestExternalExport(id, kind) }
                    },
                    onSavedResults = viewModel::openSavedResults,
                    onReport = viewModel::openVerificationReport,
                    onDone = viewModel::goHome,
                )
                AppRoute.VERIFICATION_REPORT -> VerificationReportScreen(
                    state = requireNotNull(state.result),
                    onBack = viewModel::closeVerificationReport,
                )
                AppRoute.SAVED_RESULTS -> SavedResultsScreen(
                    state = app.shareguard.feature.saved.SavedResultsUiState(
                        query = state.savedQuery,
                        sort = state.savedSort,
                        filter = state.savedFilter,
                        layout = state.savedLayout,
                        storageUsageLabel = formatStorage(state.savedItems),
                        showPreviews = state.showSavedPreviews,
                        items = state.savedItems,
                        selectedIds = state.selectedSavedIds,
                        loading = state.savedResultsLoading,
                        storageErrorCode = state.savedResultsErrorCode,
                    ),
                    sortMenuExpanded = state.sortMenuExpanded,
                    filterMenuExpanded = state.filterMenuExpanded,
                    onQueryChange = viewModel::updateSavedQuery,
                    onToggleSortMenu = viewModel::toggleSortMenu,
                    onChooseSort = viewModel::chooseSavedSort,
                    onToggleFilterMenu = viewModel::toggleFilterMenu,
                    onChooseFilter = viewModel::chooseSavedFilter,
                    onToggleLayout = viewModel::toggleSavedLayout,
                    onOpenSettings = viewModel::openSavedSettings,
                    onOpen = viewModel::openSavedDetail,
                    onShare = viewModel::shareSavedResult,
                    onOverflow = viewModel::openSavedDetail,
                    onToggleSelected = viewModel::toggleSavedSelection,
                    onDeleteSelected = viewModel::requestDeleteSelectedSavedResults,
                )
                AppRoute.SAVED_DETAIL -> {
                    val detail = requireNotNull(state.savedDetail)
                    SavedResultDetailScreen(
                        state = detail,
                        exactImagePreview = state.exactResultImagePreview?.takeUnless { it.isRecycled }?.asImageBitmap(),
                        showImportDate = state.showImportDate,
                        onToggleImportDate = viewModel::toggleImportDate,
                        onShare = { viewModel.shareSavedResult(detail.item.id) },
                        onRevalidate = { viewModel.revalidateSavedResult(detail.item.id) },
                        onExport = { kind -> viewModel.requestExternalExport(detail.item.id, kind) },
                        onEditAsNew = state.savedDetail?.canonicalTextPreview?.let {
                            { viewModel.editSavedResultAsNew() }
                        },
                        onRename = { viewModel.openRenameSavedResult(detail.item.id) },
                        onToggleFavourite = { viewModel.toggleSavedFavourite(detail.item.id) },
                        onDelete = { viewModel.requestDeleteSavedResult(detail.item.id) },
                    )
                }
                AppRoute.DELETE_CONFIRMATION -> DeleteSavedResultScreen(
                    labels = state.pendingDeletionLabels,
                    outputDescription = "Verified managed artifacts and their in-app metadata",
                    combinedStorageLabel = formatStorage(
                        state.savedItems.filter { it.id in state.pendingDeletionIds },
                    ),
                    externalCopyMayExist = state.pendingDeletionExternalCopyMayExist,
                    deleting = state.deletionInProgress,
                    onConfirm = viewModel::confirmDeletion,
                    onCancel = viewModel::cancelDeletion,
                )
                AppRoute.RENAME_SAVED -> RenameSavedResultScreen(
                    value = state.editingSavedLabel,
                    onValueChange = viewModel::updateSavedLabel,
                    onSave = viewModel::confirmRenameSavedResult,
                    onCancel = viewModel::navigateBack,
                )
                AppRoute.SAVED_SETTINGS -> SavedSettingsScreen(
                    state = SavedSettingsUiState(
                        showContentPreviews = state.showSavedPreviews,
                        protectSensitiveScreens = state.protectSensitiveScreens,
                        defaultLayout = state.savedLayout,
                        defaultSort = state.savedSort,
                        waitingTargetLabel = state.waitingTargetMillis?.let(::formatDurationMillis),
                        confirmShareBeforeTarget = state.confirmShareBeforeWaitingTarget,
                        requireDeviceAuthentication = false,
                        deviceAuthenticationSupported = false,
                        storageUsageLabel = formatStorage(state.savedItems),
                    ),
                    onShowPreviewsChange = viewModel::setShowSavedPreviews,
                    onProtectSensitiveScreensChange = viewModel::setProtectSensitiveScreens,
                    onLayoutChange = viewModel::setSavedLayout,
                    onSortChange = viewModel::chooseSavedSort,
                    onConfigureWaitingTarget = viewModel::openWaitingTargetEditor,
                    onConfirmShareBeforeTargetChange = viewModel::setConfirmShareBeforeWaitingTarget,
                    onRequireAuthenticationChange = {},
                    onDeleteAll = viewModel::requestDeleteAllSavedResults,
                )
                AppRoute.WAITING_TARGET -> WaitingTargetScreen(
                    value = state.waitingTargetInputMinutes,
                    onValueChange = viewModel::updateWaitingTargetMinutes,
                    onSave = viewModel::saveWaitingTarget,
                    onCancel = viewModel::navigateBack,
                )
                AppRoute.PRE_SHARE -> PreShareConfirmationScreen(
                    elapsedSinceImport = state.pendingShareElapsed ?: "less than a minute",
                    waitingTargetStatus = state.pendingShareTargetStatus,
                    shareJitterEnabled = state.shareJitterEnabled,
                    onShareNow = viewModel::confirmManagedShare,
                    onReturn = viewModel::cancelManagedShare,
                )
                AppRoute.ABOUT -> AboutScreen(
                    shareJitterEnabled = state.shareJitterEnabled,
                    onShareJitterChange = viewModel::toggleShareJitter,
                    onBack = viewModel::goHome,
                )
                        AppRoute.ERROR -> ErrorScreen(
                            code = state.errorCode ?: "LOCAL_PROCESSING_FAILED",
                            editableTextAvailable = state.text.isNotEmpty(),
                            onRecoverAsText = viewModel::recoverAsEditableText,
                            onDiscard = viewModel::goHome,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SemanticDiffScreen(
    canonicalText: String,
    rows: List<LedgerReviewRow>,
    outputMode: OutputMode,
    derivativeWarningAcknowledged: Boolean,
    onDerivativeWarningAcknowledged: (Boolean) -> Unit,
    onApprove: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    if (outputMode == OutputMode.DERIVATIVE_IMAGE) {
                        "Experimental derivative warning"
                    } else {
                        "Review possible semantic impact"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text("Every application transformation is listed before final verification.")
            }
            if (outputMode == OutputMode.DERIVATIVE_IMAGE) {
                item {
                    LimitationCard(
                        "Source pixels remain related",
                        "This output remains semantically and statistically related to the source image. " +
                            "Unknown robust watermark signals may remain. This mode cannot exceed AS-1 and " +
                            "does not claim anonymity or watermark removal.",
                    )
                }
            } else {
                item { LimitationCard("Complete canonical result", canonicalText) }
            }
            if (rows.isEmpty()) {
                item { Text("No byte-changing transformation was required.") }
            } else {
                items(rows) { row ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(row.blockId, fontWeight = FontWeight.Bold)
                        Text("Reason: ${row.reason}")
                        Text("Semantic impact: ${row.semanticImpact}")
                        if (row.before != null) Text("Before: ${row.before}")
                        if (row.after != null) Text("After: ${row.after}")
                    }
                }
            }
            item {
                LimitationCard(
                    "Assurance consequence",
                    if (outputMode == OutputMode.DERIVATIVE_IMAGE) {
                        "The maximum is AS-1 Re-encoded Derivative even when every mandatory verifier passes."
                    } else {
                        "This text workflow can reach reviewed canonical-text assurance only after every " +
                            "mandatory verifier passes. It does not claim anonymity."
                    },
                )
            }
            if (outputMode == OutputMode.DERIVATIVE_IMAGE) {
                item {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Checkbox(
                            checked = derivativeWarningAcknowledged,
                            onCheckedChange = onDerivativeWarningAcknowledged,
                        )
                        Column {
                            Text("I understand this export retains source-pixel relationships", fontWeight = FontWeight.SemiBold)
                            Text("This acknowledgement applies only to this export and warning version.")
                        }
                    }
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = onApprove,
                        enabled = outputMode != OutputMode.DERIVATIVE_IMAGE || derivativeWarningAcknowledged,
                    ) { Text("Approve, verify and save") }
                }
            }
        }
    }
}

@Composable
private fun SourceChoiceScreen(
    onChooseText: () -> Unit,
    onChooseImage: () -> Unit,
    onDiscard: () -> Unit,
) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Choose one source",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            LimitationCard(
                "Malformed combined share",
                "The sending app supplied both text and an image. Canonical Share processes exactly one source and will not choose silently.",
            )
            Button(onClick = onChooseText, modifier = Modifier.fillMaxWidth()) { Text("Use shared text") }
            Button(onClick = onChooseImage, modifier = Modifier.fillMaxWidth()) { Text("Use shared image") }
            OutlinedButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) { Text("Discard both") }
        }
    }
}

@Composable
private fun ProcessingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Processing locally", style = MaterialTheme.typography.headlineSmall)
        Text("The exact result will not be saved or shareable unless mandatory verification passes.")
    }
}

@Composable
private fun ResultScreen(
    state: ResultUiState,
    exactImagePreview: androidx.compose.ui.graphics.ImageBitmap?,
    onShare: () -> Unit,
    onExport: (ArtifactKind) -> Unit,
    onSavedResults: () -> Unit,
    onReport: () -> Unit,
    onDone: () -> Unit,
) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(if (state.savedResultId != null) "Verified and saved" else "Verification blocked", style = MaterialTheme.typography.headlineSmall) }
            if (state.outputMode != OutputMode.DERIVATIVE_IMAGE) {
                item { LimitationCard("Canonical text", state.canonicalText) }
            }
            if (exactImagePreview != null) {
                item {
                    androidx.compose.foundation.Image(
                        bitmap = exactImagePreview,
                        contentDescription = if (state.outputMode == OutputMode.DERIVATIVE_IMAGE) {
                            "Exact final derivative image"
                        } else {
                            "Exact final rebuilt image"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item { Text(state.assuranceLabel, fontWeight = FontWeight.Bold) }
            items(state.statusLines) { Text(it) }
            items(state.limitationLines) { LimitationCard("Limitation", it) }
            if (state.blockingChecks.isNotEmpty()) {
                item { LimitationCard("Blocking checks", state.blockingChecks.joinToString()) }
            }
            item { OutlinedButton(onClick = onReport, modifier = Modifier.fillMaxWidth()) { Text("Verification report") } }
            if (state.savedResultId != null) {
                item { Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) { Text("Share verified result") } }
                if (state.outputMode in setOf(OutputMode.TEXT, OutputMode.BOTH)) {
                    item {
                        OutlinedButton(
                            onClick = { onExport(ArtifactKind.CANONICAL_TEXT) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Export canonical text copy") }
                    }
                }
                if (state.outputMode in setOf(OutputMode.REBUILT_IMAGE, OutputMode.BOTH)) {
                    item {
                        OutlinedButton(
                            onClick = { onExport(ArtifactKind.REBUILT_IMAGE) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Export rebuilt image copy") }
                    }
                }
                if (state.outputMode == OutputMode.DERIVATIVE_IMAGE) {
                    item {
                        OutlinedButton(
                            onClick = { onExport(ArtifactKind.DERIVATIVE_IMAGE) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Export derivative image copy") }
                    }
                }
                item { OutlinedButton(onClick = onSavedResults, modifier = Modifier.fillMaxWidth()) { Text("Saved Results") } }
            }
            item { OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") } }
            item { LimitationCard("Managed artifact boundary", ClaimLanguage.MANAGED_BOUNDARY) }
        }
    }
}

@Composable
private fun VerificationReportScreen(
    state: ResultUiState,
    onBack: () -> Unit,
) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Verification report",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
            items(state.verificationReportRows) { (label, value) ->
                Column {
                    Text(label, fontWeight = FontWeight.SemiBold)
                    Text(value)
                }
            }
            items(state.limitationLines) { LimitationCard("Declared limitation", it) }
            item { LimitationCard("Managed artifact boundary", ClaimLanguage.MANAGED_BOUNDARY) }
            item { OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to result") } }
        }
    }
}

@Composable
private fun AboutScreen(
    shareJitterEnabled: Boolean,
    onShareJitterChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("About and threat model", style = MaterialTheme.typography.headlineSmall) }
            item { Text(ClaimLanguage.LOCAL_PROCESSING) }
            item { LimitationCard("What verification means", ClaimLanguage.MANAGED_BOUNDARY) }
            item { LimitationCard("Reference timer", ClaimLanguage.TIMER_LIMIT) }
            item { LimitationCard("Deletion", ClaimLanguage.DELETE_LIMIT) }
            item {
                Text(
                    "Canonical Share does not guarantee anonymity, defeat account or network correlation, " +
                        "or prove the absence of every unknown signal. It is not an anonymous transport.",
                )
            }
            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = shareJitterEnabled, onCheckedChange = onShareJitterChange)
                    Column {
                        Text("Optional local share jitter", fontWeight = FontWeight.SemiBold)
                        Text("Adds a random 0.1–0.5 second local delay before the Sharesheet. It is not anonymity protection.")
                    }
                }
            }
            item { OutlinedButton(onClick = onBack) { Text("Back") } }
        }
    }
}

@Composable
private fun ErrorScreen(
    code: String,
    editableTextAvailable: Boolean,
    onRecoverAsText: () -> Unit,
    onDiscard: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Processing stopped", style = MaterialTheme.typography.headlineSmall)
        Text(code)
        Text("Source material was not made shareable. Mandatory verification was not weakened.")
        if (editableTextAvailable) {
            Text("A safe alternative is to treat the current editable wording as a new text-only source and review it again.")
            Button(onClick = onRecoverAsText, modifier = Modifier.padding(top = 16.dp)) {
                Text("Recover as editable text")
            }
        }
        OutlinedButton(onClick = onDiscard, modifier = Modifier.padding(top = 16.dp)) { Text("Discard session") }
    }
}

@Composable
private fun RenameSavedResultScreen(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Rename Saved Result", style = MaterialTheme.typography.headlineSmall)
            Text("The label is stored as management metadata. Do not use source content if you want a neutral label.")
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("Display label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onSave, enabled = value.isNotBlank()) { Text("Save label") }
            }
        }
    }
}

@Composable
private fun PreShareConfirmationScreen(
    elapsedSinceImport: String,
    waitingTargetStatus: String?,
    shareJitterEnabled: Boolean,
    onShareNow: () -> Unit,
    onReturn: () -> Unit,
) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Before sharing",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
            item { LimitationCard("Time since import: $elapsedSinceImport", ClaimLanguage.TIMER_LIMIT) }
            if (waitingTargetStatus != null) item { LimitationCard("Your waiting target", waitingTargetStatus) }
            item {
                Text(
                    "The verified status applies to the exact copy generated now. Later edits and receiver " +
                        "consumption are outside Canonical Share's managed boundary.",
                )
            }
            if (shareJitterEnabled) {
                item {
                    Text("Optional local jitter is enabled. Its short random delay is not anonymity protection.")
                }
            }
            item { Button(onClick = onShareNow, modifier = Modifier.fillMaxWidth()) { Text("Share now") } }
            item { OutlinedButton(onClick = onReturn, modifier = Modifier.fillMaxWidth()) { Text("Return without sharing") } }
        }
    }
}

@Composable
private fun WaitingTargetScreen(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("User-defined waiting target", style = MaterialTheme.typography.headlineSmall)
            Text("Enter whole minutes, or leave blank to disable. This changes presentation only and never raises assurance.")
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("Minutes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onSave) { Text("Save local target") }
            }
        }
    }
}

private fun ShareGuardUiState.toWorkflowUiState(): WorkflowUiState {
    val preset = BuiltInPresets.require(selectedPresetId)
    return WorkflowUiState(
        presetName = preset.presetId,
        schemaVersion = preset.schemaVersion.value,
        inputLabel = preset.inputKind.name.lowercase(),
        outputLabel = preset.outputMode.name.lowercase().replace('_', ' '),
        blocks = preset.blockReferences.mapIndexed { index, reference ->
            val metadata = NormativeBlockCatalog.registry.require(reference)
            WorkflowBlockUiModel(
                blockId = reference.blockId.value,
                name = metadata.displayName,
                status = if (index == 0) BlockExecutionStatus.READY else BlockExecutionStatus.WAITING,
                findingCount = 0,
                changeCount = 0,
                warningCount = 0,
                mandatory = metadata.mandatory,
                inputType = metadata.acceptedInputKinds.joinToString { it.name.lowercase() },
                outputType = metadata.supportedOutputModes.joinToString { it.name.lowercase().replace('_', ' ') },
            )
        },
        running = false,
        waitingForReview = false,
    )
}

private fun ShareGuardUiState.toBlockDetailUiState(): BlockDetailUiState {
    val blockId = requireNotNull(selectedWorkflowBlockId)
    val metadata = NormativeBlockCatalog.registry.require(BlockId(blockId))
    val rows = when (selectedBlockDetailTab) {
        BlockDetailTab.PURPOSE -> listOf(
            "Purpose" to metadata.description,
            "Pipeline stage" to metadata.stage.name.lowercase().replace('_', ' '),
            "Threat coverage" to metadata.threatCoverage.joinToString(),
        )
        BlockDetailTab.FINDINGS -> listOf(
            "Current state" to "Not run",
            "Review behavior" to if (metadata.requiresReview) "May stop for explicit review" else "No block-specific review gate",
        )
        BlockDetailTab.CHANGES -> listOf(
            "Content transforming" to if (metadata.contentTransforming) "Yes — every applied change is ledgered" else "No",
            "Transformation category" to (metadata.transformationCategory?.name?.lowercase()?.replace('_', ' ') ?: "None"),
        )
        BlockDetailTab.SETTINGS -> listOf(
            "Settings schema" to metadata.settingsSchemaVersion.value.toString(),
            "Mandatory" to if (metadata.mandatory) "Yes — pinned" else "No",
            "Conditional" to if (metadata.conditional) "Yes" else "No",
        )
        BlockDetailTab.VERIFICATION -> listOf(
            "Independent checks" to metadata.verificationRequirements.joinToString { it.name.lowercase().replace('_', ' ') }
                .ifEmpty { "Covered by final structural and revision verification" },
            "Invalidated by" to metadata.invalidationKeys.joinToString { it.name.lowercase().replace('_', ' ') },
        )
        BlockDetailTab.TECHNICAL -> listOf(
            "Block version" to metadata.blockVersion.value.toString(),
            "Resource class" to metadata.resourceClass.name.lowercase().replace('_', ' '),
            "Offline capability" to metadata.offlineCapability.name.lowercase().replace('_', ' '),
            "Deterministic" to if (metadata.deterministic) "Yes" else "No",
            "Persistent content logging" to if (metadata.persistentLoggingAllowed) "Allowed" else "Disabled",
        )
    }
    return BlockDetailUiState(
        blockId = blockId,
        name = metadata.displayName,
        selectedTab = selectedBlockDetailTab,
        rows = rows,
        limitation = "This pre-run view shows the versioned contract. Findings, changes and evidence appear only after execution.",
    )
}

private fun formatStorage(items: List<app.shareguard.feature.saved.SavedResultCardUiModel>): String =
    formatBytes(items.sumOf { it.storageByteCount })

private fun formatDurationMillis(value: Long): String = app.shareguard.core.ui.formatElapsedMillis(value)

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L -> "%.1f MiB".format(value / (1024.0 * 1024.0))
    value >= 1024L -> "%.1f KiB".format(value / 1024.0)
    else -> "$value B"
}
