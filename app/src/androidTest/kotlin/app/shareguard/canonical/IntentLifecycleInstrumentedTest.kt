package app.shareguard.canonical

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntentLifecycleInstrumentedTest {
    @Test
    fun newActivityInstanceDoesNotRestoreTransientSourceText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val incoming = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "process-private canary")
        }
        ActivityScenario.launch<MainActivity>(incoming).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("process-private canary", activity.currentUiStateForTest().text)
            }
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.currentUiStateForTest().text.isEmpty())
                assertEquals(AppRoute.HOME, activity.currentUiStateForTest().route)
            }
        }
    }

    @Test
    fun consumedShareIntentIsSanitizedInActivityMemory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val incoming = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "process-private canary")
        }

        ActivityScenario.launch<MainActivity>(incoming).use { scenario ->
            scenario.onActivity { activity ->
                assertNull(activity.intent.getStringExtra(Intent.EXTRA_TEXT))
                assertNull(activity.intent.type)
                assertNull(activity.intent.clipData)
            }
        }
    }

    @Test
    fun committedSavedResultRemainsVisibleToANewActivityAndViewModel() {
        var savedResultId: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.runCanonicalTextWorkflowForTest("restart-visible managed artifact") }
                val deadline = SystemClock.elapsedRealtime() + 45_000L
                while (SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { savedResultId = it.currentUiStateForTest().result?.savedResultId }
                    if (savedResultId != null) break
                    SystemClock.sleep(100L)
                }
            }
            assertNotNull("workflow did not persist a Saved Result", savedResultId)

            var visible = false
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.openSavedResultsForTest() }
                val deadline = SystemClock.elapsedRealtime() + 15_000L
                while (SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { activity ->
                        visible = activity.currentUiStateForTest().savedItems.any { it.id == savedResultId }
                    }
                    if (visible) break
                    SystemClock.sleep(100L)
                }
            }
            assertTrue("durably committed Saved Result was not restored", visible)
        } finally {
            savedResultId?.let { id ->
                kotlinx.coroutines.runBlocking {
                    val application = ApplicationProvider.getApplicationContext<ShareGuardApplication>()
                    application.container.deletionService.delete(app.shareguard.core.model.SavedResultId(id))
                }
            }
        }
    }
}
