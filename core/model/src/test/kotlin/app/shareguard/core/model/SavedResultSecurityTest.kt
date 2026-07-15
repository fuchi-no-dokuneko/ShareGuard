package app.shareguard.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows
import org.junit.Test

class SavedResultSecurityTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun revalidation_cannotPromoteBeyondOriginalVerifiedAssurance() {
        val saved = ModelFixtures.textSavedResult()

        assertThrows(IllegalArgumentException::class.java) {
            saved.revalidated(
                AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE,
                WallClockInstant(3_000),
            )
        }
    }

    @Test
    fun revalidationFailure_quarantinesAndBlocksManagedShare() {
        val saved = ModelFixtures.savedResult()

        val failed = saved.revalidationFailed(WallClockInstant(3_000))

        assertThat(failed.assuranceClass).isEqualTo(AssuranceClass.AS_0_UNVERIFIED)
        assertThat(failed.lifecycleState).isEqualTo(SavedResultLifecycleState.QUARANTINED)
        assertThat(failed.verificationState).isEqualTo(VerificationState.FAILED)
        assertThat(failed.artifactManifest.integrityState).isEqualTo(IntegrityState.INVALID)
        assertThat(failed.canManagedShare).isFalse()
    }

    @Test
    fun derivativeSavedResult_cannotClaimHigherAssurance() {
        val derivative = DerivativeArtifact(
            artifactId = ArtifactId("artifact-derivative"),
            reference = ArtifactReference("managed-derivative"),
            artifactRevision = ModelFixtures.artifactRevision,
            canonicalRevision = ModelFixtures.revision,
            mimeType = MimeType("image/png"),
            digest = ModelFixtures.digest('e'),
            byteCount = ByteCount(200),
            pixelSize = PixelSize(PixelDimension(100), PixelDimension(100)),
            sourceDependencyMap = ModelFixtures.dependencyMap(),
        )
        val bundle = OutputBundle(
            outputMode = OutputMode.DERIVATIVE_IMAGE,
            canonicalRevision = ModelFixtures.revision,
            derivativeArtifact = derivative,
        )
        val manifest = ArtifactManifest.fromBundle(bundle)

        assertThrows(IllegalArgumentException::class.java) {
            SavedResult.committed(
                savedResultId = SavedResultId("saved-derivative"),
                schemaVersion = SchemaVersion(1),
                displayLabel = DisplayLabel("Derivative"),
                outputMode = OutputMode.DERIVATIVE_IMAGE,
                artifactManifest = manifest,
                assuranceClass = AssuranceClass.AS_3_REBUILT_WITH_SOURCE_REGIONS,
                assuranceRationaleSummary = SafeSummary("Invalid promotion"),
                verificationSummaryReference = null,
                verificationSummary = null,
                importAnchor = ModelFixtures.importAnchor(),
                persistedAtWallClock = WallClockInstant(2_100),
                contentDigest = ModelFixtures.digest('f'),
                previewReference = null,
                favourite = false,
                createdByAppBuild = AppBuildId("build-1"),
            )
        }
    }

    @Test
    fun savedResultSerialization_excludesTransientAndSensitiveSourceData() {
        val sourceUriCanary = "content://malicious.provider/private/source"
        val sourceFilenameCanary = "source-secret-name.png"
        val ocrCanary = "OCR_SECRET_CANARY"
        val ledgerBeforeCanary = "LEDGER_BEFORE_SECRET"
        val sessionPathCanary = "/data/user/0/app/cache/session-secret"

        // The canaries are valid in their session-only model categories.
        val source = TextSource.snapshot(
            internalId = SourceId("source-sensitive"),
            sourceMime = MimeType("text/plain"),
            importMethod = ImportMethod.ANDROID_SHARE,
            plainText = "$sourceUriCanary $sourceFilenameCanary $ocrCanary $sessionPathCanary",
        )
        val change = ChangeEntry(
            changeId = ChangeId("change-sensitive"),
            blockId = BlockId("TXT-010"),
            blockVersion = BlockVersion(1),
            canonicalRevision = ModelFixtures.revision,
            category = FindingCategory.UNICODE,
            sourceLocation = null,
            beforeRepresentation = SensitiveRepresentation(ledgerBeforeCanary),
            afterRepresentation = SensitiveRepresentation("canonical"),
            reason = SafeSummary("Session-only change"),
            reversibleBeforeExport = true,
            semanticImpact = SemanticImpact.NONE,
            reviewLink = null,
            verificationId = null,
        )
        assertThat(json.encodeToString(source)).contains(sourceUriCanary)
        assertThat(json.encodeToString(change)).contains(ledgerBeforeCanary)

        val encodedSavedResult = json.encodeToString(ModelFixtures.savedResult())

        assertThat(encodedSavedResult).doesNotContain(sourceUriCanary)
        assertThat(encodedSavedResult).doesNotContain(sourceFilenameCanary)
        assertThat(encodedSavedResult).doesNotContain(ocrCanary)
        assertThat(encodedSavedResult).doesNotContain(ledgerBeforeCanary)
        assertThat(encodedSavedResult).doesNotContain(sessionPathCanary)
        assertThat(encodedSavedResult).doesNotContain("plainText")
        assertThat(encodedSavedResult).doesNotContain("sourceHandle")
        assertThat(encodedSavedResult).doesNotContain("beforeRepresentation")
    }

    @Test
    fun opaqueArtifactReferences_rejectUrisAndPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactReference("content://provider/item")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactReference("session/private/file")
        }
    }
}
