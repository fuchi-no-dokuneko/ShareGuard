package app.shareguard.feature.entry

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
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.shareguard.core.model.OutputMode
import app.shareguard.core.ui.ClaimLanguage
import app.shareguard.core.ui.LimitationCard

@Composable
fun EntryScreen(
    onEnterText: () -> Unit,
    onChooseImage: () -> Unit,
    onOpenSavedResults: () -> Unit,
    onOpenThreatModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "Canonical Share",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(8.dp))
                Text("Build a reviewed canonical representation before sharing text or one image.")
            }
            item {
                Button(onClick = onEnterText, modifier = Modifier.fillMaxWidth()) {
                    Text("Paste or enter text")
                }
            }
            item {
                Button(onClick = onChooseImage, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose one image")
                }
            }
            item {
                OutlinedButton(onClick = onOpenSavedResults, modifier = Modifier.fillMaxWidth()) {
                    Text("Saved Results")
                }
            }
            item { Text("Content can also be shared to this app from another Android app.") }
            item { LimitationCard("Default privacy boundary", ClaimLanguage.LOCAL_PROCESSING) }
            item {
                OutlinedButton(onClick = onOpenThreatModel) { Text("About and threat model") }
            }
        }
    }
}

@Composable
fun TextInputScreen(
    text: String,
    revealCharacters: Boolean,
    selectedOutput: OutputMode,
    onTextChange: (String) -> Unit,
    onRevealCharactersChange: (Boolean) -> Unit,
    onChooseOutput: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Text source", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text("Hidden characters remain present until they are explicitly reviewed.")
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = { Text("Plain text") },
                supportingText = {
                    Text("${text.codePointCount(0, text.length)} Unicode code points")
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = revealCharacters, onCheckedChange = onRevealCharactersChange)
                Text("Reveal spaces, line endings and invisible characters during review")
            }
            OutlinedButton(onClick = onChooseOutput, modifier = Modifier.fillMaxWidth()) {
                Text("Output: ${selectedOutput.name.lowercase().replace('_', ' ')}")
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onSubmit, enabled = text.isNotEmpty()) { Text("Accept text") }
            }
        }
    }
}

@Composable
fun ImageInputPreviewScreen(
    summary: AcceptedImageSummary,
    reviewWarning: String,
    transientPreview: ImageBitmap?,
    selectedOutput: OutputMode,
    onChooseOutput: () -> Unit,
    onContinue: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Text("Image source", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
            item { LimitationCard("Source is untrusted", "The format and dimensions below were detected from the private snapshot, not accepted from its filename or MIME claim.") }
            item { LimitationCard("Local OCR review required", reviewWarning) }
            if (transientPreview != null) {
                item {
                    Image(
                        bitmap = transientPreview,
                        contentDescription = "Selected source image preview for OCR comparison",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                SummaryRow("Detected format", summary.detectedFormat)
                SummaryRow("Pixel dimensions", "${summary.pixelWidth} × ${summary.pixelHeight}")
                SummaryRow("Source metadata entries", summary.metadataEntryCount.toString())
                SummaryRow("Animated", if (summary.animated) "Yes — unsupported" else "No")
            }
            item {
                OutlinedButton(onClick = onChooseOutput, modifier = Modifier.fillMaxWidth()) {
                    Text("Output: ${selectedOutput.name.lowercase().replace('_', ' ')}")
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onReject) { Text("Reject source") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onContinue, enabled = !summary.animated) { Text("Continue") }
                }
            }
        }
    }
}

@Composable
fun OutputChoiceScreen(
    selected: OutputMode,
    onSelect: (OutputMode) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    allowedModes: Set<OutputMode> = OutputMode.entries.toSet(),
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Choose output", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
            items(outputChoices.filter { it.mode in allowedModes }, key = { it.mode }) { choice ->
                Card(
                    onClick = { onSelect(choice.mode) },
                    border = if (selected == choice.mode) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        RadioButton(selected = selected == choice.mode, onClick = { onSelect(choice.mode) })
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                choice.title + if (choice.experimental) " — Experimental" else "",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("Preserves: ${choice.preserves}")
                            Text("Discards: ${choice.discards}")
                            Text("Review: ${choice.reviewBurden}")
                            Text("Maximum assurance: ${choice.assuranceCeiling.name}")
                        }
                    }
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onContinue) { Text("Choose preset") }
                }
            }
        }
    }
}

@Composable
fun PresetChoiceScreen(
    selectedId: String,
    onSelect: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Choose a versioned workflow", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
            items(presetChoices, key = { it.id }) { preset ->
                Card(
                    onClick = { onSelect(preset.id) },
                    border = if (preset.id == selectedId) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        RadioButton(selected = preset.id == selectedId, onClick = { onSelect(preset.id) })
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(preset.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(preset.id, style = MaterialTheme.typography.labelSmall)
                            Text("Input: ${preset.contentType} · Output: ${preset.outputType}")
                            Text("Review categories: ${preset.reviewCategories}")
                            Text("Source pixels may remain: ${if (preset.sourcePixelsMayRemain) "Yes" else "No"}")
                            Text("URL behavior: ${preset.urlBehavior}")
                        }
                    }
                }
            }
            item {
                HorizontalDivider()
                Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onContinue, enabled = selectedId.isNotBlank()) { Text("Open workflow") }
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(value)
    }
}
