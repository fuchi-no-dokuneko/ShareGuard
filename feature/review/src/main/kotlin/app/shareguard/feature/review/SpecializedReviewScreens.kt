package app.shareguard.feature.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
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
import app.shareguard.core.ui.SourcePixelNotice

@Composable
fun CharacterReviewScreen(
    item: CharacterReviewUiModel,
    onAcceptSuggestion: () -> Unit,
    onRetain: () -> Unit,
    onMarkExpectedLanguage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ReviewDetailScaffold("Character review", modifier) {
        DetailRow("Visual glyph", item.glyph)
        DetailRow("Unicode name", item.unicodeName)
        DetailRow("Code point", item.codePoint)
        DetailRow("Script", item.script)
        DetailRow("Neighbouring characters", item.neighbors)
        DetailRow("Suggested canonical form", item.suggestion ?: "No automatic suggestion")
        DetailRow("OCR disagreement", if (item.ocrDisagreed) "Yes" else "No")
        DetailRow("Confusable skeleton", item.confusableSkeleton ?: "Not available")
        DetailRow("URL or identifier impact", item.identifierOrUrlImpact ?: "None detected")
        OutlinedButton(onClick = onRetain, modifier = Modifier.fillMaxWidth()) { Text("Retain source meaning") }
        OutlinedButton(onClick = onMarkExpectedLanguage, modifier = Modifier.fillMaxWidth()) { Text("Mark as expected language") }
        Button(onClick = onAcceptSuggestion, enabled = item.suggestion != null, modifier = Modifier.fillMaxWidth()) {
            Text("Accept suggested form")
        }
    }
}

@Composable
fun UrlReviewScreen(
    item: UrlReviewUiModel,
    selectedAction: DecisionAction?,
    onChoose: (DecisionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    ReviewDetailScaffold("URL review", modifier) {
        DetailRow("Original display text", item.displayText)
        DetailRow("Scheme", item.scheme ?: "None")
        DetailRow("Host", item.host)
        DetailRow("Registrable domain", item.registrableDomain ?: "Unknown")
        DetailRow("Subdomain", item.subdomain ?: "None")
        DetailRow("Path", item.path)
        DetailRow("Query", item.query ?: "None")
        DetailRow("Fragment", item.fragment ?: "None")
        DetailRow("User information", if (item.userInfoPresent) "Present — review required" else "Not present")
        DetailRow("Suspected high-entropy components", item.highEntropyComponents.joinToString().ifEmpty { "None detected" })
        DetailRow("Proposed result", item.proposedResult)
        DetailRow("Predicted functionality impact", item.functionalityImpact)
        listOf(
            DecisionAction.KEEP_FULL_URL,
            DecisionAction.REMOVE_KNOWN_TRACKING,
            DecisionAction.REMOVE_QUERY_AND_FRAGMENT,
            DecisionAction.KEEP_ORIGIN_ONLY,
            DecisionAction.MAKE_NON_CLICKABLE,
            DecisionAction.REMOVE_URL,
            DecisionAction.MANUAL_EDIT,
        ).forEach { action ->
            OutlinedButton(
                onClick = { onChoose(action) },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedAction != action,
            ) { Text(action.accessibleLabel()) }
        }
    }
}

@Composable
fun ImageRegionReviewScreen(
    item: ImageRegionReviewUiModel,
    onChoose: (DecisionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    ReviewDetailScaffold("Image region review", modifier) {
        DetailRow("Region", item.regionLabel)
        DetailRow("Location", item.boundsDescription)
        if (item.sourcePixelRetention) SourcePixelNotice(1)
        listOf(
            DecisionAction.EXCLUDE_REGION,
            DecisionAction.MARK_DECORATIVE,
            DecisionAction.RETAIN_SOURCE_MEANING,
            DecisionAction.ACCEPT_LOWER_ASSURANCE,
        ).forEach { action ->
            OutlinedButton(onClick = { onChoose(action) }, modifier = Modifier.fillMaxWidth()) {
                Text(action.accessibleLabel())
            }
        }
    }
}

@Composable
private fun ReviewDetailScaffold(
    title: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
            item { content() }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(value)
    }
}
