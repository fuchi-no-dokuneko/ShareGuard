package app.shareguard.feature.saved

import androidx.compose.foundation.Image
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.shareguard.core.ui.ClaimLanguage
import app.shareguard.core.model.ArtifactKind
import app.shareguard.core.model.OutputMode
import app.shareguard.core.ui.LimitationCard
import app.shareguard.core.ui.SourcePixelNotice

@Composable
fun SavedResultDetailScreen(
    state: SavedResultDetailUiState,
    exactImagePreview: ImageBitmap?,
    showImportDate: Boolean,
    onToggleImportDate: () -> Unit,
    onShare: () -> Unit,
    onRevalidate: () -> Unit,
    onExport: (ArtifactKind) -> Unit,
    onEditAsNew: (() -> Unit)?,
    onRename: () -> Unit,
    onToggleFavourite: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(state.item.displayLabel, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
            item { Detail("Output", state.item.outputMode.name.lowercase().replace('_', ' ')) }
            item { Detail("Assurance", ClaimLanguage.assuranceLabel(state.item.assuranceClass)) }
            item { Detail("Storage state", state.item.lifecycleState.name.lowercase().replace('_', ' ')) }
            item { Detail("Verification state", state.item.verificationState.name.lowercase().replace('_', ' ')) }
            if (state.canonicalTextPreview != null) item { LimitationCard("Canonical text preview", state.canonicalTextPreview) }
            if (exactImagePreview != null) {
                item {
                    Image(
                        bitmap = exactImagePreview,
                        contentDescription = "Exact verified rebuilt image preview",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (state.imagePreviewDescription != null) item { LimitationCard("Image preview", state.imagePreviewDescription) }
            if (state.retainedSourceRegionCount > 0) item { SourcePixelNotice(state.retainedSourceRegionCount) }
            item {
                LimitationCard(
                    "Time since import: ${state.item.elapsedSinceImport}",
                    ClaimLanguage.IMPORT_MEANING + " " + ClaimLanguage.TIMER_LIMIT,
                )
            }
            item {
                OutlinedButton(onClick = onToggleImportDate) {
                    Text(if (showImportDate) "Hide local import date" else "Show local import date")
                }
                if (showImportDate) Text(state.localImportDateLabel)
            }
            if (state.waitingTargetLabel != null) {
                item {
                    Detail("Your optional waiting target", state.waitingTargetLabel)
                    Detail("Remaining reference duration", state.remainingToTargetLabel ?: "Target reached")
                    Text("You may share before this advisory target. It does not change assurance.")
                }
            }
            item {
                Text("Verification summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                state.verificationSummary.forEach { (name, value) -> Detail(name, value) }
            }
            items(state.unresolvedLimitations) { limitation -> LimitationCard("Unresolved limitation", limitation) }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onShare, enabled = state.item.canManagedShare) { Text("Share") }
                    if (!state.item.canManagedShare) OutlinedButton(onClick = onRevalidate) { Text("Revalidate") }
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRename) { Text("Rename") }
                    OutlinedButton(onClick = onToggleFavourite) {
                        Text(if (state.item.favourite) "Remove favourite" else "Favourite")
                    }
                }
            }
            if (onEditAsNew != null) {
                item {
                    OutlinedButton(onClick = onEditAsNew, modifier = Modifier.fillMaxWidth()) {
                        Text("Edit as a new result")
                    }
                    Text("The verified artifact above is not overwritten. Completing the edited workflow creates a new Saved Result.")
                }
            }
            if (state.item.outputMode in setOf(OutputMode.TEXT, OutputMode.BOTH)) {
                item {
                    OutlinedButton(
                        onClick = { onExport(ArtifactKind.CANONICAL_TEXT) },
                        enabled = state.item.canManagedShare,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Export canonical text copy") }
                }
            }
            if (state.item.outputMode in setOf(OutputMode.REBUILT_IMAGE, OutputMode.BOTH)) {
                item {
                    OutlinedButton(
                        onClick = { onExport(ArtifactKind.REBUILT_IMAGE) },
                        enabled = state.item.canManagedShare,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Export rebuilt image copy") }
                }
            }
            if (state.item.outputMode == OutputMode.DERIVATIVE_IMAGE) {
                item {
                    OutlinedButton(
                        onClick = { onExport(ArtifactKind.DERIVATIVE_IMAGE) },
                        enabled = state.item.canManagedShare,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Export derivative image copy") }
                }
            }
            item { OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete") } }
            item { LimitationCard("Managed artifact boundary", ClaimLanguage.MANAGED_BOUNDARY) }
        }
    }
}

@Composable
fun DeleteSavedResultScreen(
    labels: List<String>,
    outputDescription: String,
    combinedStorageLabel: String,
    externalCopyMayExist: Boolean,
    deleting: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    require(labels.isNotEmpty())
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Delete permanently from Canonical Share?", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(if (labels.size == 1) labels.single() else "${labels.size} selected results")
            Text(outputDescription)
            Text("Local app storage: $combinedStorageLabel")
            Text(if (externalCopyMayExist) "An exported external copy may still exist." else "No external export is recorded, but other copies cannot be ruled out.")
            LimitationCard("What deletion means", ClaimLanguage.DELETE_LIMIT)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onCancel, enabled = !deleting) { Text("Cancel") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onConfirm, enabled = !deleting) { Text(if (deleting) "Deleting…" else "Delete in-app result") }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(value)
    }
}
