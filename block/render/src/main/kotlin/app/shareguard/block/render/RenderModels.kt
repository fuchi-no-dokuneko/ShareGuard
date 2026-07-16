package app.shareguard.block.render

import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.annotation.ColorInt
import app.shareguard.core.model.ArtifactId
import app.shareguard.core.model.ArtifactReference
import app.shareguard.core.model.ArtifactRevision
import app.shareguard.core.model.CanonicalDocument
import app.shareguard.core.model.DecisionId
import app.shareguard.core.model.ImageRegionId
import app.shareguard.core.model.MimeType
import app.shareguard.core.model.NormalizedRect
import app.shareguard.core.model.PixelSize
import app.shareguard.core.model.ScriptCode
import app.shareguard.core.model.SourceDependencyMap

enum class RenderFailureCode {
    INVALID_RESOURCE_PLAN,
    EMPTY_DOCUMENT,
    CANVAS_ALLOCATION_FAILED,
    OUTPUT_HEIGHT_EXCEEDED,
    MISSING_BUNDLED_FONT,
    MISSING_BUNDLED_GLYPH,
    INVALID_CANONICAL_CONTROL,
    TEXT_SHAPING_FAILED,
    UNSUPPORTED_CANONICAL_BLOCK,
    REGION_POLICY_UNRESOLVED,
    SOURCE_REGION_NOT_APPROVED,
    SOURCE_REGION_DEPENDENCY_MISSING,
    SOURCE_REGION_PIXELS_MISSING,
    SOURCE_REGION_TRANSFORM_UNDECLARED,
    SOURCE_REGION_BOUNDS_MISSING,
    UNEXPECTED_ALPHA,
    SERIALIZATION_FAILED,
    CONTAINER_REOPEN_FAILED,
    UNEXPECTED_PNG_CHUNK,
    UNEXPECTED_PNG_PROFILE,
    DERIVATIVE_WARNING_NOT_ACKNOWLEDGED,
    DERIVATIVE_DEPENDENCY_MISSING,
    DERIVATIVE_POLICY_NOT_BENCHMARKED,
}

class RenderException(
    val code: RenderFailureCode,
) : IllegalStateException("Render stopped: ${code.name}")

data class RenderResourcePlan(
    val outputWidthPx: Int,
    val maximumOutputHeightPx: Int,
    val maximumPixelCount: Long,
    val horizontalPaddingPx: Int,
    val verticalPaddingPx: Int,
    val blockSpacingPx: Int,
    val bodyTextSizePx: Float,
    val maximumBlockCount: Int = 2_048,
    val maximumTextScalars: Int = 1_000_000,
    val maximumRegionCount: Int = 512,
) {
    init {
        require(outputWidthPx > 0)
        require(maximumOutputHeightPx > 0)
        require(maximumPixelCount > 0)
        require(horizontalPaddingPx >= 0 && verticalPaddingPx >= 0 && blockSpacingPx >= 0)
        require(bodyTextSizePx.isFinite() && bodyTextSizePx > 0)
        require(maximumBlockCount > 0 && maximumTextScalars > 0 && maximumRegionCount > 0)
        require(outputWidthPx.toLong() <= maximumPixelCount)
        require(outputWidthPx.toLong() - horizontalPaddingPx.toLong() * 2L > 0L)
    }

    fun requireHeight(heightPx: Int) {
        if (heightPx <= 0 || heightPx > maximumOutputHeightPx) {
            throw RenderException(RenderFailureCode.OUTPUT_HEIGHT_EXCEEDED)
        }
        val pixels = try {
            Math.multiplyExact(outputWidthPx.toLong(), heightPx.toLong())
        } catch (_: ArithmeticException) {
            throw RenderException(RenderFailureCode.INVALID_RESOURCE_PLAN)
        }
        if (pixels > maximumPixelCount) throw RenderException(RenderFailureCode.OUTPUT_HEIGHT_EXCEEDED)
    }
}

enum class CanonicalRenderTheme { DOCUMENT, CARD, MESSAGE }

data class CanonicalRenderPolicy(
    val theme: CanonicalRenderTheme,
    val resourcePlan: RenderResourcePlan,
    @param:ColorInt val backgroundColor: Int,
    @param:ColorInt val foregroundColor: Int,
    @param:ColorInt val secondarySurfaceColor: Int,
    @param:ColorInt val redactionColor: Int,
    val outputMimeType: MimeType = MimeType("image/png"),
) {
    init {
        require(outputMimeType == MimeType("image/png")) {
            "High-assurance renderer supports strict PNG only"
        }
        require(
            listOf(backgroundColor, foregroundColor, secondarySurfaceColor, redactionColor)
                .all { color -> (color ushr 24) == 0xff },
        ) { "Canonical render colours must be opaque" }
    }
}

data class BundledFontFace(
    val familyId: String,
    val version: String,
    val scripts: Set<ScriptCode>,
    val typeface: Typeface,
) {
    init {
        require(familyId.matches(Regex("[a-z0-9][a-z0-9._-]{1,63}")))
        require(version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")))
        require(scripts.isNotEmpty())
    }

    override fun toString(): String = "BundledFontFace($familyId@$version,scripts=${scripts.size})"
}

fun interface BundledFontRegistry {
    fun faces(): List<BundledFontFace>
}

data class ResolvedFontRun(
    val text: String,
    val face: BundledFontFace,
)

sealed interface ApprovedStructuredRegionData {
    val regionId: ImageRegionId
    val decisionId: DecisionId
}

/** Semantic bar data only. No source coordinates, colours, or pixels are accepted. */
data class ApprovedBarChartData(
    override val regionId: ImageRegionId,
    override val decisionId: DecisionId,
    val normalizedValues: List<Float>,
) : ApprovedStructuredRegionData {
    init {
        require(normalizedValues.isNotEmpty() && normalizedValues.size <= 64)
        require(normalizedValues.all { it.isFinite() && it in 0f..1f })
    }
}

/** User-approved logical module grid, redrawn with canonical black/white primitives. */
class ApprovedModuleGridData(
    override val regionId: ImageRegionId,
    override val decisionId: DecisionId,
    val sideModules: Int,
    modules: List<Boolean>,
) : ApprovedStructuredRegionData {
    private val approvedModules = modules.toList()

    init {
        require(sideModules in 9..177)
        require(approvedModules.size == sideModules * sideModules)
    }

    fun isDark(row: Int, column: Int): Boolean {
        require(row in 0 until sideModules && column in 0 until sideModules)
        return approvedModules[row * sideModules + column]
    }

    override fun toString(): String =
        "ApprovedModuleGridData(region=${regionId.value},side=$sideModules,modules=redacted)"
}

fun interface ApprovedStructuredRegionProvider {
    fun load(regionId: ImageRegionId): ApprovedStructuredRegionData?
}

data class ApprovedSourceRegionPixels(
    val regionId: ImageRegionId,
    val decisionId: DecisionId,
    val sourceBounds: NormalizedRect,
    val declaredDerivativePolicyId: String,
    val transformedUnderDeclaredDerivativePolicy: Boolean,
    val bitmap: Bitmap,
) {
    init {
        require(declaredDerivativePolicyId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")))
        require(bitmap.width > 0 && bitmap.height > 0 && !bitmap.isRecycled)
    }
}

fun interface ApprovedSourceRegionProvider {
    fun load(regionId: ImageRegionId): ApprovedSourceRegionPixels?
}

enum class RenderOperationCode {
    FRESH_CANVAS_ALLOCATED,
    BUNDLED_FONT_RESOLVED,
    TEXT_SHAPED,
    GENERIC_PRIMITIVE_RENDERED,
    STRUCTURED_REGION_RENDERED,
    PLACEHOLDER_RENDERED,
    REDACTION_RENDERED,
    APPROVED_SOURCE_REGION_IMPORTED,
    ALPHA_FLATTENED,
    CANONICAL_COLOUR_APPLIED,
    PNG_SERIALIZED,
    PNG_REOPENED,
    DERIVATIVE_RESAMPLED,
    DERIVATIVE_CHANNELS_CANONICALIZED,
    DERIVATIVE_QUANTIZED,
    EPHEMERAL_PERTURBATION_APPLIED,
    DERIVATIVE_WARNING_ACKNOWLEDGED,
}

data class RenderOperation(
    val code: RenderOperationCode,
    val canonicalBlockId: String? = null,
    val regionId: String? = null,
)

class EncodedRenderedImage internal constructor(
    bytes: ByteArray,
    val pixelSize: PixelSize,
    val mimeType: MimeType,
    val sourceDependencyMap: SourceDependencyMap,
    val operations: List<RenderOperation>,
) {
    private val encodedBytes = bytes.copyOf()

    fun copyBytes(): ByteArray = encodedBytes.copyOf()
    val byteCount: Long get() = encodedBytes.size.toLong()

    override fun toString(): String =
        "EncodedRenderedImage(bytes=redacted,pixels=${pixelSize.width.value}x${pixelSize.height.value})"
}

data class RebuiltArtifactIdentity(
    val artifactId: ArtifactId,
    val reference: ArtifactReference,
    val artifactRevision: ArtifactRevision,
)

data class CanonicalRenderRequest(
    val document: CanonicalDocument,
    val policy: CanonicalRenderPolicy,
    val sourceRegionProvider: ApprovedSourceRegionProvider? = null,
    val structuredRegionProvider: ApprovedStructuredRegionProvider? = null,
)
