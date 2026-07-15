package app.shareguard.core.model

import kotlinx.serialization.Serializable

/**
 * A content-free debug/diagnostic event. It intentionally has no field capable of carrying source/output
 * content, paths, labels, URLs, destinations, timestamps linked to content, keys, or stable digests.
 */
@Serializable
data class DiagnosticTraceEvent(
    val eventId: TraceEventId,
    val sessionId: SessionId,
    val blockId: BlockId,
    val blockVersion: BlockVersion,
    val phase: BlockPhase?,
    val transformationCategory: TransformationCategory?,
    val executionRevision: ExecutionRevision,
    val canonicalRevision: CanonicalRevision?,
    val outcome: TraceOutcome,
    val reasonCode: String,
) {
    init {
        require(phase != null || transformationCategory != null) {
            "Trace event requires a block phase or transformation category"
        }
        require(TRACE_REASON_CODE.matches(reasonCode)) { "Trace reason must be a content-free code" }
    }
}

/**
 * Versioned configuration bounds only. The randomly selected delay is deliberately not a model field and
 * therefore cannot be persisted into an artifact or Saved Result through this type.
 */
@Serializable
data class BoundedDelayPolicy(
    val enabled: Boolean,
    val purpose: BoundedDelayPurpose,
    val minimum: DurationMillis,
    val maximum: DurationMillis,
    val validationReference: SafeSummary,
) {
    init {
        require(maximum.value >= minimum.value) { "Bounded delay maximum must not precede minimum" }
        if (enabled) {
            require(validationReference.value.isNotBlank()) {
                "Enabled delay requires a documented validation reference"
            }
        }
    }
}

private val TRACE_REASON_CODE = Regex("[A-Z][A-Z0-9_]{1,63}")
