package app.shareguard.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class OutputBundleTest {
    @Test
    fun bothMode_acceptsArtifactsFromOneCanonicalAndArtifactRevision() {
        val bundle = ModelFixtures.bothBundle()

        assertThat(bundle.outputMode).isEqualTo(OutputMode.BOTH)
        assertThat(bundle.artifacts.map { it.kind }).containsExactly(
            ArtifactKind.CANONICAL_TEXT,
            ArtifactKind.REBUILT_IMAGE,
        ).inOrder()
        assertThat(bundle.canonicalRevision).isEqualTo(ModelFixtures.revision)
        assertThat(bundle.artifactRevision).isEqualTo(ModelFixtures.artifactRevision)
    }

    @Test
    fun bothMode_rejectsCanonicalRevisionMismatch() {
        val otherRevision = CanonicalRevision(2)
        val otherDependencies = SourceDependencyMap.create(
            otherRevision,
            listOf(
                SourceDependency(
                    dependencyId = DependencyId("dependency-revision-2"),
                    type = DependencyType.CANONICAL_DOCUMENT_REVISION,
                    origin = DependencyOrigin.CANONICAL_DOCUMENT,
                    canonicalRevision = otherRevision,
                    reason = SafeSummary("Other revision"),
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            OutputBundle(
                outputMode = OutputMode.BOTH,
                canonicalRevision = ModelFixtures.revision,
                textArtifact = ModelFixtures.textArtifact(),
                imageArtifact = ModelFixtures.imageArtifact(
                    canonicalRevision = otherRevision,
                    dependencyMap = otherDependencies,
                ),
            )
        }
    }

    @Test
    fun bothMode_rejectsArtifactRevisionMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            OutputBundle(
                outputMode = OutputMode.BOTH,
                canonicalRevision = ModelFixtures.revision,
                textArtifact = ModelFixtures.textArtifact(),
                imageArtifact = ModelFixtures.imageArtifact(artifactRevision = ArtifactRevision(2)),
            )
        }
    }

    @Test
    fun outputMode_rejectsWrongArtifactCombination() {
        assertThrows(IllegalArgumentException::class.java) {
            OutputBundle(
                outputMode = OutputMode.TEXT,
                canonicalRevision = ModelFixtures.revision,
                textArtifact = ModelFixtures.textArtifact(),
                imageArtifact = ModelFixtures.imageArtifact(),
            )
        }
    }

    @Test
    fun artifactManifest_rejectsModeMismatch() {
        val manifest = ArtifactManifest.fromBundle(ModelFixtures.bothBundle())

        assertThrows(IllegalArgumentException::class.java) {
            manifest.validateOutputMode(OutputMode.TEXT)
        }
    }
}
