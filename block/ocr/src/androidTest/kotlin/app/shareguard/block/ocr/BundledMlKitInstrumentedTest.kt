package app.shareguard.block.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledMlKitInstrumentedTest {
    @Test
    fun everyDeclaredScriptCreatesOnlyBundledLocalRecognizer() {
        OcrScript.entries.forEach { script ->
            val recognizer = MlKitTextRecognizerAdapter.bundled(script)
            try {
                assertEquals(script, recognizer.script)
                assertEquals(OcrExecutionMode.BUNDLED_LOCAL, recognizer.executionMode)
            } finally {
                recognizer.close()
            }
        }
    }

    @Test
    fun barcodeRecognizerIsBundledLocal() {
        val recognizer = MlKitBundledBarcodeRecognizer.bundled()
        try {
            assertEquals(OcrExecutionMode.BUNDLED_LOCAL, recognizer.executionMode)
        } finally {
            recognizer.close()
        }
    }
}
