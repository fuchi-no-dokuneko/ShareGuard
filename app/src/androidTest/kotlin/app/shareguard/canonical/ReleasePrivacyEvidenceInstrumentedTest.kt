package app.shareguard.canonical

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleasePrivacyEvidenceInstrumentedTest {
    @Test
    fun strictOfflineCanaryWorkflowPersistsWithoutSensitiveLogging() {
        assertTrue("CI must explicitly enable release privacy evidence", BuildConfig.RELEASE_PRIVACY_EVIDENCE)
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission(Manifest.permission.INTERNET, context.packageName),
        )

        clearDeviceLog()
        val canary = "SHAREGUARD_LOG_CANARY_${UUID.randomUUID().toString().replace("-", "")}" 
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.runCanonicalTextWorkflowForTest(canary) }
            val deadline = SystemClock.elapsedRealtime() + 45_000L
            var state: ShareGuardUiState? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { state = it.currentUiStateForTest() }
                if (state?.route in setOf(AppRoute.RESULT, AppRoute.ERROR)) break
                SystemClock.sleep(100L)
            }
            assertEquals(state?.errorCode ?: "workflow did not complete", AppRoute.RESULT, state?.route)
            assertNotNull(state?.result?.savedResultId)
        }

        val logs = readDeviceLog()
        assertTrue("source canary appeared in device logs", !logs.contains(canary))
    }

    @Test
    fun sealedImageInspectionExecutesBundledOcrLocally() = runBlocking {
        assertTrue(BuildConfig.RELEASE_PRIVACY_EVIDENCE)
        val bitmap = Bitmap.createBitmap(720, 240, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { encoded ->
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText(
                    "OFFLINE IMAGE 456",
                    24f,
                    150f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 72f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    },
                )
            }
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded))
            encoded.toByteArray()
        }
        bitmap.eraseColor(Color.TRANSPARENT)
        bitmap.recycle()
        try {
            val inspection = LocalImageImportWorkflow().inspect(bytes, "image/png")
            try {
                assertEquals("PNG", inspection.summary.detectedFormat)
                assertTrue(inspection.provisionalOcrText.contains("OFFLINE"))
            } finally {
                inspection.transientPreview.eraseColor(Color.TRANSPARENT)
                inspection.transientPreview.recycle()
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun clearDeviceLog() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("logcat -c")
            .use(ParcelFileDescriptor::close)
    }

    private fun readDeviceLog(): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("logcat -d")
        return descriptor.use {
            FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
        }
    }
}
