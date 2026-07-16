package app.shareguard.block.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.DependencyId
import app.shareguard.core.model.DependencyOrigin
import app.shareguard.core.model.DependencyType
import app.shareguard.core.model.MimeType
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SourceDependency
import app.shareguard.core.model.SourceDependencyMap
import app.shareguard.core.model.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import kotlin.coroutines.coroutineContext

data class DerivativeResourcePlan(
    val outputWidthPx: Int,
    val outputHeightPx: Int,
    val maximumPixelCount: Long,
) {
    init {
        require(outputWidthPx > 0 && outputHeightPx > 0 && maximumPixelCount > 0)
        val pixels = try {
            Math.multiplyExact(outputWidthPx.toLong(), outputHeightPx.toLong())
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Derivative dimensions overflow")
        }
        require(pixels <= maximumPixelCount)
    }
}

data class QuantizationBenchmarkApproval(
    val policyId: String,
    val corpusVersion: String,
    val readabilityAccepted: Boolean,
    val diagnosticComparisonReviewed: Boolean,
) {
    init {
        require(policyId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")))
        require(corpusVersion.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")))
    }
}

data class DerivativePolicy(
    val resourcePlan: DerivativeResourcePlan,
    val quantizationBitsPerChannel: Int? = null,
    val quantizationApproval: QuantizationBenchmarkApproval? = null,
    val stochasticPerturbationAmplitude: Int = 0,
    val warningVersion: String,
    val outputMimeType: MimeType = MimeType("image/png"),
) {
    init {
        require(quantizationBitsPerChannel == null || quantizationBitsPerChannel in 4..8)
        require(stochasticPerturbationAmplitude in 0..3)
        require(warningVersion.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")))
        require(outputMimeType == MimeType("image/png"))
        require((quantizationBitsPerChannel == null) == (quantizationApproval == null))
    }
}

data class DerivativeAcknowledgement(
    val warningVersion: String,
    val acknowledgedForThisExport: Boolean,
)

data class DerivativeRenderRequest(
    val orientationAppliedSource: Bitmap,
    val canonicalRevision: CanonicalRevision,
    val sourceDependencyMap: SourceDependencyMap,
    val policy: DerivativePolicy,
    val acknowledgement: DerivativeAcknowledgement,
) {
    init {
        require(!orientationAppliedSource.isRecycled)
        require(sourceDependencyMap.canonicalRevision == canonicalRevision)
    }
}

class DerivativeImageRenderer(
    private val pngSerializer: StrictPngSerializer = StrictPngSerializer(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun render(request: DerivativeRenderRequest): EncodedRenderedImage = withContext(dispatcher) {
        validateExportGate(request)
        coroutineContext.ensureActive()
        val plan = request.policy.resourcePlan
        val output = allocate(plan.outputWidthPx, plan.outputHeightPx)
        val operations = mutableListOf(
            RenderOperation(RenderOperationCode.FRESH_CANVAS_ALLOCATED),
            RenderOperation(RenderOperationCode.DERIVATIVE_WARNING_ACKNOWLEDGED),
        )
        try {
            val canvas = Canvas(output)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                isDither = false
            }
            canvas.drawBitmap(
                request.orientationAppliedSource,
                null,
                RectF(0f, 0f, output.width.toFloat(), output.height.toFloat()),
                paint,
            )
            operations += RenderOperation(RenderOperationCode.DERIVATIVE_RESAMPLED)
            forceOpaqueChannels(output)
            operations += RenderOperation(RenderOperationCode.DERIVATIVE_CHANNELS_CANONICALIZED)
            request.policy.quantizationBitsPerChannel?.let { bits ->
                val approval = request.policy.quantizationApproval
                    ?: throw RenderException(RenderFailureCode.DERIVATIVE_POLICY_NOT_BENCHMARKED)
                if (!approval.readabilityAccepted || !approval.diagnosticComparisonReviewed) {
                    throw RenderException(RenderFailureCode.DERIVATIVE_POLICY_NOT_BENCHMARKED)
                }
                quantize(output, bits)
                operations += RenderOperation(RenderOperationCode.DERIVATIVE_QUANTIZED)
            }
            if (request.policy.stochasticPerturbationAmplitude > 0) {
                perturbWithFreshRandom(output, request.policy.stochasticPerturbationAmplitude)
                operations += RenderOperation(RenderOperationCode.EPHEMERAL_PERTURBATION_APPLIED)
            }
            val dependencyMap = addDerivativeDependencies(request.sourceDependencyMap)
            val (bytes, evidence) = pngSerializer.serializeOpaque(output)
            operations += RenderOperation(RenderOperationCode.PNG_SERIALIZED)
            operations += RenderOperation(RenderOperationCode.PNG_REOPENED)
            EncodedRenderedImage(
                bytes = bytes,
                pixelSize = evidence.pixelSize,
                mimeType = request.policy.outputMimeType,
                sourceDependencyMap = dependencyMap,
                operations = operations,
            )
        } finally {
            output.eraseColor(0)
            output.recycle()
        }
    }

    private fun validateExportGate(request: DerivativeRenderRequest) {
        val acknowledgement = request.acknowledgement
        if (!acknowledgement.acknowledgedForThisExport ||
            acknowledgement.warningVersion != request.policy.warningVersion
        ) {
            throw RenderException(RenderFailureCode.DERIVATIVE_WARNING_NOT_ACKNOWLEDGED)
        }
        if (!request.sourceDependencyMap.retainsSourcePixels) {
            throw RenderException(RenderFailureCode.DERIVATIVE_DEPENDENCY_MISSING)
        }
        if (request.policy.quantizationBitsPerChannel != null) {
            val approval = request.policy.quantizationApproval
            if (approval == null || !approval.readabilityAccepted || !approval.diagnosticComparisonReviewed) {
                throw RenderException(RenderFailureCode.DERIVATIVE_POLICY_NOT_BENCHMARKED)
            }
        }
    }

    private fun allocate(width: Int, height: Int): Bitmap = try {
        createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.setHasAlpha(false) }
    } catch (_: IllegalArgumentException) {
        throw RenderException(RenderFailureCode.CANVAS_ALLOCATION_FAILED)
    } catch (_: OutOfMemoryError) {
        throw RenderException(RenderFailureCode.CANVAS_ALLOCATION_FAILED)
    }

    private fun forceOpaqueChannels(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width)
        for (row in 0 until bitmap.height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, row, bitmap.width, 1)
            for (index in pixels.indices) pixels[index] = pixels[index] or 0xff000000.toInt()
            bitmap.setPixels(pixels, 0, bitmap.width, 0, row, bitmap.width, 1)
        }
        pixels.fill(0)
        bitmap.setHasAlpha(false)
    }

    private fun quantize(bitmap: Bitmap, bits: Int) {
        val shift = 8 - bits
        if (shift == 0) return
        val mask = (0xff shl shift) and 0xff
        val pixels = IntArray(bitmap.width)
        for (row in 0 until bitmap.height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, row, bitmap.width, 1)
            for (index in pixels.indices) {
                val pixel = pixels[index]
                pixels[index] = Color.rgb(
                    Color.red(pixel) and mask,
                    Color.green(pixel) and mask,
                    Color.blue(pixel) and mask,
                )
            }
            bitmap.setPixels(pixels, 0, bitmap.width, 0, row, bitmap.width, 1)
        }
        pixels.fill(0)
    }

    private fun perturbWithFreshRandom(bitmap: Bitmap, amplitude: Int) {
        // Constructed for this render only. No seed is accepted, stored, logged, or device-derived.
        val random = SecureRandom()
        val pixels = IntArray(bitmap.width)
        val randomBytes = ByteArray(bitmap.width * 3)
        try {
            for (row in 0 until bitmap.height) {
                random.nextBytes(randomBytes)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, row, bitmap.width, 1)
                for (index in pixels.indices) {
                    val base = index * 3
                    fun delta(offset: Int): Int =
                        ((randomBytes[base + offset].toInt() and 0xff) % (amplitude * 2 + 1)) - amplitude
                    val pixel = pixels[index]
                    pixels[index] = Color.rgb(
                        (Color.red(pixel) + delta(0)).coerceIn(0, 255),
                        (Color.green(pixel) + delta(1)).coerceIn(0, 255),
                        (Color.blue(pixel) + delta(2)).coerceIn(0, 255),
                    )
                }
                bitmap.setPixels(pixels, 0, bitmap.width, 0, row, bitmap.width, 1)
            }
        } finally {
            pixels.fill(0)
            randomBytes.fill(0)
        }
    }

    private fun addDerivativeDependencies(input: SourceDependencyMap): SourceDependencyMap {
        val entries = input.entries.toMutableList()
        val ids = entries.mapTo(mutableSetOf()) { it.dependencyId }
        var index = 1
        fun nextId(): DependencyId {
            while (true) {
                val id = DependencyId("der-${input.canonicalRevision.value}-${index++}")
                if (ids.add(id)) return id
            }
        }
        entries += SourceDependency(
            dependencyId = nextId(),
            type = DependencyType.RENDERER_GENERATED_PRIMITIVE,
            origin = DependencyOrigin.GENERATED,
            canonicalRevision = input.canonicalRevision,
            reason = SafeSummary("Fresh derivative resample and canonical channel conversion"),
        )
        return SourceDependencyMap(
            canonicalRevision = input.canonicalRevision,
            entries = entries.toImmutableList(),
            scope = SafeSummary("App-defined dependencies; platform and library internals are not enumerated"),
        )
    }
}
