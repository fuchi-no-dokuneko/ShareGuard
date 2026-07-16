package app.shareguard.feature.saved

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.shareguard.core.ui.ClaimLanguage

@Composable
fun SavedResultsScreen(
    state: SavedResultsUiState,
    sortMenuExpanded: Boolean,
    filterMenuExpanded: Boolean,
    onQueryChange: (String) -> Unit,
    onToggleSortMenu: () -> Unit,
    onChooseSort: (SavedSort) -> Unit,
    onToggleFilterMenu: () -> Unit,
    onChooseFilter: (SavedFilter) -> Unit,
    onToggleLayout: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onOverflow: (String) -> Unit,
    onToggleSelected: (String) -> Unit,
    onDeleteSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Saved Results",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp).semantics { heading() },
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search display labels") },
                singleLine = true,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column {
                    OutlinedButton(onClick = onToggleSortMenu) { Text("Sort: ${state.sort.name.lowercase()}") }
                    DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = onToggleSortMenu) {
                        SavedSort.entries.forEach { sort ->
                            DropdownMenuItem(text = { Text(sort.name.lowercase()) }, onClick = { onChooseSort(sort) })
                        }
                    }
                }
                Column {
                    OutlinedButton(onClick = onToggleFilterMenu) { Text("Filter: ${state.filter.name.lowercase()}") }
                    DropdownMenu(expanded = filterMenuExpanded, onDismissRequest = onToggleFilterMenu) {
                        SavedFilter.entries.forEach { filter ->
                            DropdownMenuItem(text = { Text(filter.name.lowercase().replace('_', ' ')) }, onClick = { onChooseFilter(filter) })
                        }
                    }
                }
                OutlinedButton(onClick = onToggleLayout) {
                    Text("Layout: ${state.layout.name.lowercase()}")
                }
                OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
            }
            Text("Local storage used: ${state.storageUsageLabel}")
            if (state.selectedIds.isNotEmpty()) {
                Button(onClick = onDeleteSelected) { Text("Delete ${state.selectedIds.size} selected") }
            }
            if (state.items.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Text(
                        "No verified results match this view. Results appear here only after verification, " +
                            "reopen/digest checks and the durable commit complete.",
                    )
                }
            } else if (state.layout == SavedLayout.LIST) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    listItems(state.items, key = { it.id }) { item ->
                        SavedResultCard(
                            item = item,
                            selected = item.id in state.selectedIds,
                            showPreview = state.showPreviews,
                            onOpen = { onOpen(item.id) },
                            onShare = { onShare(item.id) },
                            onOverflow = { onOverflow(item.id) },
                            onToggleSelected = { onToggleSelected(item.id) },
                        )
                    }
                    item { ManagedBoundaryFooter() }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 260.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    gridItems(state.items, key = { it.id }) { item ->
                        SavedResultCard(
                            item = item,
                            selected = item.id in state.selectedIds,
                            showPreview = state.showPreviews,
                            onOpen = { onOpen(item.id) },
                            onShare = { onShare(item.id) },
                            onOverflow = { onOverflow(item.id) },
                            onToggleSelected = { onToggleSelected(item.id) },
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { ManagedBoundaryFooter() }
                }
            }
        }
    }
}

@Composable
private fun ManagedBoundaryFooter() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(ClaimLanguage.MANAGED_BOUNDARY, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.padding(bottom = 12.dp))
    }
}

@Composable
private fun SavedResultCard(
    item: SavedResultCardUiModel,
    selected: Boolean,
    showPreview: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onOverflow: () -> Unit,
    onToggleSelected: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "${item.displayLabel}, ${item.outputMode.name.lowercase().replace('_', ' ')}, " +
                "${ClaimLanguage.assuranceLabel(item.assuranceClass)}, time since import ${item.elapsedSinceImport}"
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelected() })
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.displayLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${item.outputMode.name.lowercase().replace('_', ' ')} · ${if (item.favourite) "Favourite" else "Saved"}")
                }
                OutlinedButton(onClick = onOverflow) { Text("More") }
            }
            if (showPreview) Text(item.previewDescription ?: "Generic ${item.outputMode.name.lowercase()} icon")
            Text(ClaimLanguage.assuranceLabel(item.assuranceClass))
            Text("Saved ${item.savedAtLabel}")
            Text("Time since import: ${item.elapsedSinceImport}")
            Text("Integrity: ${item.integrityState.name.lowercase()} · ${item.storageBytesLabel}")
            Button(onClick = onShare, enabled = item.canManagedShare) {
                Text(if (item.canManagedShare) "Share" else "Revalidate before sharing")
            }
        }
    }
}
