package app.shareguard.core.pipeline

import app.shareguard.core.model.BlockPhase
import app.shareguard.core.model.DiagnosticTraceEvent
import app.shareguard.core.model.ExecutionContext
import app.shareguard.core.model.TraceEventId
import app.shareguard.core.model.TraceOutcome
import app.shareguard.core.model.TransformationCategory
import java.util.concurrent.atomic.AtomicLong

/** Session-scoped in-memory ring. It has no persistence API by design. */
class DiagnosticTraceBuffer(
    private val capacity: Int,
) {
    init { require(capacity > 0) { "Trace capacity must be positive" } }

    private val lock = Any()
    private val events = ArrayDeque<DiagnosticTraceEvent>(capacity)

    fun record(event: DiagnosticTraceEvent) {
        synchronized(lock) {
            if (events.size == capacity) events.removeFirst()
            events.addLast(event)
        }
    }

    fun snapshot(): List<DiagnosticTraceEvent> = synchronized(lock) { events.toList() }

    fun clear() = synchronized(lock) { events.clear() }

    val size: Int
        get() = synchronized(lock) { events.size }
}

class DiagnosticTraceRecorder(
    private val buffer: DiagnosticTraceBuffer,
) {
    private val nextId = AtomicLong(1)

    fun phase(
        context: ExecutionContext,
        metadata: PipelineBlockMetadata,
        phase: BlockPhase,
        outcome: TraceOutcome,
        reasonCode: String,
    ) {
        buffer.record(
            DiagnosticTraceEvent(
                eventId = TraceEventId("trace-${nextId.getAndIncrement()}"),
                sessionId = context.sessionId,
                blockId = metadata.blockId,
                blockVersion = metadata.blockVersion,
                phase = phase,
                transformationCategory = null,
                executionRevision = context.executionRevision,
                canonicalRevision = context.canonicalDocument?.revision,
                outcome = outcome,
                reasonCode = reasonCode,
            ),
        )
    }

    fun transformation(
        context: ExecutionContext,
        metadata: PipelineBlockMetadata,
        category: TransformationCategory,
        outcome: TraceOutcome,
        reasonCode: String,
    ) {
        buffer.record(
            DiagnosticTraceEvent(
                eventId = TraceEventId("trace-${nextId.getAndIncrement()}"),
                sessionId = context.sessionId,
                blockId = metadata.blockId,
                blockVersion = metadata.blockVersion,
                phase = null,
                transformationCategory = category,
                executionRevision = context.executionRevision,
                canonicalRevision = context.canonicalDocument?.revision,
                outcome = outcome,
                reasonCode = reasonCode,
            ),
        )
    }
}
