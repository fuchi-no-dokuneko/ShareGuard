package app.shareguard.canonical

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntentLifecycleInstrumentedTest {
    @Test
    fun freshProcessDoesNotRestoreTransientSourceText() {
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
}
