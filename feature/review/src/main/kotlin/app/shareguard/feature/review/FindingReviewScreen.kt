package app.shareguard.feature.review

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.shareguard.core.model.DecisionAction

@Composable
fun FindingReviewScreen(
    items: List<ReviewItemUiModel>,
    onChooseAction: (findingId: String, action: DecisionAction) -> Unit,
    onOpenDetail: (findingId: String) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unresolved = items.count { it.selectedAction == null }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onContinue, enabled = unresolved == 0) { Text("Apply reviewed decisions") }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(vertical = 16.dp)) {
                    Text("Review findings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Text(if (unresolved == 0) "All decisions recorded" else "$unresolved decision${if (unresolved == 1) "" else "s"} required")
                }
            }
            ReviewGroup.entries.forEach { group ->
                val groupItems = items.filter { it.group == group }
                if (groupItems.isNotEmpty()) {
                    item { Text(group.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    items(groupItems, key = { it.id }) { item ->
                        FindingCard(item, onChooseAction, onOpenDetail)
                    }
                }
            }
        }
    }
}

@Composable
private fun FindingCard(
    item: ReviewItemUiModel,
    onChooseAction: (String, DecisionAction) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Detected · ${item.category.name.lowercase().replace('_', ' ')} · ${item.severity.name.lowercase()}")
            Text("Confidence: ${item.confidence.name.lowercase().replace('_', ' ')}")
            Text("Location: ${item.locationDescription}")
            if (item.before != null || item.after != null) {
                Column {
                    Text("Before", style = MaterialTheme.typography.labelLarge)
                    Text(item.before ?: "Nothing")
                    Text("After", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                    Text(item.after ?: "Nothing")
                }
            }
            item.allowedActions.forEach { action ->
                OutlinedButton(
                    onClick = { onChooseAction(item.id, action) },
                    modifier = Modifier.fillMaxWidth(),
                    border = if (item.selectedAction == action) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                ) { Text(action.accessibleLabel()) }
            }
            OutlinedButton(onClick = { onOpenDetail(item.id) }) { Text("View surrounding context") }
        }
    }
}

fun DecisionAction.accessibleLabel(): String = when (this) {
    DecisionAction.ACCEPT_PROPOSED_CHANGE -> "Accept proposed change"
    DecisionAction.RETAIN_SOURCE_MEANING -> "Retain source meaning"
    DecisionAction.MANUAL_EDIT -> "Manually edit"
    DecisionAction.MARK_EXPECTED_LANGUAGE -> "Mark as expected language"
    DecisionAction.EXCLUDE_REGION -> "Exclude region"
    DecisionAction.KEEP_FULL_URL -> "Keep full URL"
    DecisionAction.REMOVE_KNOWN_TRACKING -> "Remove known tracking fields"
    DecisionAction.REMOVE_QUERY_AND_FRAGMENT -> "Remove query and fragment"
    DecisionAction.KEEP_ORIGIN_ONLY -> "Keep origin only"
    DecisionAction.MAKE_NON_CLICKABLE -> "Make non-clickable domain text"
    DecisionAction.REMOVE_URL -> "Remove URL"
    DecisionAction.REORDER -> "Reorder"
    DecisionAction.MERGE -> "Merge"
    DecisionAction.SPLIT -> "Split"
    DecisionAction.MARK_DECORATIVE -> "Mark decorative"
    DecisionAction.MARK_HIDDEN -> "Mark hidden"
    DecisionAction.LOCK_EXACT_WORDING -> "Lock exact wording"
    DecisionAction.ACCEPT_LOWER_ASSURANCE -> "Accept lower assurance"
}
