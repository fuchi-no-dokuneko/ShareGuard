package app.shareguard.block.image

import app.shareguard.core.model.SafeSummary

object CheckedImageArithmetic {
    fun add(first: Long, second: Long): Long = try {
        Math.addExact(first, second)
    } catch (_: ArithmeticException) {
        throw ImageArithmeticOverflowException()
    }

    fun multiply(first: Long, second: Long): Long = try {
        Math.multiplyExact(first, second)
    } catch (_: ArithmeticException) {
        throw ImageArithmeticOverflowException()
    }

    fun decodedBytes(width: Int, height: Int, bytesPerPixel: Int): Long {
        require(width > 0 && height > 0 && bytesPerPixel > 0)
        return multiply(multiply(width.toLong(), height.toLong()), bytesPerPixel.toLong())
    }
}

class ImageArithmeticOverflowException : IllegalArgumentException("Image size arithmetic overflow")

enum class AnimatedInputPolicy { REJECT, FIRST_FRAME_REVIEW_REQUIRED }
enum class DecodeStrategy { FULL, SAMPLED, TILED, REJECT }
enum class ResourcePlanReason {
    WITHIN_VALIDATED_BUDGET,
    SAMPLING_REQUIRED,
    TILING_REQUIRED,
    PIXEL_LIMIT_EXCEEDED,
    DIMENSION_LIMIT_EXCEEDED,
    MEMORY_LIMIT_EXCEEDED,
    ANIMATION_REJECTED,
    FRAME_COUNT_UNKNOWN,
    ARITHMETIC_OVERFLOW,
}

/** Resource thresholds must cite device/corpus validation; this type intentionally has no guessed defaults. */
data class ImageResourcePolicy(
    val maximumWidth: Int,
    val maximumHeight: Int,
    val maximumPixels: Long,
    val maximumDecodedBytes: Long,
    val maximumFrames: Int,
    val allowSampling: Boolean,
    val allowTiling: Boolean,
    val animatedInputPolicy: AnimatedInputPolicy,
    val validationReference: SafeSummary,
) {
    init {
        require(maximumWidth > 0 && maximumHeight > 0)
        require(maximumPixels > 0 && maximumDecodedBytes > 0 && maximumFrames > 0)
        require(validationReference.value.isNotBlank()) { "Resource policy requires validation evidence" }
    }
}

data class DecodeResourcePlan(
    val strategy: DecodeStrategy,
    val sampleSize: Int,
    val estimatedDecodedBytes: Long?,
    val reason: ResourcePlanReason,
    val reviewRequired: Boolean,
) {
    init {
        require(sampleSize > 0 && sampleSize.countOneBits() == 1) { "sampleSize must be a power of two" }
        require(estimatedDecodedBytes == null || estimatedDecodedBytes >= 0)
    }

    val approved: Boolean
        get() = strategy != DecodeStrategy.REJECT
}

class ImageResourcePlanner {
    fun plan(header: ImageHeaderModel, policy: ImageResourcePolicy): DecodeResourcePlan {
        if (header.width > policy.maximumWidth || header.height > policy.maximumHeight) {
            return DecodeResourcePlan(DecodeStrategy.REJECT, 1, null, ResourcePlanReason.DIMENSION_LIMIT_EXCEEDED, false)
        }
        if (header.animated) {
            if (header.frameCount == null) {
                return DecodeResourcePlan(DecodeStrategy.REJECT, 1, null, ResourcePlanReason.FRAME_COUNT_UNKNOWN, true)
            }
            if (header.frameCount > policy.maximumFrames || policy.animatedInputPolicy == AnimatedInputPolicy.REJECT) {
                return DecodeResourcePlan(DecodeStrategy.REJECT, 1, null, ResourcePlanReason.ANIMATION_REJECTED, true)
            }
        }
        val pixels = try {
            CheckedImageArithmetic.multiply(header.width.toLong(), header.height.toLong())
        } catch (_: ImageArithmeticOverflowException) {
            return DecodeResourcePlan(DecodeStrategy.REJECT, 1, null, ResourcePlanReason.ARITHMETIC_OVERFLOW, false)
        }
        if (pixels > policy.maximumPixels && !policy.allowSampling && !policy.allowTiling) {
            return DecodeResourcePlan(DecodeStrategy.REJECT, 1, null, ResourcePlanReason.PIXEL_LIMIT_EXCEEDED, false)
        }
        val bytesPerPixel = when {
            header.bitDepth > 8 -> 8
            header.channels >= 3 -> 4
            else -> 2
        }
        val fullBytes = try {
            CheckedImageArithmetic.decodedBytes(header.width, header.height, bytesPerPixel)
        } catch (_: ImageArithmeticOverflowException) {
            return DecodeResourcePlan(DecodeStrategy.REJECT, 1, null, ResourcePlanReason.ARITHMETIC_OVERFLOW, false)
        }
        if (pixels <= policy.maximumPixels && fullBytes <= policy.maximumDecodedBytes) {
            return DecodeResourcePlan(
                DecodeStrategy.FULL,
                1,
                fullBytes,
                ResourcePlanReason.WITHIN_VALIDATED_BUDGET,
                header.animated,
            )
        }
        if (policy.allowSampling) {
            var sample = 2
            while (sample <= MAX_SAMPLE_SIZE) {
                val sampledWidth = ceilDivide(header.width, sample)
                val sampledHeight = ceilDivide(header.height, sample)
                val sampledPixels = CheckedImageArithmetic.multiply(sampledWidth.toLong(), sampledHeight.toLong())
                val sampledBytes = CheckedImageArithmetic.decodedBytes(sampledWidth, sampledHeight, bytesPerPixel)
                if (sampledPixels <= policy.maximumPixels && sampledBytes <= policy.maximumDecodedBytes) {
                    return DecodeResourcePlan(
                        DecodeStrategy.SAMPLED,
                        sample,
                        sampledBytes,
                        ResourcePlanReason.SAMPLING_REQUIRED,
                        true,
                    )
                }
                sample *= 2
            }
        }
        if (policy.allowTiling) {
            return DecodeResourcePlan(DecodeStrategy.TILED, 1, null, ResourcePlanReason.TILING_REQUIRED, true)
        }
        return DecodeResourcePlan(DecodeStrategy.REJECT, 1, null, ResourcePlanReason.MEMORY_LIMIT_EXCEEDED, false)
    }

    private fun ceilDivide(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1) / divisor).toInt()

    private companion object {
        const val MAX_SAMPLE_SIZE = 128
    }
}
