package app.shareguard.canonical

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ShareGuardApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Canonical Share", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Create a newly normalized representation of text or screenshots. " +
                            "Processing stays on this device in the default build.",
                    )
                    Button(onClick = { }) {
                        Text("Paste or enter text")
                    }
                    Button(onClick = { }) {
                        Text("Choose one image")
                    }
                    Text(
                        "Verification describes the processing performed. It does not guarantee " +
                            "anonymity or removal of every unknown signal.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
