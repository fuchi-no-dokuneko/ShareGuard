package app.shareguard.core.session

import app.shareguard.core.model.BlockId
import app.shareguard.core.model.BlockPhase
import app.shareguard.core.model.BlockVersion
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.DiagnosticTraceEvent
import app.shareguard.core.model.ExecutionRevision
import app.shareguard.core.model.SessionId
import app.shareguard.core.model.TraceEventId
import app.shareguard.core.model.TraceOutcome
import app.shareguard.core.model.TransformationCategory

interface SessionDiagnosticTrace {
    fun record(
        blockId: BlockId,
        blockVersion: BlockVersion,
        phase: BlockPhase? = null,
        transformationCategory: TransformationCategory? = null,
        executionRevision: ExecutionRevision,
        canonicalRevision: CanonicalRevision? = null,
        outcome: TraceOutcome,
        reason: SessionDiagnosticReason,
    ): DiagnosticTraceEvent?

    fun snapshot(): List<DiagnosticTraceEvent>
    fun clear()
}

/** Closed vocabulary prevents source-derived strings from being smuggled into diagnostic reason codes. */
enum class SessionDiagnosticReason {
    IMPORT_ACCEPTED,
    SNAPSHOT_SEALED,
    SNAPSHOT_INTEGRITY_FAILED,
    WORK_CANCELLED,
    CLEANUP_COMPLETE,
    CLEANUP_PARTIAL,
    FATAL_FAILURE,
    STALE_SESSION_PURGED,
    BLOCK_PHASE_COMPLETE,
    TRANSFORMATION_APPLIED,
}

/** Production default: no persistent trace and no content-linked timestamps. */
object DisabledSessionDiagnosticTrace : SessionDiagnosticTrace {
    override fun record(
        blockId: BlockId,
        blockVersion: BlockVersion,
        phase: BlockPhase?,
        transformationCategory: TransformationCategory?,
        executionRevision: ExecutionRevision,
        canonicalRevision: CanonicalRevision?,
        outcome: TraceOutcome,
        reason: SessionDiagnosticReason,
    ): DiagnosticTraceEvent? = null

    override fun snapshot(): List<DiagnosticTraceEvent> = emptyList()
    override fun clear() = Unit
}

/** Bounded, debug-only, in-memory and session-local diagnostic trace. */
class InMemorySessionDiagnosticTrace(
    private val sessionId: SessionId,
    private val capacity: Int = 2_048,
) : SessionDiagnosticTrace {
    private val events = ArrayDeque<DiagnosticTraceEvent>(capacity)
    private var nextEventId = 1L

    init {
        require(capacity > 0) { "Trace capacity must be positive" }
    }

    @Synchronized
    override fun record(
        blockId: BlockId,
        blockVersion: BlockVersion,
        phase: BlockPhase?,
        transformationCategory: TransformationCategory?,
        executionRevision: ExecutionRevision,
        canonicalRevision: CanonicalRevision?,
        outcome: TraceOutcome,
        reason: SessionDiagnosticReason,
    ): DiagnosticTraceEvent {
        val event = DiagnosticTraceEvent(
            eventId = TraceEventId("event-${nextEventId++}"),
            sessionId = sessionId,
            blockId = blockId,
            blockVersion = blockVersion,
            phase = phase,
            transformationCategory = transformationCategory,
            executionRevision = executionRevision,
            canonicalRevision = canonicalRevision,
            outcome = outcome,
            reasonCode = reason.name,
        )
        if (events.size == capacity) events.removeFirst()
        events.addLast(event)
        return event
    }

    @Synchronized
    override fun snapshot(): List<DiagnosticTraceEvent> = events.toList()

    @Synchronized
    override fun clear() {
        events.clear()
    }

    override fun toString(): String = "InMemorySessionDiagnosticTrace(events=redacted)"
}
