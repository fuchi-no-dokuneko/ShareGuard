package app.shareguard.canonical

import android.os.Bundle
import android.content.Intent
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.shareguard.core.model.OutputMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    private val viewModel: ShareGuardViewModel by viewModels()
    private val externalCopyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.completeExternalExport(result.data?.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (viewModel.state.value.protectSensitiveScreens) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContent {
            ShareGuardApp(viewModel)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is AppEffect.LaunchIntent -> startActivity(effect.intent)
                        is AppEffect.CreateExternalCopy -> externalCopyLauncher.launch(effect.intent)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.map { it.protectSensitiveScreens }.distinctUntilChanged().collect { protect ->
                    if (protect) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }
        consumeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(source: Intent) {
        viewModel.consumeIncomingIntent(source)
        if (source.action == Intent.ACTION_SEND) {
            source.removeExtra(Intent.EXTRA_TEXT)
            source.removeExtra(Intent.EXTRA_STREAM)
            source.clipData = null
            source.action = Intent.ACTION_MAIN
            source.type = null
        }
    }

    internal fun currentUiStateForTest(): ShareGuardUiState = viewModel.state.value

    internal fun runCanonicalTextWorkflowForTest(text: String, outputMode: OutputMode = OutputMode.TEXT) {
        viewModel.openTextEntry(text)
        viewModel.chooseOutput(outputMode)
        viewModel.submitText()
        lifecycleScope.launch {
            while (true) {
                val current = viewModel.state.value
                when (current.route) {
                    AppRoute.FINDING_REVIEW -> {
                        current.reviewItems.forEach { item ->
                            item.allowedActions.firstOrNull()?.let { action ->
                                viewModel.chooseReviewAction(item.id, action)
                            }
                        }
                        viewModel.applyReviewDecisions()
                    }
                    AppRoute.SEMANTIC_DIFF -> viewModel.verifyAndSave()
                    AppRoute.RESULT, AppRoute.ERROR -> return@launch
                    else -> Unit
                }
                delay(25L)
            }
        }
    }
}
