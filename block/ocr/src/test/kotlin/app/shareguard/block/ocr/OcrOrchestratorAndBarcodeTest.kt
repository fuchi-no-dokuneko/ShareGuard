package app.shareguard.block.ocr

import app.shareguard.block.image.ArgbPixelImage
import app.shareguard.core.model.ImageRegionPolicy
import app.shareguard.core.model.NormalizedRect
import app.shareguard.core.model.SafeSummary
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class OcrOrchestratorAndBarcodeTest {
    @Test
    fun `local OCR run closes recognizer and every temporary view on success`() = runBlocking {
        val observer = RecordingObserver()
        val recognizer = FakeTextRecognizer()
        val source = ArgbPixelImage(2, 2, IntArray(4) { 0xffffffff.toInt() })

        val report = LocalOcrOrchestrator(observer = observer).run(source, plan(), listOf(recognizer))

        assertThat(report.regions.single().accepted).isTrue()
        assertThat(report.expectedObservationSources).isEqualTo(2)
        assertThat(recognizer.closed).isTrue()
        assertThat(observer.deleted).containsExactly("ocr-view-1", "ocr-view-2")
        source.close()
    }

    @Test
    fun `native-style cancellation invalidates partial observations and still deletes all views`() {
        val observer = RecordingObserver()
        val recognizer = FakeTextRecognizer(cancelOnCall = 2)
        val source = ArgbPixelImage(2, 2, IntArray(4))

        assertThrows(CancellationException::class.java) {
            runBlocking { LocalOcrOrchestrator(observer = observer).run(source, plan(), listOf(recognizer)) }
        }

        assertThat(observer.invalidated).containsExactly(1)
        assertThat(observer.deleted).containsExactly("ocr-view-1", "ocr-view-2")
        assertThat(recognizer.closed).isTrue()
        source.close()
    }

    @Test
    fun `validation and view allocation failure also release owned recognizers`() {
        val recognizer = FakeTextRecognizer(script = OcrScript.LATIN)
        val invalidPlan = plan().copy(scripts = setOf(OcrScript.KOREAN))
        val source = ArgbPixelImage(2, 2, IntArray(4))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { LocalOcrOrchestrator().run(source, invalidPlan, listOf(recognizer)) }
        }

        assertThat(recognizer.closed).isTrue()
        source.close()
    }

    @Test
    fun `decoded barcode passes Unicode and URL routing while receipts remain content-free`() = runBlocking {
        var gatedText: String? = null
        var routedText: String? = null
        val service = BarcodeRoutingService(
            BarcodeUnicodeGate { raw ->
                gatedText = raw
                UnicodeGatedBarcodeValue(raw.trim())
            },
            CanonicalTextAndUrlRouter { value ->
                routedText = value.text
                BarcodeRoutingReceipt("URL_POLICY_APPLIED")
            },
        )
        val secret = " https://example.test/?token=secret "

        val result = service.route(BarcodeObservation(secret, 256, 7, BOUNDS))

        assertThat(result.decoded).isTrue()
        assertThat(result.regionPolicy).isEqualTo(ImageRegionPolicy.REBUILD_FROM_STRUCTURED_DATA)
        assertThat(result.routingReceipt?.code).isEqualTo("URL_POLICY_APPLIED")
        assertThat(gatedText).isEqualTo(secret)
        assertThat(routedText).isEqualTo(secret.trim())
        assertThat(result.toString()).doesNotContain("token=secret")
    }

    @Test
    fun `empty decoded barcode fails closed without entering text routing`() = runBlocking {
        var routed = false
        val service = BarcodeRoutingService(
            BarcodeUnicodeGate {
                routed = true
                UnicodeGatedBarcodeValue(it)
            },
            CanonicalTextAndUrlRouter {
                routed = true
                BarcodeRoutingReceipt("TEXT_POLICY_APPLIED")
            },
        )

        val result = service.route(BarcodeObservation("", 256, 7, BOUNDS))

        assertThat(result.decoded).isFalse()
        assertThat(result.regionPolicy).isEqualTo(ImageRegionPolicy.SOLID_REDACT)
        assertThat(result.reviewReasons).containsExactly(OcrReviewReason.UNDECODABLE_REGION)
        assertThat(routed).isFalse()
    }

    @Test
    fun `undecodable or inconsistent barcode views default to solid redaction`() = runBlocking {
        val service = BarcodeRoutingService(
            BarcodeUnicodeGate { UnicodeGatedBarcodeValue(it) },
            CanonicalTextAndUrlRouter { BarcodeRoutingReceipt("TEXT_POLICY_APPLIED") },
        )
        val recognizer = object : LocalBarcodeRecognizer {
            override val engineId = SafeSummary("barcode-engine")
            override val executionMode = OcrExecutionMode.BUNDLED_LOCAL
            var call = 0
            var closed = false
            override suspend fun scan(view: TemporaryOcrView): List<BarcodeObservation> {
                call++
                return listOf(BarcodeObservation(if (call == 1) "decoded" else null, 256, 7, BOUNDS))
            }
            override fun close() { closed = true }
        }
        val source = ArgbPixelImage(2, 2, IntArray(4))

        val results = LocalBarcodeOrchestrator().run(
            source,
            listOf(OcrViewRecipe.ORIGINAL, OcrViewRecipe.GRAYSCALE),
            viewPolicy(),
            BarcodeConsensusPolicy(2, 0.5f, SafeSummary("barcode-corpus-v1")),
            recognizer,
            service,
        )

        assertThat(results.single().decoded).isFalse()
        assertThat(results.single().regionPolicy).isEqualTo(ImageRegionPolicy.SOLID_REDACT)
        assertThat(results.single().reviewReasons).contains(OcrReviewReason.UNDECODABLE_REGION)
        assertThat(recognizer.closed).isTrue()
        source.close()
    }

    private fun plan() = OcrRunPlan(
        setOf(OcrScript.LATIN),
        listOf(OcrViewRecipe.ORIGINAL, OcrViewRecipe.GRAYSCALE),
        viewPolicy(),
        OcrConsensusPolicy(2, 0.5f, 0.05f, null, SafeSummary("ocr-consensus-corpus-v1")),
    )

    private fun viewPolicy() = OcrViewResourcePolicy(4, 4, 16, SafeSummary("ocr-view-corpus-v1"))

    private class RecordingObserver : OcrLifecycleObserver {
        val deleted = mutableListOf<String>()
        val invalidated = mutableListOf<Int>()
        override fun temporaryViewDeleted(viewId: SafeSummary) { deleted += viewId.value }
        override fun partialResultInvalidated(completedSources: Int) { invalidated += completedSources }
    }

    private class FakeTextRecognizer(
        override val script: OcrScript = OcrScript.LATIN,
        private val cancelOnCall: Int? = null,
    ) : LocalTextRecognizer {
        override val engineId = SafeSummary("text-engine")
        override val executionMode = OcrExecutionMode.BUNDLED_LOCAL
        var closed = false
        private var call = 0

        override suspend fun recognize(view: TemporaryOcrView): OcrEngineOutput {
            call++
            if (call == cancelOnCall) throw CancellationException("test cancellation")
            val observation = OcrTextObservation(
                "Canonical",
                OcrGeometry(BOUNDS, null, null, 0f, false),
                script,
                engineId,
                view.viewId,
                0.9f,
            )
            return OcrEngineOutput(listOf(observation), script, engineId, view.viewId)
        }

        override fun close() { closed = true }
    }

    private companion object {
        val BOUNDS = NormalizedRect(0.1f, 0.1f, 0.9f, 0.4f)
    }
}
