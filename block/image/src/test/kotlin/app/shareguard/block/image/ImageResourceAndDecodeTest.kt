package app.shareguard.block.image

import app.shareguard.core.model.SafeSummary
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageResourceAndDecodeTest {
    private val planner = ImageResourcePlanner()

    @Test
    fun `validated budgets choose full sampled tiled or reject without guessed defaults`() {
        val header = header(4_000, 3_000)
        val full = planner.plan(header, policy(20_000_000, 80_000_000, allowSampling = true))
        assertThat(full.strategy).isEqualTo(DecodeStrategy.FULL)

        val sampled = planner.plan(header, policy(1_000_000, 4_000_000, allowSampling = true))
        assertThat(sampled.strategy).isEqualTo(DecodeStrategy.SAMPLED)
        assertThat(sampled.sampleSize).isEqualTo(4)
        assertThat(sampled.reviewRequired).isTrue()

        val tiled = planner.plan(header, policy(100, 100, allowSampling = false, allowTiling = true))
        assertThat(tiled.strategy).isEqualTo(DecodeStrategy.TILED)

        val rejected = planner.plan(header, policy(100, 100, allowSampling = false))
        assertThat(rejected.strategy).isEqualTo(DecodeStrategy.REJECT)
        assertThat(rejected.reason).isEqualTo(ResourcePlanReason.PIXEL_LIMIT_EXCEEDED)
    }

    @Test
    fun `animated input is rejected or explicitly review bounded`() {
        val animated = header(10, 10).copy(animated = true, frameCount = 2)
        val rejected = planner.plan(animated, policy(1_000, 10_000, animatedPolicy = AnimatedInputPolicy.REJECT))
        assertThat(rejected.reason).isEqualTo(ResourcePlanReason.ANIMATION_REJECTED)
        assertThat(rejected.reviewRequired).isTrue()

        val reviewed = planner.plan(
            animated,
            policy(1_000, 10_000, animatedPolicy = AnimatedInputPolicy.FIRST_FRAME_REVIEW_REQUIRED),
        )
        assertThat(reviewed.approved).isTrue()
        assertThat(reviewed.reviewRequired).isTrue()
    }

    @Test
    fun `orientation materialization uses a new pixel owner and all source storage can be erased`() {
        val source = ArgbPixelImage(2, 3, intArrayOf(1, 2, 3, 4, 5, 6))

        val rotated = ImageOrientationMaterializer.materialize(source, ExifOrientation.ROTATE_90_CLOCKWISE)

        assertThat(rotated.width).isEqualTo(3)
        assertThat(rotated.height).isEqualTo(2)
        assertThat((0 until rotated.height).flatMap { y -> (0 until rotated.width).map { x -> rotated.argbAt(x, y) } })
            .containsExactly(5, 3, 1, 6, 4, 2).inOrder()
        source.close()
        assertThat(source.storageIsZeroizedForTest()).isTrue()
        assertThat(rotated.argbAt(0, 0)).isEqualTo(5)
        rotated.close()
        assertThat(rotated.storageIsZeroizedForTest()).isTrue()
    }

    @Test
    fun `controlled decoder enforces dimensions inventories channels and closes transient source`() = runBlocking {
        val encoded = ByteArrayImageDecodeSource(byteArrayOf(1, 2, 3))
        val backend = object : ImageDecoderBackend {
            override suspend fun decode(source: ImageDecodeSource, plan: DecodeResourcePlan): PixelImage =
                ArgbPixelImage(2, 1, intArrayOf(0xff102030.toInt(), 0x80112233.toInt()))
        }
        val plan = DecodeResourcePlan(DecodeStrategy.FULL, 1, 8, ResourcePlanReason.WITHIN_VALIDATED_BUDGET, false)
        val decoder = ControlledImageDecoder(backend)

        val result = decoder.decode(encoded, header(2, 1, alpha = true), plan, ExifOrientation.NORMAL)

        assertThat(result.sourceBufferRetained).isFalse()
        assertThat(result.channels.observedTransparency).isTrue()
        assertThat(result.channels.alpha).isEqualTo(ChannelRange(128, 255))
        assertThat(encoded.isZeroizedForTest()).isTrue()
        result.close()
    }

    @Test
    fun `decoder dimension disagreement is fatal and still erases source`() {
        val encoded = ByteArrayImageDecodeSource(byteArrayOf(1, 2, 3))
        val backend = object : ImageDecoderBackend {
            override suspend fun decode(source: ImageDecodeSource, plan: DecodeResourcePlan): PixelImage =
                ArgbPixelImage(1, 1, intArrayOf(0))
        }
        val plan = DecodeResourcePlan(DecodeStrategy.FULL, 1, 8, ResourcePlanReason.WITHIN_VALIDATED_BUDGET, false)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ControlledImageDecoder(backend).decode(encoded, header(2, 1), plan, ExifOrientation.NORMAL) }
        }
        assertThat(encoded.isZeroizedForTest()).isTrue()
    }

    private fun header(width: Int, height: Int, alpha: Boolean = false): ImageHeaderModel = ImageHeaderModel(
        ImageFormat.PNG,
        width,
        height,
        8,
        if (alpha) 4 else 3,
        alpha,
        false,
        1,
        ContainerStructureInventory(emptyList(), true),
    )

    private fun policy(
        pixels: Long,
        bytes: Long,
        allowSampling: Boolean = false,
        allowTiling: Boolean = false,
        animatedPolicy: AnimatedInputPolicy = AnimatedInputPolicy.REJECT,
    ): ImageResourcePolicy = ImageResourcePolicy(
        10_000,
        10_000,
        pixels,
        bytes,
        4,
        allowSampling,
        allowTiling,
        animatedPolicy,
        SafeSummary("validated-device-corpus-v1"),
    )
}
