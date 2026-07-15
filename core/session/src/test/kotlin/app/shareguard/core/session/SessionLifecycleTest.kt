package app.shareguard.core.session

import app.shareguard.core.model.BlockId
import app.shareguard.core.model.BlockPhase
import app.shareguard.core.model.BlockVersion
import app.shareguard.core.model.ExecutionRevision
import app.shareguard.core.model.SessionId
import app.shareguard.core.model.TraceOutcome
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionLifecycleTest {
    @Test
    fun `cancellation cleans partial work but preserves resumable session state`() {
        val lifecycle = SessionLifecycle(SessionId("session-a"))
        val deleted = mutableListOf<String>()
        lifecycle.registerPartial { deleted += "partial"; LogicalCleanupOutcome.DELETED }
        lifecycle.registerSessionTransient { deleted += "source"; LogicalCleanupOutcome.DELETED }
        lifecycle.activate()

        val report = lifecycle.requestCancellation()

        assertThat(report).isEqualTo(CleanupReport(1, 1, 0))
        assertThat(deleted).containsExactly("partial")
        assertThat(lifecycle.state).isEqualTo(SessionRuntimeState.CANCELLED)
        assertThat(lifecycle.cancellationSignal.isCancellationRequested).isTrue()
        assertThrows(IllegalStateException::class.java) { lifecycle.ensureNewWorkMayStart() }

        lifecycle.resume()

        assertThat(lifecycle.state).isEqualTo(SessionRuntimeState.ACTIVE)
        assertThat(lifecycle.cancellationSignal.isCancellationRequested).isFalse()
        lifecycle.ensureNewWorkMayStart()
        lifecycle.discard()
        assertThat(deleted).containsExactly("partial", "source").inOrder()
    }

    @Test
    fun `in-flight native call returns before invalidation and deferred cleanup`() {
        val lifecycle = SessionLifecycle(SessionId("session-a"))
        val sequence = mutableListOf<String>()
        lifecycle.registerPartial { sequence += "cleanup"; LogicalCleanupOutcome.DELETED }
        lifecycle.activate()

        val failure = assertThrows(SessionCancellationException::class.java) {
            lifecycle.runNativeBoundary<String>(
                discardInvalidPartial = { value -> sequence += "discard-$value" },
                operation = {
                    sequence += "native-start"
                    val report = lifecycle.requestCancellation()
                    assertThat(report.deferredUntilNativeReturn).isTrue()
                    sequence += "native-return"
                    "partial-result"
                },
            )
        }

        assertThat(failure.message).doesNotContain("partial-result")
        assertThat(sequence).containsExactly(
            "native-start",
            "native-return",
            "discard-partial-result",
            "cleanup",
        ).inOrder()
        assertThat(lifecycle.state).isEqualTo(SessionRuntimeState.CANCELLED)
    }

    @Test
    fun `fatal failure during native call defers all cleanup and rejects the result`() {
        val trace = InMemorySessionDiagnosticTrace(SessionId("session-a"))
        trace.record(
            blockId = BlockId("SYS-001"),
            blockVersion = BlockVersion(1),
            phase = BlockPhase.INSPECT,
            executionRevision = ExecutionRevision(0),
            outcome = TraceOutcome.SUCCESS,
            reason = SessionDiagnosticReason.IMPORT_ACCEPTED,
        )
        val lifecycle = SessionLifecycle(SessionId("session-a"), diagnosticTrace = trace)
        val sequence = mutableListOf<String>()
        lifecycle.registerPartial { sequence += "partial-cleanup"; LogicalCleanupOutcome.DELETED }
        lifecycle.registerSessionTransient { sequence += "session-cleanup"; LogicalCleanupOutcome.DELETED }
        lifecycle.activate()

        assertThrows(SessionCancellationException::class.java) {
            lifecycle.runNativeBoundary<String>(
                discardInvalidPartial = { sequence += "discard" },
                operation = {
                    sequence += "native"
                    assertThat(lifecycle.failFatal().deferredUntilNativeReturn).isTrue()
                    "invalid"
                },
            )
        }

        assertThat(sequence).containsExactly(
            "native",
            "discard",
            "session-cleanup",
            "partial-cleanup",
        ).inOrder()
        assertThat(lifecycle.state).isEqualTo(SessionRuntimeState.FAILED)
        assertThat(trace.snapshot()).isEmpty()
    }

    @Test
    fun `fatal failure outside native work cleans all transient resources best effort`() {
        val lifecycle = SessionLifecycle(SessionId("session-a"))
        lifecycle.activate()
        lifecycle.registerPartial { LogicalCleanupOutcome.FAILED_BEST_EFFORT }
        lifecycle.registerSessionTransient { LogicalCleanupOutcome.DELETED }

        val report = lifecycle.failFatal()

        assertThat(report).isEqualTo(CleanupReport(2, 1, 1))
        assertThat(lifecycle.state).isEqualTo(SessionRuntimeState.FAILED)
        assertThat(lifecycle.cancellationSignal.isCancellationRequested).isTrue()
    }

    @Test
    fun `successful native result is returned and operation failure is preserved`() {
        val lifecycle = SessionLifecycle(SessionId("session-a"))
        lifecycle.activate()

        assertThat(lifecycle.runNativeBoundary(discardInvalidPartial = {}, operation = { 42 })).isEqualTo(42)
        val expected = IllegalArgumentException("content-free-test-error")
        val actual = assertThrows(IllegalArgumentException::class.java) {
            lifecycle.runNativeBoundary<Int>(discardInvalidPartial = {}, operation = { throw expected })
        }

        assertThat(actual).isSameInstanceAs(expected)
        assertThat(lifecycle.state).isEqualTo(SessionRuntimeState.ACTIVE)
    }

    @Test
    fun `normal completion clears trace and session transients`() {
        val trace = InMemorySessionDiagnosticTrace(SessionId("session-a"))
        trace.record(
            blockId = BlockId("SYS-003"),
            blockVersion = BlockVersion(1),
            phase = BlockPhase.CLEANUP,
            executionRevision = ExecutionRevision(1),
            outcome = TraceOutcome.SUCCESS,
            reason = SessionDiagnosticReason.CLEANUP_COMPLETE,
        )
        var deleted = false
        val lifecycle = SessionLifecycle(SessionId("session-a"), diagnosticTrace = trace)
        lifecycle.registerSessionTransient { deleted = true; LogicalCleanupOutcome.DELETED }
        lifecycle.activate()

        val report = lifecycle.complete()

        assertThat(report.fullySuccessful).isTrue()
        assertThat(deleted).isTrue()
        assertThat(trace.snapshot()).isEmpty()
        assertThat(lifecycle.state).isEqualTo(SessionRuntimeState.COMPLETED)
    }
}
