package app.shareguard.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface OutputArtifact {
    val artifactId: ArtifactId
    val reference: ArtifactReference
    val artifactRevision: ArtifactRevision
    val canonicalRevision: CanonicalRevision
    val mimeType: MimeType
    val digest: ContentDigest
    val byteCount: ByteCount
    val kind: ArtifactKind
}

@Serializable
@SerialName("canonical_text_artifact")
data class TextArtifact(
    override val artifactId: ArtifactId,
    override val reference: ArtifactReference,
    override val artifactRevision: ArtifactRevision,
    override val canonicalRevision: CanonicalRevision,
    override val mimeType: MimeType,
    override val digest: ContentDigest,
    override val byteCount: ByteCount,
    val canonicalText: String,
) : OutputArtifact {
    override val kind: ArtifactKind = ArtifactKind.CANONICAL_TEXT

    init {
        require(mimeType.value.startsWith("text/")) { "Text artifact requires text MIME" }
        require(!canonicalText.contains('\u0000')) { "Canonical text cannot contain NUL" }
    }
}

@Serializable
@SerialName("rebuilt_image_artifact")
data class ImageArtifact(
    override val artifactId: ArtifactId,
    override val reference: ArtifactReference,
    override val artifactRevision: ArtifactRevision,
    override val canonicalRevision: CanonicalRevision,
    override val mimeType: MimeType,
    override val digest: ContentDigest,
    override val byteCount: ByteCount,
    val pixelSize: PixelSize,
    val sourceDependencyMap: SourceDependencyMap,
) : OutputArtifact {
    override val kind: ArtifactKind = ArtifactKind.REBUILT_IMAGE

    init {
        require(mimeType.value.startsWith("image/")) { "Image artifact requires image MIME" }
        require(sourceDependencyMap.canonicalRevision == canonicalRevision) {
            "Image dependency map revision mismatch"
        }
    }
}

@Serializable
@SerialName("derivative_image_artifact")
data class DerivativeArtifact(
    override val artifactId: ArtifactId,
    override val reference: ArtifactReference,
    override val artifactRevision: ArtifactRevision,
    override val canonicalRevision: CanonicalRevision,
    override val mimeType: MimeType,
    override val digest: ContentDigest,
    override val byteCount: ByteCount,
    val pixelSize: PixelSize,
    val sourceDependencyMap: SourceDependencyMap,
) : OutputArtifact {
    override val kind: ArtifactKind = ArtifactKind.DERIVATIVE_IMAGE

    init {
        require(mimeType.value.startsWith("image/")) { "Derivative artifact requires image MIME" }
        require(sourceDependencyMap.canonicalRevision == canonicalRevision) {
            "Derivative dependency map revision mismatch"
        }
    }
}

@Serializable
data class OutputBundle(
    val outputMode: OutputMode,
    val canonicalRevision: CanonicalRevision,
    val textArtifact: TextArtifact? = null,
    val imageArtifact: ImageArtifact? = null,
    val derivativeArtifact: DerivativeArtifact? = null,
    val verificationReport: VerificationReport? = null,
) {
    init {
        when (outputMode) {
            OutputMode.TEXT -> require(textArtifact != null && imageArtifact == null && derivativeArtifact == null) {
                "TEXT bundle requires only canonical text"
            }
            OutputMode.REBUILT_IMAGE -> require(textArtifact == null && imageArtifact != null && derivativeArtifact == null) {
                "REBUILT_IMAGE bundle requires only a rebuilt image"
            }
            OutputMode.BOTH -> require(textArtifact != null && imageArtifact != null && derivativeArtifact == null) {
                "BOTH bundle requires canonical text and rebuilt image"
            }
            OutputMode.DERIVATIVE_IMAGE -> require(textArtifact == null && imageArtifact == null && derivativeArtifact != null) {
                "DERIVATIVE_IMAGE bundle requires only a derivative image"
            }
        }
        val artifacts = listOfNotNull(textArtifact, imageArtifact, derivativeArtifact)
        require(artifacts.all { it.canonicalRevision == canonicalRevision }) {
            "All bundle artifacts must share one canonical revision"
        }
        require(artifacts.map { it.artifactRevision }.distinct().size == 1) {
            "All bundle artifacts must share one artifact revision"
        }
        if (verificationReport != null) {
            require(verificationReport.artifactRevision == artifacts.singleRevision()) {
                "Verification report revision does not match bundle artifacts"
            }
        }
    }

    val artifacts: ImmutableList<OutputArtifact>
        get() = listOfNotNull(textArtifact, imageArtifact, derivativeArtifact).toImmutableList()

    val artifactRevision: ArtifactRevision
        get() = artifacts.singleRevision()

    fun withVerification(report: VerificationReport): OutputBundle = copy(verificationReport = report)
}

private fun List<OutputArtifact>.singleRevision(): ArtifactRevision =
    firstOrNull()?.artifactRevision ?: error("Output bundle has no artifacts")

private fun ImmutableList<OutputArtifact>.singleRevision(): ArtifactRevision =
    firstOrNull()?.artifactRevision ?: error("Output bundle has no artifacts")
