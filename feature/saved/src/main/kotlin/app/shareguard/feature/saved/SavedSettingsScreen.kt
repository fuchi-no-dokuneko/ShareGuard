package app.shareguard.feature.saved

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shareguard.core.ui.ClaimLanguage
import app.shareguard.core.ui.LimitationCard

@Composable
fun SavedSettingsScreen(
    state: SavedSettingsUiState,
    onShowPreviewsChange: (Boolean) -> Unit,
    onLayoutChange: (SavedLayout) -> Unit,
    onSortChange: (SavedSort) -> Unit,
    onConfigureWaitingTarget: () -> Unit,
    onConfirmShareBeforeTargetChange: (Boolean) -> Unit,
    onRequireAuthenticationChange: (Boolean) -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Saved Results settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
            item { ToggleRow("Show content previews", state.showContentPreviews, onShowPreviewsChange) }
            item {
                Text("Default layout")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SavedLayout.entries.forEach { layout ->
                        OutlinedButton(onClick = { onLayoutChange(layout) }, enabled = state.defaultLayout != layout) { Text(layout.name.lowercase()) }
                    }
                }
            }
            item {
                Text("Default sort: ${state.defaultSort.name.lowercase()}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SavedSort.entries.forEach { sort ->
                        OutlinedButton(onClick = { onSortChange(sort) }, enabled = state.defaultSort != sort) { Text(sort.name.lowercase()) }
                    }
                }
            }
            item {
                Text("Optional waiting target: ${state.waitingTargetLabel ?: "Not configured"}")
                OutlinedButton(onClick = onConfigureWaitingTarget) { Text("Configure local target") }
                Text("This affects presentation only and never raises assurance.")
            }
            item { ToggleRow("Confirm before sharing prior to your target", state.confirmShareBeforeTarget, onConfirmShareBeforeTargetChange) }
            item {
                ToggleRow(
                    "Require device authentication",
                    state.requireDeviceAuthentication,
                    onRequireAuthenticationChange,
                    enabled = state.deviceAuthenticationSupported,
                )
            }
            item { Text("Storage used: ${state.storageUsageLabel}") }
            item { LimitationCard("Backup status: Off", "Cloud backup and device-transfer extraction are disabled for managed app data in the default edition.") }
            item { Button(onClick = onDeleteAll, modifier = Modifier.fillMaxWidth()) { Text("Delete all Saved Results") } }
            item { LimitationCard("Reference timer", ClaimLanguage.TIMER_LIMIT) }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(label)
    }
}
