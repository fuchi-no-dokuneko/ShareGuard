package app.shareguard.canonical

import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.shareguard.core.model.SavedResultId
import app.shareguard.core.security.AndroidKeystoreAesGcmKeyProvider
import app.shareguard.core.security.KeyAlias
import app.shareguard.core.security.LogicalKeyDeletionResult
import app.shareguard.core.storage.SavedResultDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedResultPlatformInstrumentedTest {
    @Test
    fun unavailableAndroidKeystoreKeyQuarantinesResultAndBlocksManagedShare() {
        val application = ApplicationProvider.getApplicationContext<ShareGuardApplication>()
        val id = persistTextResult("approved key unavailability probe")
        try {
            val databasePath = application.getDatabasePath(SavedResultDatabase.DEFAULT_NAME)
            val keyAlias = SQLiteDatabase.openDatabase(
                databasePath.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                database.rawQuery(
                    "SELECT key_alias FROM saved_results WHERE saved_result_id = ?",
                    arrayOf(id),
                ).use { cursor ->
                    check(cursor.moveToFirst()) { "SAVED_RESULT_KEY_REFERENCE_MISSING" }
                    cursor.getString(0)
                }
            }
            assertEquals(
                LogicalKeyDeletionResult.DELETED,
                AndroidKeystoreAesGcmKeyProvider().delete(KeyAlias(keyAlias)),
            )

            val result = runBlocking { application.container.revalidator.revalidate(SavedResultId(id)) }
            assertFalse(result.valid)
            assertEquals("KEY_UNAVAILABLE", result.reasonCode)
            assertNull(runBlocking { application.container.repository.findVisible(SavedResultId(id)) })
        } finally {
            runBlocking { application.container.deletionService.delete(SavedResultId(id)) }
        }
    }

    @Test
    fun multiSelectDeletionRemovesBothItemsFromDurableStorageAndTheSavedResultsUi() {
        val application = ApplicationProvider.getApplicationContext<ShareGuardApplication>()
        val ids = listOf(
            persistTextResult("approved bulk deletion probe alpha"),
            persistTextResult("approved bulk deletion probe beta"),
        )
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.openSavedResultsForTest() }
                waitUntil(UI_TIMEOUT_MILLIS) {
                    var visible = false
                    scenario.onActivity { activity ->
                        visible = activity.currentUiStateForTest().savedItems.map { it.id }.containsAll(ids)
                    }
                    visible
                }
                scenario.onActivity { activity ->
                    ids.forEach(activity::selectSavedResultForTest)
                    activity.requestDeleteSelectedForTest()
                }
                waitUntil(UI_TIMEOUT_MILLIS) {
                    var ready = false
                    scenario.onActivity { activity ->
                        ready = activity.currentUiStateForTest().route == AppRoute.DELETE_CONFIRMATION
                    }
                    ready
                }
                scenario.onActivity { it.confirmDeletionForTest() }
                waitUntil(UI_TIMEOUT_MILLIS) {
                    runBlocking { ids.all { application.container.repository.findVisible(SavedResultId(it)) == null } }
                }
                scenario.onActivity { activity ->
                    assertTrue(activity.currentUiStateForTest().savedItems.none { it.id in ids })
                }
            }
        } finally {
            runBlocking { ids.forEach { application.container.deletionService.delete(SavedResultId(it)) } }
        }
    }

    @Test
    fun savedResultShareLaunchesTheAndroidSystemChooser() {
        val application = ApplicationProvider.getApplicationContext<ShareGuardApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor: Instrumentation.ActivityMonitor = instrumentation.addMonitor(
            IntentFilter(Intent.ACTION_CHOOSER),
            null,
            true,
        )
        val id = persistTextResult("approved sharesheet launch probe")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.openSavedResultsForTest()
                    activity.requestSavedResultShareForTest(id)
                }
                waitUntil(UI_TIMEOUT_MILLIS) {
                    var ready = false
                    scenario.onActivity { activity ->
                        ready = activity.currentUiStateForTest().route == AppRoute.PRE_SHARE
                    }
                    ready
                }
                scenario.onActivity { it.confirmManagedShareForTest() }
                waitUntil(UI_TIMEOUT_MILLIS) { monitor.hits > 0 }
                assertEquals(1, monitor.hits)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            runBlocking { application.container.deletionService.delete(SavedResultId(id)) }
        }
    }

    private fun persistTextResult(text: String): String {
        var id: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.runCanonicalTextWorkflowForTest(text) }
            waitUntil(WORKFLOW_TIMEOUT_MILLIS) {
                scenario.onActivity { activity -> id = activity.currentUiStateForTest().result?.savedResultId }
                id != null
            }
        }
        return requireNotNull(id) { "SAVED_RESULT_PERSISTENCE_TIMEOUT" }
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_MILLIS)
        }
        assertTrue("condition was not met before timeout", condition())
    }

    private companion object {
        const val WORKFLOW_TIMEOUT_MILLIS = 45_000L
        const val UI_TIMEOUT_MILLIS = 15_000L
        const val POLL_MILLIS = 100L
    }
}
