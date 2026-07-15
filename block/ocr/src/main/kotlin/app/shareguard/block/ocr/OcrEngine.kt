package app.shareguard.block.ocr

import app.shareguard.block.image.PixelImage
import app.shareguard.core.model.SafeSummary
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class OcrExecutionMode { BUNDLED_LOCAL }

interface LocalTextRecognizer : Closeable {
    val script: OcrScript
    val engineId: SafeSummary
    val executionMode: OcrExecutionMode
    suspend fun recognize(view: TemporaryOcrView): OcrEngineOutput
}

data class OcrRunPlan(
    val scripts: Set<OcrScript>,
    val recipes: List<OcrViewRecipe>,
    val viewResourcePolicy: OcrViewResourcePolicy,
    val consensusPolicy: OcrConsensusPolicy,
) {
    init {
        require(scripts.isNotEmpty())
        require(recipes.isNotEmpty())
    }
}

interface OcrLifecycleObserver {
    fun temporaryViewDeleted(viewId: SafeSummary) = Unit
    fun partialResultInvalidated(completedSources: Int) = Unit

    data object None : OcrLifecycleObserver
}

/** Owns recognizers and temporary views for exactly one run and closes both on every exit path. */
class LocalOcrOrchestrator(
    private val viewFactory: TemporaryOcrViewFactory = TemporaryOcrViewFactory(),
    private val consensus: ConservativeOcrConsensus = ConservativeOcrConsensus(),
    private val observer: OcrLifecycleObserver = OcrLifecycleObserver.None,
) {
    suspend fun run(
        source: PixelImage,
        plan: OcrRunPlan,
        recognizers: List<LocalTextRecognizer>,
    ): OcrConsensusReport {
        var views: List<TemporaryOcrView> = emptyList()
        val outputs = mutableListOf<OcrEngineOutput>()
        try {
            require(recognizers.isNotEmpty())
            require(recognizers.all { it.executionMode == OcrExecutionMode.BUNDLED_LOCAL }) {
                "Cloud, downloaded, and network OCR engines are forbidden"
            }
            require(plan.scripts.all { requested -> recognizers.any { it.script == requested } }) {
                "Every selected script must have a bundled recognizer"
            }
            require(recognizers.all { it.script in plan.scripts })
            views = viewFactory.create(source, plan.recipes, plan.viewResourcePolicy)
            val expected = Math.multiplyExact(views.size, recognizers.size)
            for (recognizer in recognizers) for (view in views) {
                currentCoroutineContext().ensureActive()
                val output = recognizer.recognize(view)
                // Native inference may complete after cancellation; never publish that partial observation.
                currentCoroutineContext().ensureActive()
                outputs += output
            }
            return consensus.reconcile(outputs, expected, plan.consensusPolicy)
        } catch (cancelled: CancellationException) {
            val completed = outputs.size
            outputs.clear()
            observer.partialResultInvalidated(completed)
            throw cancelled
        } catch (failure: Throwable) {
            val completed = outputs.size
            outputs.clear()
            observer.partialResultInvalidated(completed)
            throw failure
        } finally {
            views.forEach {
                it.close()
                observer.temporaryViewDeleted(it.viewId)
            }
            recognizers.forEach { runCatching { it.close() } }
        }
    }
}
