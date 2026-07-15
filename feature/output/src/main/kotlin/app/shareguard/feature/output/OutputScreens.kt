package app.shareguard.feature.output

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.shareguard.core.model.OutputMode
import app.shareguard.core.ui.ClaimLanguage
import app.shareguard.core.ui.LimitationCard
import app.shareguard.core.ui.SourcePixelNotice

@Composable
fun OutputPreviewScreen(
    state: OutputPreviewUiState,
    onSelectTab: (OutputTab) -> Unit,
    onOpenReport: () -> Unit,
    onRetryVerification: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleTabs = OutputTab.entries.filter { tab ->
        when (tab) {
            OutputTab.CANONICAL_TEXT -> state.canonicalText != null
            OutputTab.REBUILT_IMAGE -> state.hasImage && state.mode != OutputMode.DERIVATIVE_IMAGE
            OutputTab.DERIVATIVE_IMAGE -> state.mode == OutputMode.DERIVATIVE_IMAGE
            OutputTab.DIFF, OutputTab.VERIFICATION -> true
        }
    }
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Output preview", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text("This preview is app chrome. Only the artifact inside the preview boundary is shared.")
            }
            PrimaryScrollableTabRow(selectedTabIndex = visibleTabs.indexOf(state.selectedTab).coerceAtLeast(0)) {
                visibleTabs.forEach { tab ->
                    Tab(selected = state.selectedTab == tab, onClick = { onSelectTab(tab) }, text = { Text(tab.label) })
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.retainedSourceRegionCount > 0) item { SourcePixelNotice(state.retainedSourceRegionCount) }
                when (state.selectedTab) {
                    OutputTab.CANONICAL_TEXT -> item { ArtifactTextBoundary(state.canonicalText.orEmpty()) }
                    OutputTab.REBUILT_IMAGE, OutputTab.DERIVATIVE_IMAGE -> item {
                        LimitationCard("Image artifact preview", state.imageDescription ?: "Verified image representation")
                    }
                    OutputTab.DIFF -> items(state.diffSummary) { Text("• $it") }
                    OutputTab.VERIFICATION -> items(state.verifierSummary) { (name, status) ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(name, fontWeight = FontWeight.SemiBold)
                            Text(status)
                        }
                    }
                }
                if (state.fatalVerificationReason != null) {
                    item { LimitationCard("Mandatory verification failed", state.fatalVerificationReason) }
                    item { Button(onClick = onRetryVerification) { Text("Retry verification") } }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onOpenReport) { Text("View report") }
            }
        }
    }
}

@Composable
private fun ArtifactTextBoundary(text: String) {
    LimitationCard(
        title = "Canonical text artifact",
        text = text,
        modifier = Modifier.semantics { /* explicit semantic grouping */ },
    )
}

@Composable
fun VerificationReportScreen(
    state: VerificationReportUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Verification report", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
            item { Detail("Assurance", ClaimLanguage.assuranceLabel(state.assuranceClass)) }
            item { Detail("Executed block versions", state.executedBlocks.joinToString()) }
            item { Detail("Findings detected", state.findingsDetected.toString()) }
            item { Detail("Changes applied", state.changesApplied.toString()) }
            item { Detail("Unresolved findings", state.unresolvedFindings.joinToString().ifEmpty { "None" }) }
            item { Detail("Retained source regions", state.retainedSourceRegions.joinToString().ifEmpty { "None" }) }
            item { Detail("Final metadata", state.finalMetadataSummary) }
            item { Detail("Final Unicode scan", state.finalUnicodeSummary) }
            item { Detail("Final URL scan", state.finalUrlSummary) }
            if (state.ocrRoundTripSummary != null) item { Detail("OCR round trip", state.ocrRoundTripSummary) }
            item { Detail("Output construction", state.rebuiltOrDerived) }
            items(state.limitations) { limitation -> LimitationCard("Known limitation", limitation) }
            item { LimitationCard("Managed artifact boundary", ClaimLanguage.MANAGED_BOUNDARY) }
            item { OutlinedButton(onClick = onBack) { Text("Back") } }
        }
    }
}

@Composable
fun SavedConfirmationScreen(
    state: SavedConfirmationUiState,
    onRetrySave: () -> Unit,
    onShare: () -> Unit,
    onViewSavedResult: () -> Unit,
    onExportCopy: () -> Unit,
    onDelete: () -> Unit,
    onClearTransientSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    if (state.saved) "Saved in Canonical Share" else "Verified result not yet saved",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
            item { Detail("Display label", state.displayLabel) }
            item { Detail("Output", state.mode.name.lowercase().replace('_', ' ')) }
            item { Detail("Assurance", ClaimLanguage.assuranceLabel(state.assuranceClass)) }
            item { Detail("Time since import", state.elapsedSinceImport) }
            item { LimitationCard("Reference timer", ClaimLanguage.TIMER_LIMIT) }
            if (!state.saved) {
                item { LimitationCard("Persistence failed", state.persistenceError ?: "The exact verified representation was not committed.") }
                item { Button(onClick = onRetrySave, modifier = Modifier.fillMaxWidth()) { Text("Retry save") } }
            } else {
                item { Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) { Text("Share") } }
                item { OutlinedButton(onClick = onViewSavedResult, modifier = Modifier.fillMaxWidth()) { Text("View in Saved Results") } }
                item { OutlinedButton(onClick = onExportCopy, modifier = Modifier.fillMaxWidth()) { Text("Export copy") } }
                item { OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete saved result") } }
            }
            item { OutlinedButton(onClick = onClearTransientSession, modifier = Modifier.fillMaxWidth()) { Text("Clear transient session") } }
            item { LimitationCard("External copies", ClaimLanguage.MANAGED_BOUNDARY) }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(value)
    }
}
