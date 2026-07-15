package app.shareguard.feature.workflow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import app.shareguard.core.model.BlockExecutionStatus
import app.shareguard.core.ui.LimitationCard

@Composable
fun WorkflowScreen(
    state: WorkflowUiState,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onReview: () -> Unit,
    onOpenBlock: (String) -> Unit,
    onChooseLowerCostPreset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            WorkflowActions(
                state = state,
                onRun = onRun,
                onCancel = onCancel,
                onReview = onReview,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(vertical = 16.dp)) {
                    Text("Sequential workflow", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Text("${state.inputLabel} → ${state.outputLabel}", style = MaterialTheme.typography.titleMedium)
                    Text("${state.presetName} · schema ${state.schemaVersion}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.fatalReason != null) {
                item {
                    LimitationCard("Processing stopped", state.fatalReason)
                    OutlinedButton(onClick = onChooseLowerCostPreset, modifier = Modifier.padding(vertical = 12.dp)) {
                        Text("Choose a lower-cost preset")
                    }
                }
            }
            itemsIndexed(state.blocks, key = { _, item -> item.blockId }) { index, block ->
                if (index > 0) {
                    Column(
                        modifier = Modifier.fillMaxWidth().height(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) { HorizontalDivider(modifier = Modifier.width(2.dp).height(20.dp), thickness = 2.dp) }
                }
                WorkflowBlockCard(block = block, onOpen = { onOpenBlock(block.blockId) })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun WorkflowBlockCard(block: WorkflowBlockUiModel, onOpen: () -> Unit) {
    val color = when (block.status) {
        BlockExecutionStatus.FAILED_FATAL -> MaterialTheme.colorScheme.errorContainer
        BlockExecutionStatus.REVIEW_REQUIRED -> MaterialTheme.colorScheme.tertiaryContainer
        BlockExecutionStatus.DONE_CHANGED -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "${block.name}, ${block.status.name.lowercase().replace('_', ' ')}, " +
                "${block.findingCount} findings, ${block.changeCount} changes"
        },
        colors = CardDefaults.cardColors(containerColor = color),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(block.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(block.blockId, style = MaterialTheme.typography.labelMedium)
                }
                Text(block.status.name.lowercase().replace('_', ' '), fontWeight = FontWeight.SemiBold)
            }
            Text("${if (block.mandatory) "Mandatory" else "Optional"} · ${block.inputType} → ${block.outputType}")
            Text("Detected ${block.findingCount} · Changed ${block.changeCount} · Warnings ${block.warningCount}")
        }
    }
}

@Composable
private fun WorkflowActions(
    state: WorkflowUiState,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onReview: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.running) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            } else {
                OutlinedButton(onClick = onRun, enabled = state.fatalReason == null) { Text("Run") }
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onReview, enabled = state.waitingForReview) { Text("Review") }
        }
    }
}
