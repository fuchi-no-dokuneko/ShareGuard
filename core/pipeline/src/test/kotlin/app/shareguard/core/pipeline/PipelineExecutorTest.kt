package app.shareguard.core.pipeline

import app.shareguard.core.model.BlockId
import app.shareguard.core.model.ExecutionContext
import app.shareguard.core.model.ExecutionLifecycleState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PipelineExecutorTest {
    @Test
    fun successfulExecutionCommitsEveryBlockAndCleansUpInReverseOrder() = runTest {
        val preset = BuiltInPresets.textBalanced
        val cleanup = mutableListOf<Pair<String, CleanupReason>>()
        val blocks = preset.blockReferences.map { reference ->
            TrackingBlock(reference.blockId.value, cleanup = cleanup)
        }

        val result = PipelineExecutor(MapPipelineBlockResolver(blocks))
            .execute(preset, executionContextFor(preset))

        assertThat(result).isInstanceOf(PipelineRunResult.Completed::class.java)
        assertThat(result.executedBlocks).containsExactlyElementsIn(preset.blockReferences).inOrder()
        assertThat(result.summaries).hasSize(preset.blockReferences.size)
        assertThat(result.context.executionRevision.value).isEqualTo(preset.blockReferences.size.toLong())
        assertThat(result.context.lifecycleState).isEqualTo(ExecutionLifecycleState.COMPLETED)
        assertThat(cleanup.map { it.first })
            .containsExactlyElementsIn(preset.blockIds.asReversed().map(BlockId::value))
            .inOrder()
        assertThat(cleanup.map { it.second }.distinct()).containsExactly(CleanupReason.COMPLETED)
    }

    @Test
    fun fatalMandatoryFailureBlocksAllLaterBlocksAndReturnsPv016() = runTest {
        val preset = BuiltInPresets.textBalanced
        val failedId = "TXT-001"
        val cleanup = mutableListOf<Pair<String, CleanupReason>>()
        val entered = mutableListOf<String>()
        val blocks = preset.blockReferences.map { reference ->
            if (reference.blockId.value == failedId) {
                TrackingBlock(
                    reference.blockId.value,
                    cleanup = cleanup,
                    entered = entered,
                    selfCheck = PhaseCheck.fatal("SELF_CHECK_FATAL"),
                )
            } else {
                TrackingBlock(reference.blockId.value, cleanup = cleanup, entered = entered)
            }
        }

        val result = PipelineExecutor(MapPipelineBlockResolver(blocks))
            .execute(preset, executionContextFor(preset))

        assertThat(result).isInstanceOf(PipelineRunResult.Failed::class.java)
        result as PipelineRunResult.Failed
        assertThat(result.failedBlock.blockId).isEqualTo(BlockId(failedId))
        assertThat(result.fatal).isTrue()
        assertThat(result.violationCode).isEqualTo(PipelineViolationCode.PV_016)
        assertThat(result.reasonCode).isEqualTo("MANDATORY_BLOCK_FAILED")
        assertThat(entered.last()).isEqualTo(failedId)
        assertThat(entered).doesNotContain("TXT-002")
        assertThat(cleanup.map { it.second }.distinct()).containsExactly(CleanupReason.FATAL_FAILURE)
    }

    @Test
    fun pendingReviewPausesWithoutApplyingTheReviewBlockAndRunsCleanup() = runTest {
        val preset = BuiltInPresets.textBalanced
        val reviewId = "REV-001"
        val cleanup = mutableListOf<Pair<String, CleanupReason>>()
        val applied = mutableListOf<String>()
        val blocks = preset.blockReferences.map { reference ->
            if (reference.blockId.value == reviewId) {
                TrackingBlock(
                    id = reviewId,
                    cleanup = cleanup,
                    applied = applied,
                    plan = BlockPlan(PlanDisposition.REVIEW_REQUIRED, "HUMAN_REVIEW_REQUIRED"),
                )
            } else {
                TrackingBlock(reference.blockId.value, cleanup = cleanup, applied = applied)
            }
        }

        val result = PipelineExecutor(
            resolver = MapPipelineBlockResolver(blocks),
            reviewGate = ReviewGate { ReviewResolution.PENDING },
        ).execute(preset, executionContextFor(preset))

        assertThat(result).isInstanceOf(PipelineRunResult.ReviewRequired::class.java)
        result as PipelineRunResult.ReviewRequired
        assertThat(result.request.blockReference.blockId).isEqualTo(BlockId(reviewId))
        assertThat(result.context.lifecycleState).isEqualTo(ExecutionLifecycleState.REVIEW_REQUIRED)
        assertThat(applied).doesNotContain(reviewId)
        assertThat(result.executedBlocks.map { it.blockId.value }).doesNotContain(reviewId)
        assertThat(cleanup.map { it.second }.distinct()).containsExactly(CleanupReason.REVIEW_PAUSED)
    }

    @Test
    fun cancellationPropagatesAndNonCancellableCleanupStillRuns() = runTest {
        val preset = BuiltInPresets.textBalanced
        val blockingId = "TXT-001"
        val started = CompletableDeferred<Unit>()
        val cleanup = mutableListOf<Pair<String, CleanupReason>>()
        val blocks = preset.blockReferences.map { reference ->
            if (reference.blockId.value == blockingId) {
                object : TrackingBlock(reference.blockId.value, cleanup = cleanup) {
                    override suspend fun apply(context: ExecutionContext, plan: BlockPlan): BlockApplication {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
            } else {
                TrackingBlock(reference.blockId.value, cleanup = cleanup)
            }
        }
        val executor = PipelineExecutor(MapPipelineBlockResolver(blocks))

        val job = launch {
            executor.execute(preset, executionContextFor(preset))
        }
        started.await()
        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
        assertThat(cleanup).isNotEmpty()
        assertThat(cleanup.map { it.second }.distinct()).containsExactly(CleanupReason.CANCELLED)
        assertThat(cleanup.first().first).isEqualTo(blockingId)
    }

    private open class TrackingBlock(
        id: String,
        private val cleanup: MutableList<Pair<String, CleanupReason>>,
        private val entered: MutableList<String>? = null,
        private val applied: MutableList<String>? = null,
        private val plan: BlockPlan = BlockPlan(PlanDisposition.NO_CHANGE, "NO_CHANGE_REQUIRED"),
        private val selfCheck: PhaseCheck = PhaseCheck.pass("SELF_CHECK_PASSED"),
    ) : BasePipelineBlock(NormativeBlockCatalog.registry.require(BlockId(id))) {
        override suspend fun validateConfiguration(context: ExecutionContext): PhaseCheck {
            entered?.add(metadata.blockId.value)
            return PhaseCheck.pass()
        }

        override suspend fun planChanges(context: ExecutionContext, inspection: BlockInspection): BlockPlan = plan

        override suspend fun apply(context: ExecutionContext, plan: BlockPlan): BlockApplication {
            applied?.add(metadata.blockId.value)
            return BlockApplication(context, changed = false, reasonCode = "NO_CHANGE_APPLIED")
        }

        override suspend fun selfCheck(
            before: ExecutionContext,
            application: BlockApplication,
        ): PhaseCheck = selfCheck

        override suspend fun cleanup(context: ExecutionContext, reason: CleanupReason) {
            cleanup += metadata.blockId.value to reason
        }
    }
}
