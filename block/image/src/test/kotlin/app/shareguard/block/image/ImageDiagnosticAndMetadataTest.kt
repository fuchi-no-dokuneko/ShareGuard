package app.shareguard.block.image

import android.graphics.Bitmap
import android.graphics.Color
import app.shareguard.core.model.SafeSummary
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageDiagnosticAndMetadataTest {
    @Test
    fun `disabled diagnostics are explicitly NOT_RUN and never certify absence`() {
        val image = ArgbPixelImage(2, 2, intArrayOf(1, 2, 3, 4))
        val report = ImageDiagnosticScanner().scan(
            image,
            ImageDiagnosticPolicy(4, resourceValidationReference = SafeSummary("diagnostic-corpus-v1")),
        )

        assertThat(report.sampledPixels).isEqualTo(0)
        assertThat(report.lsb.disposition).isEqualTo(DiagnosticDisposition.NOT_RUN)
        assertThat(report.spatial.disposition).isEqualTo(DiagnosticDisposition.NOT_RUN)
        assertThat(report.canCertifyAbsence).isFalse()
        image.close()
    }

    @Test
    fun `enabled diagnostic evidence is bounded calibrated comparative evidence only`() {
        val calibration = DetectorCalibration(SafeSummary("lsb-v1"), SafeSummary("tc-img-corpus-v1"), 20)
        val enabled = DetectorPolicy.CorpusCalibrated(calibration, threshold = 0.0)
        val pixels = IntArray(100) { index -> Color.rgb(index, index * 3 and 0xff, index * 7 and 0xff) }
        val image = ArgbPixelImage(10, 10, pixels)

        val report = ImageDiagnosticScanner().scan(
            image,
            ImageDiagnosticPolicy(17, enabled, enabled, enabled, SafeSummary("resource-plan-v1")),
        )

        assertThat(report.sampledPixels).isAtMost(17)
        assertThat(report.lsb.disposition).isEqualTo(DiagnosticDisposition.CALIBRATED_THRESHOLD_EXCEEDED)
        assertThat(report.lsb.calibration).isEqualTo(calibration)
        assertThat(report.lsb.canCertifyAbsence).isFalse()
        assertThat(report.frequencyAndRepetition.scalarEvidence.keys)
            .containsAtLeast("laplacian_energy", "repeated_block_ratio")
        image.close()
    }

    @Test
    fun `maintained metadata inventory emits schema identifiers not source values and erases input`() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            output.toByteArray()
        }
        bitmap.recycle()
        val source = ByteArrayMetadataInputSource(bytes)

        val result = MaintainedMetadataInventory().inspect(
            source,
            MetadataInventoryPolicy(128, 2_048, validationReference = SafeSummary("metadata-corpus-v1")),
        )

        assertThat(result.parserFailed).isFalse()
        assertThat(result.complete).isTrue()
        assertThat(result.directories).isNotEmpty()
        assertThat(result.directories.flatMap { it.tags }.all {
            it.schemaIdentifier.matches(Regex("[A-Z0-9_?]{1,96}"))
        }).isTrue()
        assertThat(source.isZeroizedForTest()).isTrue()
    }

    @Test
    fun `malformed metadata is fail-closed and source is still erased`() {
        val source = ByteArrayMetadataInputSource("not-an-image SECRET_VALUE".encodeToByteArray())

        val result = MaintainedMetadataInventory().inspect(
            source,
            MetadataInventoryPolicy(16, 32, validationReference = SafeSummary("metadata-corpus-v1")),
        )

        assertThat(result.parserFailed).isTrue()
        assertThat(result.complete).isFalse()
        assertThat(result.toString()).doesNotContain("SECRET_VALUE")
        assertThat(source.isZeroizedForTest()).isTrue()
    }

    @Test
    fun `thumbnail retention cannot be configured without explicit approval`() {
        assertThrows(IllegalArgumentException::class.java) {
            MetadataInventoryPolicy(
                16,
                32,
                ThumbnailPolicy.RETAIN_WITH_EXPLICIT_APPROVAL,
                thumbnailRetentionApproved = false,
                validationReference = SafeSummary("metadata-corpus-v1"),
            )
        }
    }
}
