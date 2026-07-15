package app.shareguard.block.image

import app.shareguard.core.model.SafeSummary
import kotlin.math.abs

data class DetectorCalibration(
    val detectorVersion: SafeSummary,
    val corpusReference: SafeSummary,
    val corpusSampleCount: Int,
) {
    init {
        require(detectorVersion.value.isNotBlank() && corpusReference.value.isNotBlank())
        require(corpusSampleCount > 0)
    }
}

sealed interface DetectorPolicy {
    data object Disabled : DetectorPolicy

    /** An enabled threshold cannot exist without an identified calibration corpus. */
    data class CorpusCalibrated(
        val calibration: DetectorCalibration,
        val threshold: Double,
    ) : DetectorPolicy {
        init { require(threshold.isFinite()) }
    }
}

data class ImageDiagnosticPolicy(
    val maximumPixelsExamined: Int,
    val lsb: DetectorPolicy = DetectorPolicy.Disabled,
    val spatial: DetectorPolicy = DetectorPolicy.Disabled,
    val frequencyAndRepetition: DetectorPolicy = DetectorPolicy.Disabled,
    val resourceValidationReference: SafeSummary,
) {
    init {
        require(maximumPixelsExamined > 0)
        require(resourceValidationReference.value.isNotBlank())
    }
}

enum class DiagnosticDisposition { NOT_RUN, EVIDENCE_RECORDED, CALIBRATED_THRESHOLD_EXCEEDED }

data class DiagnosticEvidence(
    val disposition: DiagnosticDisposition,
    val scalarEvidence: Map<String, Double>,
    val calibration: DetectorCalibration?,
    /** These comparative detectors can never certify that hidden information is absent. */
    val canCertifyAbsence: Boolean = false,
) {
    init {
        require(!canCertifyAbsence)
        require(scalarEvidence.keys.all { it.matches(Regex("[a-z0-9_]{1,64}")) })
        require(scalarEvidence.values.all(Double::isFinite))
        require((disposition == DiagnosticDisposition.NOT_RUN) == scalarEvidence.isEmpty())
        require((disposition == DiagnosticDisposition.NOT_RUN) == (calibration == null))
    }
}

data class ImageDiagnosticReport(
    val lsb: DiagnosticEvidence,
    val spatial: DiagnosticEvidence,
    val frequencyAndRepetition: DiagnosticEvidence,
    val sampledPixels: Int,
    val canCertifyAbsence: Boolean = false,
) {
    init { require(sampledPixels >= 0); require(!canCertifyAbsence) }
}

/** Diagnostic-only evidence. It is deliberately not an allow/deny gate and cannot raise assurance. */
class ImageDiagnosticScanner {
    fun scan(image: PixelImage, policy: ImageDiagnosticPolicy): ImageDiagnosticReport {
        check(!image.isClosed)
        val anyEnabled = listOf(policy.lsb, policy.spatial, policy.frequencyAndRepetition)
            .any { it is DetectorPolicy.CorpusCalibrated }
        if (!anyEnabled) {
            val disabled = notRun()
            return ImageDiagnosticReport(disabled, disabled, disabled, 0)
        }
        val total = CheckedImageArithmetic.multiply(image.width.toLong(), image.height.toLong())
        val stride = maxOf(1, ((total + policy.maximumPixelsExamined - 1) / policy.maximumPixelsExamined).toInt())
        val pixels = sampledPixels(image, stride, policy.maximumPixelsExamined)
        try {
            return ImageDiagnosticReport(
                lsb = evaluate(policy.lsb, lsbEvidence(pixels)),
                spatial = evaluate(policy.spatial, spatialEvidence(pixels)),
                frequencyAndRepetition = evaluate(
                    policy.frequencyAndRepetition,
                    frequencyEvidence(image, stride, policy.maximumPixelsExamined),
                ),
                sampledPixels = pixels.size,
            )
        } finally {
            pixels.fill(0)
        }
    }

    private fun sampledPixels(image: PixelImage, stride: Int, maximum: Int): IntArray {
        val output = IntArray(minOf(maximum, ((image.width.toLong() * image.height + stride - 1) / stride).toInt()))
        var outputIndex = 0
        var flat = 0
        loop@ for (y in 0 until image.height) for (x in 0 until image.width) {
            if (flat % stride == 0) {
                if (outputIndex >= output.size) break@loop
                output[outputIndex++] = image.argbAt(x, y)
            }
            flat++
        }
        return if (outputIndex == output.size) output else output.copyOf(outputIndex).also { output.fill(0) }
    }

    private fun lsbEvidence(pixels: IntArray): Map<String, Double> {
        if (pixels.isEmpty()) return mapOf("sample_count" to 0.0, "one_ratio" to 0.0, "transition_ratio" to 0.0)
        var ones = 0L
        var transitions = 0L
        var previous = -1
        for (pixel in pixels) {
            for (shift in intArrayOf(0, 8, 16)) {
                val bit = (pixel ushr shift) and 1
                ones += bit
                if (previous >= 0 && previous != bit) transitions++
                previous = bit
            }
        }
        val bits = pixels.size.toLong() * 3L
        return mapOf(
            "sample_count" to pixels.size.toDouble(),
            "one_ratio" to ones.toDouble() / bits,
            "transition_ratio" to transitions.toDouble() / maxOf(1L, bits - 1L),
        )
    }

    private fun spatialEvidence(pixels: IntArray): Map<String, Double> {
        if (pixels.size < 2) return mapOf("mean_adjacent_delta" to 0.0, "delta_variance" to 0.0)
        var sum = 0.0
        var squared = 0.0
        for (index in 1 until pixels.size) {
            val delta = abs(luma(pixels[index]) - luma(pixels[index - 1])).toDouble()
            sum += delta
            squared += delta * delta
        }
        val count = (pixels.size - 1).toDouble()
        val mean = sum / count
        return mapOf(
            "mean_adjacent_delta" to mean,
            "delta_variance" to maxOf(0.0, squared / count - mean * mean),
        )
    }

    private fun frequencyEvidence(image: PixelImage, stride: Int, maximum: Int): Map<String, Double> {
        var examined = 0
        var laplacianEnergy = 0.0
        var laplacianCount = 0
        val blockFingerprints = HashMap<Long, Int>()
        val blockStep = maxOf(8, stride)
        var y = 0
        while (y < image.height && examined < maximum) {
            var x = 0
            while (x < image.width && examined < maximum) {
                var sum = 0L
                var sumSquares = 0L
                var count = 0
                val endY = minOf(image.height, y + 8)
                val endX = minOf(image.width, x + 8)
                for (py in y until endY) for (px in x until endX) {
                    if (examined >= maximum) break
                    val value = luma(image.argbAt(px, py))
                    sum += value
                    sumSquares += value.toLong() * value
                    count++
                    examined++
                    if (px > 0 && px + 1 < image.width && py > 0 && py + 1 < image.height) {
                        val laplacian = 4 * value - luma(image.argbAt(px - 1, py)) -
                            luma(image.argbAt(px + 1, py)) - luma(image.argbAt(px, py - 1)) -
                            luma(image.argbAt(px, py + 1))
                        laplacianEnergy += laplacian.toDouble() * laplacian
                        laplacianCount++
                    }
                }
                if (count > 0) {
                    // Quantized first and second moments provide a content-free repeated-block comparison.
                    val fingerprint = ((sum / count / 8) shl 32) xor (sumSquares / count / 64)
                    blockFingerprints[fingerprint] = (blockFingerprints[fingerprint] ?: 0) + 1
                }
                x += blockStep
            }
            y += blockStep
        }
        val repeated = blockFingerprints.values.sumOf { maxOf(0, it - 1) }
        val blocks = blockFingerprints.values.sum()
        return mapOf(
            "laplacian_energy" to laplacianEnergy / maxOf(1, laplacianCount),
            "repeated_block_ratio" to repeated.toDouble() / maxOf(1, blocks),
            "block_count" to blocks.toDouble(),
        )
    }

    private fun evaluate(policy: DetectorPolicy, evidence: Map<String, Double>): DiagnosticEvidence = when (policy) {
        DetectorPolicy.Disabled -> notRun()
        is DetectorPolicy.CorpusCalibrated -> {
            // The first non-count scalar is the versioned detector score; raw evidence remains available.
            val score = evidence.entries.firstOrNull { !it.key.endsWith("count") }?.value ?: 0.0
            DiagnosticEvidence(
                if (score >= policy.threshold) DiagnosticDisposition.CALIBRATED_THRESHOLD_EXCEEDED
                else DiagnosticDisposition.EVIDENCE_RECORDED,
                evidence,
                policy.calibration,
            )
        }
    }

    private fun notRun() = DiagnosticEvidence(DiagnosticDisposition.NOT_RUN, emptyMap(), null)

    private fun luma(pixel: Int): Int =
        (((pixel ushr 16 and 0xff) * 77 + (pixel ushr 8 and 0xff) * 150 + (pixel and 0xff) * 29) ushr 8)
}
