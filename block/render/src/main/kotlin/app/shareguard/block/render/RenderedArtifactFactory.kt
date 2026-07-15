package app.shareguard.block.render

import app.shareguard.core.model.ByteCount
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.DerivativeArtifact
import app.shareguard.core.model.ImageArtifact
import app.shareguard.core.security.ContentDigester

class RenderedArtifactFactory(
    private val contentDigester: ContentDigester,
) {
    fun rebuiltImage(
        encoded: EncodedRenderedImage,
        identity: RebuiltArtifactIdentity,
        canonicalRevision: CanonicalRevision,
    ): ImageArtifact {
        val bytes = encoded.copyBytes()
        return try {
            ImageArtifact(
                artifactId = identity.artifactId,
                reference = identity.reference,
                artifactRevision = identity.artifactRevision,
                canonicalRevision = canonicalRevision,
                mimeType = encoded.mimeType,
                digest = contentDigester.digest(bytes),
                byteCount = ByteCount(encoded.byteCount),
                pixelSize = encoded.pixelSize,
                sourceDependencyMap = encoded.sourceDependencyMap,
            )
        } finally {
            bytes.fill(0)
        }
    }

    fun derivativeImage(
        encoded: EncodedRenderedImage,
        identity: RebuiltArtifactIdentity,
        canonicalRevision: CanonicalRevision,
    ): DerivativeArtifact {
        val bytes = encoded.copyBytes()
        return try {
            DerivativeArtifact(
                artifactId = identity.artifactId,
                reference = identity.reference,
                artifactRevision = identity.artifactRevision,
                canonicalRevision = canonicalRevision,
                mimeType = encoded.mimeType,
                digest = contentDigester.digest(bytes),
                byteCount = ByteCount(encoded.byteCount),
                pixelSize = encoded.pixelSize,
                sourceDependencyMap = encoded.sourceDependencyMap,
            )
        } finally {
            bytes.fill(0)
        }
    }
}
