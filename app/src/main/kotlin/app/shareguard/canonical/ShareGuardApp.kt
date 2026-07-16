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
import app.shareguard.core.ui.ClaimLanguage
import app.shareguard.core.ui.LimitationCard
import app.shareguard.core.ui.PersistentStatusHeader
import app.shareguard.core.ui.RepresentationStage
import app.shareguard.core.ui.ShareGuardTheme
import app.shareguard.feature.entry.EntryScreen
import app.shareguard.feature.entry.ImageInputPreviewScreen
import app.shareguard.feature.entry.OutputChoiceScreen
import app.shareguard.feature.entry.TextInputScreen
import app.shareguard.feature.review.FindingReviewScreen
import app.shareguard.feature.saved.SavedResultDetailScreen
import app.shareguard.feature.saved.DeleteSavedResultScreen
import app.shareguard.feature.saved.SavedResultsScreen
import app.shareguard.feature.saved.SavedSettingsScreen
import app.shareguard.feature.saved.SavedSettingsUiState

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
                    AppRoute.TEXT_INPUT, AppRoute.IMAGE_PREVIEW, AppRoute.OUTPUT_CHOICE -> RepresentationStage.SOURCE
                    AppRoute.FINDING_REVIEW, AppRoute.SEMANTIC_DIFF, AppRoute.PROCESSING -> RepresentationStage.CANONICAL
                    AppRoute.RESULT -> RepresentationStage.OUTPUT
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
                    onContinue = viewModel::finishOutputChoice,
                    onBack = viewModel::finishOutputChoice,
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
                    onSavedResults = viewModel::openSavedResults,
                    onDone = viewModel::goHome,
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
                        AppRoute.ERROR -> ErrorScreen(state.errorCode ?: "LOCAL_PROCESSING_FAILED", viewModel::goHome)
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
    onSavedResults: () -> Unit,
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
            if (state.savedResultId != null) {
                item { Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) { Text("Share verified result") } }
                item { OutlinedButton(onClick = onSavedResults, modifier = Modifier.fillMaxWidth()) { Text("Saved Results") } }
            }
            item { OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") } }
            item { LimitationCard("Managed artifact boundary", ClaimLanguage.MANAGED_BOUNDARY) }
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
private fun ErrorScreen(code: String, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Processing stopped", style = MaterialTheme.typography.headlineSmall)
        Text(code)
        Text("Source material was not made shareable. You can edit the input and try again.")
        OutlinedButton(onClick = onDone, modifier = Modifier.padding(top = 16.dp)) { Text("Return home") }
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

private fun formatStorage(items: List<app.shareguard.feature.saved.SavedResultCardUiModel>): String =
    formatBytes(items.sumOf { it.storageByteCount })

private fun formatDurationMillis(value: Long): String = app.shareguard.core.ui.formatElapsedMillis(value)

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L -> "%.1f MiB".format(value / (1024.0 * 1024.0))
    value >= 1024L -> "%.1f KiB".format(value / 1024.0)
    else -> "$value B"
}
