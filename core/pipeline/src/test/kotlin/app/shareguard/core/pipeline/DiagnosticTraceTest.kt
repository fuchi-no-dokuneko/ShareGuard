package app.shareguard.core.pipeline

import app.shareguard.core.model.BlockPhase
import app.shareguard.core.model.TraceOutcome
import app.shareguard.core.model.TransformationCategory
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows
import org.junit.Test

class DiagnosticTraceTest {
    @Test
    fun ringBufferEvictsOldestEventAndCanBeClearedWithSession() {
        val buffer = DiagnosticTraceBuffer(capacity = 2)
        val recorder = DiagnosticTraceRecorder(buffer)
        val context = executionContext()
        val metadata = NormativeBlockCatalog.registry.require(BuiltInPresets.textBalanced.blockIds.first())

        recorder.phase(context, metadata, BlockPhase.VALIDATE, TraceOutcome.SUCCESS, "FIRST_EVENT")
        recorder.phase(context, metadata, BlockPhase.INSPECT, TraceOutcome.SUCCESS, "SECOND_EVENT")
        recorder.transformation(
            context,
            metadata,
            TransformationCategory.IMPORT,
            TraceOutcome.SUCCESS,
            "THIRD_EVENT",
        )

        assertThat(buffer.snapshot().map { it.reasonCode })
            .containsExactly("SECOND_EVENT", "THIRD_EVENT")
            .inOrder()
        assertThat(buffer.size).isEqualTo(2)

        buffer.clear()

        assertThat(buffer.snapshot()).isEmpty()
        assertThat(buffer.size).isEqualTo(0)
    }

    @Test
    fun traceSchemaAndSerializationContainOnlyContentFreeOperationalFields() {
        val buffer = DiagnosticTraceBuffer(capacity = 1)
        val recorder = DiagnosticTraceRecorder(buffer)
        val metadata = NormativeBlockCatalog.registry.require(BuiltInPresets.textBalanced.blockIds.first())
        recorder.phase(executionContext(), metadata, BlockPhase.APPLY, TraceOutcome.SUCCESS, "BLOCK_APPLIED")

        val encoded = Json.encodeToString(buffer.snapshot().single())

        listOf(
            "content", "path", "label", "url", "destination", "recipient", "timestamp",
            "digest", "key", "session-source",
        ).forEach { forbidden ->
            assertThat(encoded.lowercase()).doesNotContain(forbidden)
        }
        assertThat(encoded).contains("BLOCK_APPLIED")
        assertThat(encoded).contains("SYS-001")
    }

    @Test
    fun arbitrarySourceLikeReasonTextCannotEnterTrace() {
        val buffer = DiagnosticTraceBuffer(capacity = 1)
        val recorder = DiagnosticTraceRecorder(buffer)
        val metadata = NormativeBlockCatalog.registry.require(BuiltInPresets.textBalanced.blockIds.first())

        assertThrows(IllegalArgumentException::class.java) {
            recorder.phase(
                executionContext(),
                metadata,
                BlockPhase.APPLY,
                TraceOutcome.SUCCESS,
                "https://sensitive.example/source?q=value",
            )
        }
        assertThat(buffer.snapshot()).isEmpty()
    }
}
