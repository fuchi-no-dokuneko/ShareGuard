package app.shareguard.canonical

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import app.shareguard.core.model.ImportClockConfidence
import app.shareguard.core.model.SavedResultId
import app.shareguard.core.session.AdvisoryImportTimer
import app.shareguard.core.session.AdvisoryTimerAnomaly
import app.shareguard.core.session.AndroidMonotonicClockSource
import app.shareguard.core.session.SystemWallClockSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two-phase probe run by the destructive emulator lifecycle lane. The lane invokes [seedBeforeReboot],
 * reboots the emulator without reinstalling either APK, then invokes [verifyAfterReboot]. Large tests
 * are excluded from the ordinary connected-test lane so the phase boundary cannot be faked by ordering.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class DestructiveDeviceLifecycleInstrumentedTest {
    @Test
    fun seedBeforeReboot() {
        val application = ApplicationProvider.getApplicationContext<ShareGuardApplication>()
        val preferences = application.getSharedPreferences(PROBE_PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(KEY_SAVED_RESULT_ID, null)?.let { staleId ->
            runBlocking { application.container.deletionService.delete(SavedResultId(staleId)) }
        }
        preferences.edit().clear().commit()

        var savedResultId: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.runCanonicalTextWorkflowForTest("approved reboot lifecycle probe")
            }
            val deadline = SystemClock.elapsedRealtime() + WORKFLOW_TIMEOUT_MILLIS
            while (SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity ->
                    savedResultId = activity.currentUiStateForTest().result?.savedResultId
                }
                if (savedResultId != null) break
                SystemClock.sleep(POLL_MILLIS)
            }
        }

        val id = requireNotNull(savedResultId) { "REBOOT_PROBE_PERSISTENCE_TIMEOUT" }
        val persisted = runBlocking { application.container.repository.findVisible(SavedResultId(id)) }
        assertNotNull("seeded result was not durably visible before reboot", persisted)
        assertNotNull("seeded import did not retain its boot-scoped monotonic anchor", persisted?.importAnchor?.monotonic)
        assertTrue(preferences.edit().putString(KEY_SAVED_RESULT_ID, id).commit())
    }

    @Test
    fun verifyAfterReboot() {
        val application = ApplicationProvider.getApplicationContext<ShareGuardApplication>()
        val preferences = application.getSharedPreferences(PROBE_PREFERENCES, Context.MODE_PRIVATE)
        val id = requireNotNull(preferences.getString(KEY_SAVED_RESULT_ID, null)) {
            "REBOOT_PROBE_SEED_MISSING"
        }
        try {
            val persisted = runBlocking {
                application.container.awaitStartupMaintenance()
                application.container.repository.findVisible(SavedResultId(id))
            }
            assertNotNull("committed result was unavailable after device reboot", persisted)
            val result = requireNotNull(persisted)
            val currentBoot = EstimatedBootSessionReferenceSource().current()
            assertNotNull(result.importAnchor.bootSessionReference)
            assertNotNull(currentBoot)
            assertNotEquals(
                "the emulator did not cross a real boot boundary",
                result.importAnchor.bootSessionReference,
                currentBoot,
            )

            val reading = AdvisoryImportTimer(
                anchor = result.importAnchor,
                wallClock = SystemWallClockSource,
                monotonicClock = AndroidMonotonicClockSource,
                bootSessionReferenceSource = EstimatedBootSessionReferenceSource(),
            ).sample()
            assertTrue(reading.elapsed.value >= 0L)
            assertFalse(reading.confidence == ImportClockConfidence.MONOTONIC_ACTIVE)
            assertTrue(
                reading.anomaly == AdvisoryTimerAnomaly.BOOT_CHANGED ||
                    reading.anomaly == AdvisoryTimerAnomaly.WALL_CLOCK_ROLLBACK,
            )

            var restoredElapsed: String? = null
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.openSavedResultsForTest() }
                val deadline = SystemClock.elapsedRealtime() + RESTORE_TIMEOUT_MILLIS
                while (SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { activity ->
                        restoredElapsed = activity.currentUiStateForTest().savedItems
                            .singleOrNull { it.id == id }
                            ?.elapsedSinceImport
                    }
                    if (restoredElapsed != null) break
                    SystemClock.sleep(POLL_MILLIS)
                }
            }
            assertNotNull("Saved Results UI did not restore the rebooted item", restoredElapsed)
            assertFalse("restored elapsed reference was negative", restoredElapsed.orEmpty().contains('-'))
        } finally {
            runBlocking { application.container.deletionService.delete(SavedResultId(id)) }
            preferences.edit().clear().commit()
        }
    }

    private companion object {
        const val PROBE_PREFERENCES = "destructive-lifecycle-probe-v1"
        const val KEY_SAVED_RESULT_ID = "saved_result_id"
        const val WORKFLOW_TIMEOUT_MILLIS = 45_000L
        const val RESTORE_TIMEOUT_MILLIS = 15_000L
        const val POLL_MILLIS = 100L
    }
}
