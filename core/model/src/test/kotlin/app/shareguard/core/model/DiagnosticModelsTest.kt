package app.shareguard.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows
import org.junit.Test

class DiagnosticModelsTest {
    @Test
    fun traceEvent_serializesOnlyContentFreeExecutionFields() {
        val event = DiagnosticTraceEvent(
            eventId = TraceEventId("event-1"),
            sessionId = SessionId("session-trace"),
            blockId = BlockId("TXT-010"),
            blockVersion = BlockVersion(1),
            phase = BlockPhase.APPLY,
            transformationCategory = TransformationCategory.TEXT_NORMALIZATION,
            executionRevision = ExecutionRevision(4),
            canonicalRevision = CanonicalRevision(2),
            outcome = TraceOutcome.SUCCESS,
            reasonCode = "NORMALIZATION_APPLIED",
        )

        val encoded = Json.encodeToString(event)

        assertThat(encoded).contains("NORMALIZATION_APPLIED")
        assertThat(encoded).doesNotContain("sourceText")
        assertThat(encoded).doesNotContain("outputText")
        assertThat(encoded).doesNotContain("contentUri")
        assertThat(encoded).doesNotContain("filename")
        assertThat(encoded).doesNotContain("displayLabel")
        assertThat(encoded).doesNotContain("destination")
        assertThat(encoded).doesNotContain("digest")
    }

    @Test
    fun traceEvent_rejectsFreeFormReasonText() {
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticTraceEvent(
                eventId = TraceEventId("event-invalid"),
                sessionId = SessionId("session-trace"),
                blockId = BlockId("TXT-010"),
                blockVersion = BlockVersion(1),
                phase = BlockPhase.APPLY,
                transformationCategory = null,
                executionRevision = ExecutionRevision(4),
                canonicalRevision = CanonicalRevision(2),
                outcome = TraceOutcome.SUCCESS,
                reasonCode = "normalized the user's secret text",
            )
        }
    }

    @Test
    fun boundedDelayPolicy_persistsBoundsButHasNoSelectedDelay() {
        val policy = BoundedDelayPolicy(
            enabled = true,
            purpose = BoundedDelayPurpose.OPTIONAL_PRE_SHARE_JITTER,
            minimum = DurationMillis(10),
            maximum = DurationMillis(50),
            validationReference = SafeSummary("benchmark-delay-policy-v1"),
        )

        val encoded = Json.encodeToString(policy)

        assertThat(encoded).contains("minimum")
        assertThat(encoded).contains("maximum")
        assertThat(encoded).doesNotContain("selectedDelay")
        assertThat(encoded).doesNotContain("actualDelay")
    }
}
