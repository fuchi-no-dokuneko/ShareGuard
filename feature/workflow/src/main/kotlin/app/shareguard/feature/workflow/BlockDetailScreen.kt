package app.shareguard.feature.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp

enum class BlockDetailTab(val label: String) {
    PURPOSE("Purpose"), FINDINGS("Findings"), CHANGES("Changes"), SETTINGS("Settings"),
    VERIFICATION("Verification"), TECHNICAL("Technical details"),
}

data class BlockDetailUiState(
    val blockId: String,
    val name: String,
    val selectedTab: BlockDetailTab,
    val rows: List<Pair<String, String>>,
    val limitation: String,
)

@Composable
fun BlockDetailScreen(
    state: BlockDetailUiState,
    onSelectTab: (BlockDetailTab) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(state.blockId)
            }
            PrimaryScrollableTabRow(selectedTabIndex = state.selectedTab.ordinal) {
                BlockDetailTab.entries.forEach { tab ->
                    Tab(
                        selected = state.selectedTab == tab,
                        onClick = { onSelectTab(tab) },
                        text = { Text(tab.label, maxLines = 2) },
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.rows) { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                        Text(value)
                    }
                }
                item {
                    Text("Limit", style = MaterialTheme.typography.labelLarge)
                    Text(state.limitation)
                }
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.padding(16.dp)) { Text("Back to workflow") }
        }
    }
}
