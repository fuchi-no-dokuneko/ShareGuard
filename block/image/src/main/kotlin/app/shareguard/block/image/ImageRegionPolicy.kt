package app.shareguard.block.image

import app.shareguard.core.model.ArtifactReference
import app.shareguard.core.model.CanonicalBlockId
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.DecisionId
import app.shareguard.core.model.DependencyId
import app.shareguard.core.model.DependencyOrigin
import app.shareguard.core.model.DependencyType
import app.shareguard.core.model.ImageRegion
import app.shareguard.core.model.ImageRegionId
import app.shareguard.core.model.ImageRegionPolicy
import app.shareguard.core.model.ImageRegionType
import app.shareguard.core.model.NormalizedRect
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SourceDependency
import app.shareguard.core.model.SourceDependencyMap

enum class DetectedRegionClass { TEXT, NON_TEXT, UNKNOWN }

data class RegionDispositionRequest(
    val regionId: ImageRegionId,
    val detectedClass: DetectedRegionClass,
    val semanticType: ImageRegionType,
    val sourceBounds: NormalizedRect,
    val requestedPolicy: ImageRegionPolicy? = null,
    val replacementAsset: ArtifactReference? = null,
    val approvalDecisionId: DecisionId? = null,
)

data class RegionDisposition(
    val region: ImageRegion,
    val reviewRequired: Boolean,
    val approvalDecisionId: DecisionId?,
)

/** Applies an explicit disposition to every region. There is intentionally no retain-source default. */
class ConservativeImageRegionPolicy {
    fun decide(request: RegionDispositionRequest): RegionDisposition {
        val selected = when (request.detectedClass) {
            DetectedRegionClass.TEXT -> request.requestedPolicy ?: ImageRegionPolicy.REBUILD_FROM_STRUCTURED_DATA
            DetectedRegionClass.UNKNOWN -> request.requestedPolicy ?: ImageRegionPolicy.SOLID_REDACT
            DetectedRegionClass.NON_TEXT -> request.requestedPolicy
                ?: throw IllegalArgumentException("Non-text regions require an explicit disposition")
        }
        if (request.detectedClass == DetectedRegionClass.TEXT) {
            require(selected in setOf(
                ImageRegionPolicy.REBUILD_FROM_STRUCTURED_DATA,
                ImageRegionPolicy.SOLID_REDACT,
                ImageRegionPolicy.REMOVE,
            )) { "Text pixels cannot be retained by the text reconstruction path" }
        }
        if (request.detectedClass == DetectedRegionClass.UNKNOWN) {
            require(selected != ImageRegionPolicy.RETAIN_SOURCE_PIXELS) {
                "Unknown regions never default or opt directly into source-pixel retention"
            }
        }
        val retainsPixels = selected == ImageRegionPolicy.RETAIN_SOURCE_PIXELS
        val rebuildsStructuredData = selected == ImageRegionPolicy.REBUILD_FROM_STRUCTURED_DATA
        if (retainsPixels) requireNotNull(request.approvalDecisionId) {
            "Source-pixel retention requires an explicit decision"
        }
        if (selected == ImageRegionPolicy.REPLACE_WITH_PLACEHOLDER) requireNotNull(request.replacementAsset)
        val reviewRequired = request.detectedClass == DetectedRegionClass.UNKNOWN || retainsPixels ||
            (rebuildsStructuredData && request.approvalDecisionId == null) ||
            (request.detectedClass == DetectedRegionClass.TEXT && selected != ImageRegionPolicy.REBUILD_FROM_STRUCTURED_DATA)
        val reason = when (request.detectedClass) {
            DetectedRegionClass.TEXT -> SafeSummary("Text region rebuilt or removed under explicit image policy")
            DetectedRegionClass.NON_TEXT -> SafeSummary("Non-text region handled by explicit image policy")
            DetectedRegionClass.UNKNOWN -> SafeSummary("Unknown region redacted or removed pending review")
        }
        return RegionDisposition(
            region = ImageRegion(
                regionId = request.regionId,
                regionType = request.semanticType,
                sourceBounds = request.sourceBounds,
                canonicalBounds = request.sourceBounds.takeIf {
                    selected in setOf(ImageRegionPolicy.REBUILD_FROM_STRUCTURED_DATA, ImageRegionPolicy.RETAIN_SOURCE_PIXELS)
                },
                policy = selected,
                sourcePixelRetained = retainsPixels,
                replacementAssetId = request.replacementAsset,
                userApproved = retainsPixels || (rebuildsStructuredData && request.approvalDecisionId != null),
                dependencyReason = reason,
            ),
            reviewRequired = reviewRequired,
            approvalDecisionId = request.approvalDecisionId,
        )
    }
}

data class BlockDependencyUse(val blockId: CanonicalBlockId)
data class RegionRetentionUse(val regionId: ImageRegionId, val decisionId: DecisionId)
data class BundledAssetUse(val asset: ArtifactReference, val blockId: CanonicalBlockId? = null)
data class RendererPrimitiveUse(val primitiveSchema: SafeSummary, val blockId: CanonicalBlockId? = null)

data class ImageDependencyDeclaration(
    val canonicalRevision: CanonicalRevision,
    val retainedPixelRegions: List<RegionRetentionUse> = emptyList(),
    val ocrDerivedText: List<BlockDependencyUse> = emptyList(),
    val sourceDerivedLayout: List<BlockDependencyUse> = emptyList(),
    val retainedMetadataBlocks: List<BlockDependencyUse> = emptyList(),
    val bundledAssets: List<BundledAssetUse> = emptyList(),
    val rendererPrimitives: List<RendererPrimitiveUse> = emptyList(),
    val userDecisions: List<DecisionId> = emptyList(),
)

data class ImageDependencyCoverage(
    val dependencyMap: SourceDependencyMap,
    val bundledAssetByDependency: Map<DependencyId, ArtifactReference>,
    val rendererSchemaByDependency: Map<DependencyId, SafeSummary>,
    val scopeLimitation: SafeSummary = SafeSummary(
        "Covers declared canonical lineage; does not claim hidden codec, native-library, or platform dependencies",
    ),
) {
    init {
        require(bundledAssetByDependency.keys.all { id -> dependencyMap.entries.any { it.dependencyId == id } })
        require(rendererSchemaByDependency.keys.all { id -> dependencyMap.entries.any { it.dependencyId == id } })
    }
}

/** Builds exact, typed lineage for every dependency category required by the image reconstruction path. */
class ImageSourceDependencyMapper {
    fun build(declaration: ImageDependencyDeclaration): ImageDependencyCoverage {
        require(declaration.retainedPixelRegions.map { it.regionId }.distinct().size == declaration.retainedPixelRegions.size)
        val entries = mutableListOf<SourceDependency>()
        val assetLinks = linkedMapOf<DependencyId, ArtifactReference>()
        val rendererLinks = linkedMapOf<DependencyId, SafeSummary>()
        var sequence = 0

        fun add(
            type: DependencyType,
            origin: DependencyOrigin,
            blockId: CanonicalBlockId? = null,
            regionId: ImageRegionId? = null,
            decisionId: DecisionId? = null,
            reason: String,
        ): DependencyId {
            val id = DependencyId("imgdep-${declaration.canonicalRevision.value}-${sequence++}")
            entries += SourceDependency(
                dependencyId = id,
                type = type,
                origin = origin,
                canonicalRevision = declaration.canonicalRevision,
                canonicalBlockId = blockId,
                imageRegionId = regionId,
                decisionId = decisionId,
                sourcePixelRetained = type == DependencyType.RETAINED_SOURCE_PIXELS,
                reason = SafeSummary(reason),
            )
            return id
        }

        declaration.retainedPixelRegions.forEach {
            add(
                DependencyType.RETAINED_SOURCE_PIXELS,
                DependencyOrigin.SOURCE,
                regionId = it.regionId,
                decisionId = it.decisionId,
                reason = "Explicitly approved retained source-pixel region",
            )
        }
        declaration.ocrDerivedText.forEach {
            add(DependencyType.OCR_DERIVED_TEXT, DependencyOrigin.SOURCE, it.blockId, reason = "OCR-derived canonical text")
        }
        declaration.sourceDerivedLayout.forEach {
            add(
                DependencyType.SOURCE_DERIVED_LAYOUT,
                DependencyOrigin.SOURCE,
                it.blockId,
                reason = "Source-derived canonical layout",
            )
        }
        declaration.retainedMetadataBlocks.forEach {
            add(
                DependencyType.RETAINED_SOURCE_METADATA,
                DependencyOrigin.SOURCE,
                it.blockId,
                reason = "Explicit retained source metadata",
            )
        }
        declaration.bundledAssets.forEach {
            val id = add(
                DependencyType.BUNDLED_ASSET,
                DependencyOrigin.BUNDLED,
                it.blockId,
                reason = "Bundled reconstruction asset",
            )
            assetLinks[id] = it.asset
        }
        declaration.rendererPrimitives.forEach {
            val id = add(
                DependencyType.RENDERER_GENERATED_PRIMITIVE,
                DependencyOrigin.GENERATED,
                it.blockId,
                reason = "Renderer-generated primitive",
            )
            rendererLinks[id] = it.primitiveSchema
        }
        declaration.userDecisions.distinct().forEach {
            add(
                DependencyType.USER_DECISION,
                DependencyOrigin.USER_DECISION,
                decisionId = it,
                reason = "Explicit user decision affecting image output",
            )
        }
        add(
            DependencyType.CANONICAL_DOCUMENT_REVISION,
            DependencyOrigin.CANONICAL_DOCUMENT,
            reason = "Canonical document revision used for rendering",
        )
        return ImageDependencyCoverage(
            SourceDependencyMap.create(declaration.canonicalRevision, entries),
            assetLinks,
            rendererLinks,
        )
    }
}
