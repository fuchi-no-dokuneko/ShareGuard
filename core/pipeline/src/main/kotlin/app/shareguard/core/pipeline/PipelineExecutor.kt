package app.shareguard.core.pipeline

import app.shareguard.core.model.BlockPhase
import app.shareguard.core.model.ExecutionContext
import app.shareguard.core.model.ExecutionLifecycleState
import app.shareguard.core.model.TraceOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

interface PipelineBlockResolver {
    fun resolve(reference: BlockReference): PipelineBlock
}

class MapPipelineBlockResolver(
    blocks: Iterable<PipelineBlock>,
) : PipelineBlockResolver {
    private val materialized = blocks.toList()
    private val byId = materialized.associateBy { it.metadata.blockId }

    init {
        require(materialized.map { it.metadata.blockId }.distinct().size == materialized.size) {
            "Executable block IDs must be unique"
        }
    }

    override fun resolve(reference: BlockReference): PipelineBlock {
        val block = byId[reference.blockId]
            ?: throw MissingBlockImplementationException(reference)
        if (block.metadata.blockVersion != reference.blockVersion) {
            throw MissingBlockImplementationException(reference)
        }
        return block
    }
}

class MissingBlockImplementationException(
    val reference: BlockReference,
) : IllegalStateException("No implementation for ${reference.blockId.value}@${reference.blockVersion.value}")

sealed interface PipelineRunResult {
    val context: ExecutionContext
    val executedBlocks: List<BlockReference>
    val summaries: List<BlockSummary>
    val verificationHints: List<VerificationHint>

    data class Completed(
        override val context: ExecutionContext,
        override val executedBlocks: List<BlockReference>,
        override val summaries: List<BlockSummary>,
        override val verificationHints: List<VerificationHint>,
    ) : PipelineRunResult

    data class ReviewRequired(
        override val context: ExecutionContext,
        val request: ReviewRequest,
        override val executedBlocks: List<BlockReference>,
        override val summaries: List<BlockSummary>,
        override val verificationHints: List<VerificationHint>,
    ) : PipelineRunResult

    data class Failed(
        override val context: ExecutionContext,
        val failedBlock: BlockReference,
        val fatal: Boolean,
        val reasonCode: String,
        val violationCode: PipelineViolationCode?,
        override val executedBlocks: List<BlockReference>,
        override val summaries: List<BlockSummary>,
        override val verificationHints: List<VerificationHint>,
    ) : PipelineRunResult
}

class PipelineExecutor(
    private val resolver: PipelineBlockResolver,
    private val validator: SequenceValidator = SequenceValidator(),
    private val reviewGate: ReviewGate = ReviewGate { ReviewResolution.PENDING },
    private val traceRecorder: DiagnosticTraceRecorder? = null,
) {
    suspend fun execute(
        preset: PipelinePreset,
        initialContext: ExecutionContext,
    ): PipelineRunResult = coroutineScope {
        validator.validate(preset).requireValid()
        require(initialContext.inputKind == preset.inputKind) { "Context input kind does not match preset" }
        require(initialContext.requestedOutput == preset.outputMode) { "Context output mode does not match preset" }

        var context = initialContext.copy(lifecycleState = ExecutionLifecycleState.RUNNING)
        val entered = mutableListOf<Pair<BlockReference, PipelineBlock>>()
        val executed = mutableListOf<BlockReference>()
        val summaries = mutableListOf<BlockSummary>()
        val hints = mutableListOf<VerificationHint>()
        var cleaned = false

        suspend fun cleanup(reason: CleanupReason) {
            if (cleaned) return
            cleaned = true
            withContext(NonCancellable) {
                entered.asReversed().forEach { (_, block) ->
                    try {
                        block.cleanup(context, reason)
                        trace(block, context, BlockPhase.CLEANUP, TraceOutcome.SUCCESS, "CLEANUP_COMPLETE")
                    } catch (_: Throwable) {
                        trace(block, context, BlockPhase.CLEANUP, TraceOutcome.RECOVERABLE_FAILURE, "CLEANUP_FAILED")
                    }
                }
            }
        }

        suspend fun fail(
            reference: BlockReference,
            block: PipelineBlock,
            fatal: Boolean,
            reasonCode: String,
        ): PipelineRunResult.Failed {
            val effectiveFatal = fatal || block.metadata.mandatory
            val violation = if (block.metadata.mandatory) PipelineViolationCode.PV_016 else null
            context = context.copy(lifecycleState = ExecutionLifecycleState.FAILED)
            cleanup(if (effectiveFatal) CleanupReason.FATAL_FAILURE else CleanupReason.RECOVERABLE_FAILURE)
            return PipelineRunResult.Failed(
                context = context,
                failedBlock = reference,
                fatal = effectiveFatal,
                reasonCode = if (block.metadata.mandatory) "MANDATORY_BLOCK_FAILED" else reasonCode,
                violationCode = violation,
                executedBlocks = executed.toList(),
                summaries = summaries.toList(),
                verificationHints = hints.toList(),
            )
        }

        try {
            for (reference in preset.blockReferences) {
                coroutineContext.ensureActive()
                val block = resolver.resolve(reference)
                entered += reference to block

                val configuration = block.validateConfiguration(context)
                traceCheck(block, context, BlockPhase.VALIDATE, configuration)
                if (!configuration.passed) {
                    return@coroutineScope fail(reference, block, configuration.fatal, configuration.reasonCode)
                }

                coroutineContext.ensureActive()
                val inspection = try {
                    block.inspect(context).also {
                        trace(block, context, BlockPhase.INSPECT, TraceOutcome.SUCCESS, it.reasonCode)
                    }
                } catch (error: FatalBlockException) {
                    trace(block, context, BlockPhase.INSPECT, TraceOutcome.FATAL_FAILURE, error.reasonCode)
                    return@coroutineScope fail(reference, block, true, error.reasonCode)
                } catch (error: RecoverableBlockException) {
                    trace(block, context, BlockPhase.INSPECT, TraceOutcome.RECOVERABLE_FAILURE, error.reasonCode)
                    return@coroutineScope fail(reference, block, false, error.reasonCode)
                }

                coroutineContext.ensureActive()
                val plan = block.planChanges(context, inspection)
                val planOutcome = when (plan.disposition) {
                    PlanDisposition.REVIEW_REQUIRED -> TraceOutcome.REVIEW_REQUIRED
                    PlanDisposition.RECOVERABLE_FAILURE -> TraceOutcome.RECOVERABLE_FAILURE
                    PlanDisposition.FATAL_FAILURE -> TraceOutcome.FATAL_FAILURE
                    else -> TraceOutcome.SUCCESS
                }
                trace(block, context, BlockPhase.PLAN, planOutcome, plan.reasonCode)
                when (plan.disposition) {
                    PlanDisposition.RECOVERABLE_FAILURE ->
                        return@coroutineScope fail(reference, block, false, plan.reasonCode)
                    PlanDisposition.FATAL_FAILURE ->
                        return@coroutineScope fail(reference, block, true, plan.reasonCode)
                    else -> Unit
                }

                if (plan.disposition == PlanDisposition.REVIEW_REQUIRED || inspection.reviewRecommended) {
                    val request = ReviewRequest(reference, plan.reasonCode, context.executionRevision.value)
                    when (reviewGate.resolve(request)) {
                        ReviewResolution.APPROVED ->
                            trace(block, context, BlockPhase.REVIEW, TraceOutcome.SUCCESS, "REVIEW_APPROVED")
                        ReviewResolution.REJECTED -> {
                            trace(block, context, BlockPhase.REVIEW, TraceOutcome.FATAL_FAILURE, "REVIEW_REJECTED")
                            return@coroutineScope fail(reference, block, true, "REVIEW_REJECTED")
                        }
                        ReviewResolution.PENDING -> {
                            trace(block, context, BlockPhase.REVIEW, TraceOutcome.REVIEW_REQUIRED, "REVIEW_PENDING")
                            context = context.copy(lifecycleState = ExecutionLifecycleState.REVIEW_REQUIRED)
                            cleanup(CleanupReason.REVIEW_PAUSED)
                            return@coroutineScope PipelineRunResult.ReviewRequired(
                                context,
                                request,
                                executed.toList(),
                                summaries.toList(),
                                hints.toList(),
                            )
                        }
                    }
                }

                coroutineContext.ensureActive()
                val before = context
                val application = try {
                    block.apply(before, plan).also {
                        trace(block, before, BlockPhase.APPLY, TraceOutcome.SUCCESS, it.reasonCode)
                        val transformationCategory = block.metadata.transformationCategory
                        if (it.changed && transformationCategory != null) {
                            traceRecorder?.transformation(
                                before,
                                block.metadata,
                                transformationCategory,
                                TraceOutcome.SUCCESS,
                                it.reasonCode,
                            )
                        }
                    }
                } catch (error: FatalBlockException) {
                    trace(block, before, BlockPhase.APPLY, TraceOutcome.FATAL_FAILURE, error.reasonCode)
                    return@coroutineScope fail(reference, block, true, error.reasonCode)
                } catch (error: RecoverableBlockException) {
                    trace(block, before, BlockPhase.APPLY, TraceOutcome.RECOVERABLE_FAILURE, error.reasonCode)
                    return@coroutineScope fail(reference, block, false, error.reasonCode)
                }

                val selfCheck = block.selfCheck(before, application)
                traceCheck(block, before, BlockPhase.SELF_CHECK, selfCheck)
                if (!selfCheck.passed) {
                    return@coroutineScope fail(reference, block, selfCheck.fatal, selfCheck.reasonCode)
                }

                context = block.commit(before, application)
                trace(block, context, BlockPhase.COMMIT, TraceOutcome.SUCCESS, "CONTEXT_COMMITTED")
                summaries += block.summarize(before, context)
                hints += block.produceVerificationHints(context)
                executed += reference
            }

            context = context.copy(lifecycleState = ExecutionLifecycleState.COMPLETED)
            cleanup(CleanupReason.COMPLETED)
            PipelineRunResult.Completed(context, executed.toList(), summaries.toList(), hints.toList())
        } catch (cancelled: CancellationException) {
            context = context.copy(lifecycleState = ExecutionLifecycleState.CANCELLED)
            cleanup(CleanupReason.CANCELLED)
            throw cancelled
        } catch (error: FatalBlockException) {
            val (reference, block) = entered.lastOrNull()
                ?: throw error
            trace(block, context, BlockPhase.APPLY, TraceOutcome.FATAL_FAILURE, error.reasonCode)
            fail(reference, block, true, error.reasonCode)
        } catch (error: RecoverableBlockException) {
            val (reference, block) = entered.lastOrNull()
                ?: throw error
            trace(block, context, BlockPhase.APPLY, TraceOutcome.RECOVERABLE_FAILURE, error.reasonCode)
            fail(reference, block, false, error.reasonCode)
        } catch (error: Exception) {
            val (reference, block) = entered.lastOrNull()
                ?: throw error
            trace(block, context, BlockPhase.APPLY, TraceOutcome.FATAL_FAILURE, "UNEXPECTED_BLOCK_FAILURE")
            fail(reference, block, true, "UNEXPECTED_BLOCK_FAILURE")
        }
    }

    private fun traceCheck(
        block: PipelineBlock,
        context: ExecutionContext,
        phase: BlockPhase,
        check: PhaseCheck,
    ) {
        val outcome = when {
            check.passed -> TraceOutcome.SUCCESS
            check.fatal -> TraceOutcome.FATAL_FAILURE
            else -> TraceOutcome.RECOVERABLE_FAILURE
        }
        trace(block, context, phase, outcome, check.reasonCode)
    }

    private fun trace(
        block: PipelineBlock,
        context: ExecutionContext,
        phase: BlockPhase,
        outcome: TraceOutcome,
        reasonCode: String,
    ) {
        traceRecorder?.phase(context, block.metadata, phase, outcome, reasonCode)
    }
}

open class RecoverableBlockException(
    val reasonCode: String,
    cause: Throwable? = null,
) : RuntimeException(reasonCode, cause) {
    init { require(CONTENT_FREE_CODE.matches(reasonCode)) { "Failure reason must be content-free" } }
}

class FatalBlockException(
    reasonCode: String,
    cause: Throwable? = null,
) : RecoverableBlockException(reasonCode, cause)
