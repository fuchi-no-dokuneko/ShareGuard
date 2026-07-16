package app.shareguard.canonical

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrossAppSharingInstrumentedTest {
    @Test
    fun temporaryGrantLetsSecondPackageReadExactBytesButNeverOpenForWrite() {
        val targetContext = ApplicationProvider.getApplicationContext<Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        assertFalse(targetContext.packageName == testContext.packageName)
        val bytes = "reviewed managed artifact bytes".encodeToByteArray()
        val shareFile = File(
            File(targetContext.cacheDir, "managed-share-v2/cross-app-result").apply { mkdirs() },
            "share-cross-app.share",
        ).apply {
            writeBytes(bytes)
            setReadable(false, false)
            check(setReadable(true, true))
            check(setWritable(false, false))
        }
        try {
            val uri = FileProvider.getUriForFile(
                targetContext,
                "${targetContext.packageName}.managed-share",
                shareFile,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                component = ComponentName(testContext.packageName, TestShareReceiverActivity::class.java.name)
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(targetContext.contentResolver, "Managed Artifact", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            ActivityScenario.launchActivityForResult<TestShareReceiverActivity>(intent).use { scenario ->
                val result = scenario.result
                assertEquals(Activity.RESULT_OK, result.resultCode)
                assertEquals(bytes.size, result.resultData?.getIntExtra(TestShareReceiverActivity.EXTRA_BYTE_COUNT, -1))
                assertEquals(bytes.sha256(), result.resultData?.getStringExtra(TestShareReceiverActivity.EXTRA_SHA_256))
                assertFalse(
                    result.resultData?.getBooleanExtra(
                        TestShareReceiverActivity.EXTRA_WRITE_OPEN_SUCCEEDED,
                        true,
                    ) ?: true,
                )
            }
            assertTrue(shareFile.exists())
        } finally {
            bytes.fill(0)
            shareFile.delete()
            shareFile.parentFile?.delete()
        }
    }

    @Test
    fun secondPackageCannotReadTheManagedUriWithoutAnExplicitTemporaryGrant() {
        val targetContext = ApplicationProvider.getApplicationContext<Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val bytes = "grant boundary probe".encodeToByteArray()
        val shareFile = File(
            File(targetContext.cacheDir, "managed-share-v2/no-grant-result").apply { mkdirs() },
            "share-no-grant.share",
        ).apply {
            writeBytes(bytes)
            setReadable(false, false)
            check(setReadable(true, true))
            check(setWritable(false, false))
        }
        try {
            val uri = FileProvider.getUriForFile(
                targetContext,
                "${targetContext.packageName}.managed-share",
                shareFile,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                component = ComponentName(testContext.packageName, TestShareReceiverActivity::class.java.name)
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
            }

            ActivityScenario.launchActivityForResult<TestShareReceiverActivity>(intent).use { scenario ->
                assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
                assertEquals(
                    "READ_DENIED",
                    scenario.result.resultData?.getStringExtra(TestShareReceiverActivity.EXTRA_FAILURE_CODE),
                )
            }
        } finally {
            bytes.fill(0)
            shareFile.delete()
            shareFile.parentFile?.delete()
        }
    }

    @Test
    fun FileProviderRefusesAnyFileOutsideTheManagedShareRoot() {
        val targetContext = ApplicationProvider.getApplicationContext<Context>()
        val sourceLikeFile = File(targetContext.cacheDir, "transient-sessions-v1/source.bin").apply {
            parentFile?.mkdirs()
            writeText("TRANSIENT_SOURCE_CANARY")
        }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(
                    targetContext,
                    "${targetContext.packageName}.managed-share",
                    sourceLikeFile,
                )
            }
        } finally {
            sourceLikeFile.delete()
            sourceLikeFile.parentFile?.delete()
        }
    }

    private fun ByteArray.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return try {
            digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } finally {
            digest.fill(0)
        }
    }
}
