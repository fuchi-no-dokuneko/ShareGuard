package app.shareguard.core.model

import kotlinx.serialization.Serializable

private val OPAQUE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val BLOCK_ID = Regex("[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-[0-9]{3}")
private val MIME_TYPE = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
private val LANGUAGE_TAG = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")
private val HEX = Regex("[0-9a-f]+")

private fun requireOpaque(value: String, label: String) {
    require(OPAQUE_ID.matches(value)) { "$label must be a non-path opaque identifier" }
}

@Serializable @JvmInline value class SessionId(val value: String) {
    init { requireOpaque(value, "sessionId") }
}

@Serializable @JvmInline value class WorkflowId(val value: String) {
    init { requireOpaque(value, "workflowId") }
}

@Serializable @JvmInline value class SourceId(val value: String) {
    init { requireOpaque(value, "sourceId") }
}

@Serializable @JvmInline value class SourceHandle(val value: String) {
    init { requireOpaque(value, "sourceHandle") }
}

@Serializable @JvmInline value class BlockId(val value: String) {
    init { require(BLOCK_ID.matches(value)) { "Invalid normative blockId: $value" } }
}

@Serializable @JvmInline value class CanonicalBlockId(val value: String) {
    init { requireOpaque(value, "canonicalBlockId") }
}

@Serializable @JvmInline value class FindingId(val value: String) {
    init { requireOpaque(value, "findingId") }
}

@Serializable @JvmInline value class DecisionId(val value: String) {
    init { requireOpaque(value, "decisionId") }
}

@Serializable @JvmInline value class ChangeId(val value: String) {
    init { requireOpaque(value, "changeId") }
}

@Serializable @JvmInline value class ChangeLedgerId(val value: String) {
    init { requireOpaque(value, "changeLedgerId") }
}

@Serializable @JvmInline value class UrlTokenId(val value: String) {
    init { requireOpaque(value, "urlTokenId") }
}

@Serializable @JvmInline value class ImageRegionId(val value: String) {
    init { requireOpaque(value, "imageRegionId") }
}

@Serializable @JvmInline value class DependencyId(val value: String) {
    init { requireOpaque(value, "dependencyId") }
}

@Serializable @JvmInline value class ArtifactId(val value: String) {
    init { requireOpaque(value, "artifactId") }
}

@Serializable @JvmInline value class ArtifactReference(val value: String) {
    init { requireOpaque(value, "artifactReference") }
}

@Serializable @JvmInline value class PreviewReference(val value: String) {
    init { requireOpaque(value, "previewReference") }
}

@Serializable @JvmInline value class VerificationSummaryReference(val value: String) {
    init { requireOpaque(value, "verificationSummaryReference") }
}

@Serializable @JvmInline value class VerificationId(val value: String) {
    init { requireOpaque(value, "verificationId") }
}

@Serializable @JvmInline value class TraceEventId(val value: String) {
    init { requireOpaque(value, "traceEventId") }
}

@Serializable @JvmInline value class SavedResultId(val value: String) {
    init { requireOpaque(value, "savedResultId") }
}

@Serializable @JvmInline value class BootSessionReference(val value: String) {
    init { requireOpaque(value, "bootSessionReference") }
}

@Serializable @JvmInline value class AppBuildId(val value: String) {
    init { requireOpaque(value, "appBuildId") }
}

@Serializable @JvmInline value class SchemaVersion(val value: Int) {
    init { require(value > 0) { "schemaVersion must be positive" } }
}

@Serializable @JvmInline value class WorkflowVersion(val value: Int) {
    init { require(value > 0) { "workflowVersion must be positive" } }
}

@Serializable @JvmInline value class BlockVersion(val value: Int) {
    init { require(value > 0) { "blockVersion must be positive" } }
}

@Serializable @JvmInline value class ExecutionRevision(val value: Long) {
    init { require(value >= 0) { "executionRevision cannot be negative" } }
    fun next(): ExecutionRevision = ExecutionRevision(Math.addExact(value, 1))
}

@Serializable @JvmInline value class CanonicalRevision(val value: Long) {
    init { require(value > 0) { "canonicalRevision must be positive" } }
    fun next(): CanonicalRevision = CanonicalRevision(Math.addExact(value, 1))
}

@Serializable @JvmInline value class ArtifactRevision(val value: Long) {
    init { require(value > 0) { "artifactRevision must be positive" } }
}

@Serializable @JvmInline value class WallClockInstant(val epochMillis: Long) {
    init { require(epochMillis >= 0) { "wall-clock instant cannot be negative" } }
}

@Serializable @JvmInline value class MonotonicInstant(val elapsedRealtimeNanos: Long) {
    init { require(elapsedRealtimeNanos >= 0) { "monotonic instant cannot be negative" } }
}

@Serializable @JvmInline value class ByteCount(val value: Long) {
    init { require(value >= 0) { "byte count cannot be negative" } }
}

@Serializable @JvmInline value class DurationMillis(val value: Long) {
    init { require(value >= 0) { "duration cannot be negative" } }
}

@Serializable @JvmInline value class PixelDimension(val value: Int) {
    init { require(value > 0) { "pixel dimension must be positive" } }
}

@Serializable @JvmInline value class MimeType(val value: String) {
    init { require(MIME_TYPE.matches(value)) { "Invalid MIME type: $value" } }
}

@Serializable @JvmInline value class LanguageTag(val value: String) {
    init { require(LANGUAGE_TAG.matches(value)) { "Invalid BCP-47-like language tag: $value" } }
}

@Serializable @JvmInline value class ContentDigest(val sha256: String) {
    init {
        require(sha256.length == 64 && HEX.matches(sha256)) {
            "Content digest must be a lowercase SHA-256 hex value"
        }
    }
}

@Serializable @JvmInline value class SafeSummary(val value: String) {
    init {
        require(value.length <= 1_024) { "Summary is too long" }
        require(!value.contains('\u0000')) { "Summary contains NUL" }
    }
}

@Serializable @JvmInline value class DisplayLabel(val value: String) {
    init {
        require(value.isNotBlank()) { "Display label cannot be blank" }
        require(value.length <= 128) { "Display label is too long" }
        require(!value.contains('/') && !value.contains('\\') && !value.contains('\u0000')) {
            "Display label cannot contain path separators or NUL"
        }
    }
}

@Serializable
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f }) {
            "Normalized bounds must be finite values from 0 to 1"
        }
        require(right >= left && bottom >= top) { "Normalized bounds are inverted" }
    }
}

@Serializable
data class PixelSize(
    val width: PixelDimension,
    val height: PixelDimension,
)
