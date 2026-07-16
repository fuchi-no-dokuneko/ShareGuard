package app.shareguard.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.InputKind
import app.shareguard.core.model.OutputMode

enum class RepresentationStage { SOURCE, CANONICAL, OUTPUT }

@Composable
fun PersistentStatusHeader(
    inputKind: InputKind?,
    outputMode: OutputMode?,
    assuranceClass: AssuranceClass?,
    stage: RepresentationStage,
    elapsedLabel: String?,
    onClearSession: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stage.name.lowercase().replaceFirstChar(Char::uppercase),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                if (onClearSession != null) {
                    OutlinedButton(onClick = onClearSession) { Text("Clear session") }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip("Input", inputKind?.name ?: "Not accepted")
                StatusChip("Output", outputMode?.accessibleName() ?: "Not selected")
                StatusChip("Assurance", ClaimLanguage.assuranceLabel(assuranceClass))
                if (elapsedLabel != null) StatusChip("Time since import", elapsedLabel)
            }
        }
    }
}

@Composable
fun StatusChip(label: String, value: String, modifier: Modifier = Modifier) {
    AssistChip(
        modifier = modifier.semantics { contentDescription = "$label: $value" },
        onClick = {},
        enabled = false,
        label = { Text("$label · $value") },
    )
}

@Composable
fun LimitationCard(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun PrimaryAndSecondaryActions(
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
    ) {
        OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
        Button(onClick = onPrimary, enabled = primaryEnabled) { Text(primaryLabel) }
    }
}

@Composable
fun SourcePixelNotice(regionCount: Int, modifier: Modifier = Modifier) {
    require(regionCount > 0)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ShareGuardColors.warningContainer),
    ) {
        Text(
            text = "$regionCount approved region${if (regionCount == 1) "" else "s"} still " +
                "depend on source pixels. These regions are listed in the verification report.",
            modifier = Modifier.padding(16.dp),
            color = androidx.compose.ui.graphics.Color(0xFF241A00),
        )
    }
}

fun formatElapsedMillis(durationMillis: Long): String {
    val seconds = durationMillis.coerceAtLeast(0) / 1_000L
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> "$days d $hours h"
        hours > 0 -> "$hours h $minutes min"
        minutes > 0 -> "$minutes min"
        else -> "less than a minute"
    }
}

private fun OutputMode.accessibleName(): String = when (this) {
    OutputMode.TEXT -> "Canonical text"
    OutputMode.REBUILT_IMAGE -> "Rebuilt image"
    OutputMode.BOTH -> "Text and rebuilt image"
    OutputMode.DERIVATIVE_IMAGE -> "Appearance-preserving derivative"
}
