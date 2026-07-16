package app.shareguard.canonical

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import app.shareguard.core.model.SavedResultId
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class SavedResultsAccessibilityInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun elapsedSinceImportHasNaturalAccessibilitySemanticsAndCanReceiveFocus() {
        var id: String? = null
        try {
            composeRule.runOnUiThread {
                composeRule.activity.runCanonicalTextWorkflowForTest("approved accessibility elapsed probe")
            }
            composeRule.waitUntil(WORKFLOW_TIMEOUT_MILLIS) {
                id = composeRule.activity.currentUiStateForTest().result?.savedResultId
                id != null
            }
            composeRule.runOnUiThread { composeRule.activity.openSavedResultsForTest() }
            composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
                composeRule.activity.currentUiStateForTest().savedItems.any { it.id == id }
            }

            val naturalElapsedDescription = SemanticsMatcher("natural time-since-import description") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)
                    ?.any { description -> description.contains("time since import", ignoreCase = true) }
                    ?: false
            }
            val elapsedNode = composeRule.onNode(naturalElapsedDescription)
            elapsedNode.assertExists()
            elapsedNode.performSemanticsAction(SemanticsActions.RequestFocus) { requestFocus -> requestFocus() }
            elapsedNode.assertIsFocused()
        } finally {
            id?.let { savedId ->
                runBlocking {
                    val application = composeRule.activity.application as ShareGuardApplication
                    application.container.deletionService.delete(SavedResultId(savedId))
                }
            }
        }
    }

    private companion object {
        const val WORKFLOW_TIMEOUT_MILLIS = 45_000L
        const val UI_TIMEOUT_MILLIS = 15_000L
    }
}
