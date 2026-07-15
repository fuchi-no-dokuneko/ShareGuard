package app.shareguard.core.session

import app.shareguard.core.model.BlockId
import app.shareguard.core.model.BlockPhase
import app.shareguard.core.model.BlockVersion
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.ExecutionRevision
import app.shareguard.core.model.SessionId
import app.shareguard.core.model.TraceOutcome
import app.shareguard.core.model.TransformationCategory
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiagnosticTraceTest {
    @Test
    fun `debug trace records only closed content-free fields`() {
        val trace = InMemorySessionDiagnosticTrace(SessionId("session-a"))

        val event = trace.record(
            blockId = BlockId("SYS-001"),
            blockVersion = BlockVersion(2),
            phase = BlockPhase.COMMIT,
            transformationCategory = TransformationCategory.IMPORT,
            executionRevision = ExecutionRevision(3),
            canonicalRevision = CanonicalRevision(4),
            outcome = TraceOutcome.SUCCESS,
            reason = SessionDiagnosticReason.SNAPSHOT_SEALED,
        )

        assertThat(event.eventId.value).isEqualTo("event-1")
        assertThat(event.reasonCode).isEqualTo("SNAPSHOT_SEALED")
        assertThat(event.sessionId).isEqualTo(SessionId("session-a"))
        assertThat(trace.snapshot()).containsExactly(event)
        assertThat(trace.toString()).isEqualTo("InMemorySessionDiagnosticTrace(events=redacted)")
    }

    @Test
    fun `bounded trace drops oldest and keeps event IDs session local`() {
        val firstSession = InMemorySessionDiagnosticTrace(SessionId("session-a"), capacity = 2)
        repeat(3) { revision -> firstSession.recordEvent(revision.toLong()) }
        val secondSession = InMemorySessionDiagnosticTrace(SessionId("session-b"), capacity = 2)

        assertThat(firstSession.snapshot().map { it.eventId.value })
            .containsExactly("event-2", "event-3").inOrder()
        assertThat(secondSession.recordEvent(0).eventId.value).isEqualTo("event-1")
    }

    @Test
    fun `clear removes all in-memory session events`() {
        val trace = InMemorySessionDiagnosticTrace(SessionId("session-a"))
        trace.recordEvent(0)

        trace.clear()

        assertThat(trace.snapshot()).isEmpty()
    }

    @Test
    fun `production trace is disabled and retains nothing`() {
        val event = DisabledSessionDiagnosticTrace.record(
            blockId = BlockId("SYS-001"),
            blockVersion = BlockVersion(1),
            phase = BlockPhase.CLEANUP,
            executionRevision = ExecutionRevision(0),
            outcome = TraceOutcome.SUCCESS,
            reason = SessionDiagnosticReason.CLEANUP_COMPLETE,
        )

        assertThat(event).isNull()
        assertThat(DisabledSessionDiagnosticTrace.snapshot()).isEmpty()
    }

    private fun InMemorySessionDiagnosticTrace.recordEvent(revision: Long) = record(
        blockId = BlockId("SYS-001"),
        blockVersion = BlockVersion(1),
        phase = BlockPhase.INSPECT,
        executionRevision = ExecutionRevision(revision),
        outcome = TraceOutcome.SUCCESS,
        reason = SessionDiagnosticReason.IMPORT_ACCEPTED,
    )
}
