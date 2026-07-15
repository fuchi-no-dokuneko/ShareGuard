package app.shareguard.block.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import app.shareguard.core.model.CanonicalBlock
import app.shareguard.core.model.CanonicalBlockId
import app.shareguard.core.model.CanonicalDocument
import app.shareguard.core.model.CodeBlock
import app.shareguard.core.model.DependencyId
import app.shareguard.core.model.DependencyOrigin
import app.shareguard.core.model.DependencyType
import app.shareguard.core.model.GenericUiBlock
import app.shareguard.core.model.HeadingBlock
import app.shareguard.core.model.ImagePlaceholderBlock
import app.shareguard.core.model.ImageRegion
import app.shareguard.core.model.ImageRegionId
import app.shareguard.core.model.ImageRegionPolicy
import app.shareguard.core.model.LinkBlock
import app.shareguard.core.model.ListBlock
import app.shareguard.core.model.MessageBlock
import app.shareguard.core.model.MessageRole
import app.shareguard.core.model.ParagraphBlock
import app.shareguard.core.model.QuoteBlock
import app.shareguard.core.model.RedactionBlock
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.ScriptCode
import app.shareguard.core.model.SourceDependency
import app.shareguard.core.model.SourceDependencyMap
import app.shareguard.core.model.TableLikeBlock
import app.shareguard.core.model.TextRun
import app.shareguard.core.model.UnknownRegionBlock
import app.shareguard.core.model.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.min

class CanonicalImageRenderer private constructor(
    fontRegistry: BundledFontRegistry,
    private val pngSerializer: StrictPngSerializer,
    private val dispatcher: CoroutineDispatcher,
    glyphCoverageChecker: GlyphCoverageChecker,
) {
    constructor(
        fontRegistry: BundledFontRegistry,
        pngSerializer: StrictPngSerializer = StrictPngSerializer(),
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(fontRegistry, pngSerializer, dispatcher, AndroidGlyphCoverage)

    internal constructor(
        fontRegistry: BundledFontRegistry,
        dispatcher: CoroutineDispatcher,
        glyphCoverageChecker: GlyphCoverageChecker,
    ) : this(fontRegistry, StrictPngSerializer(), dispatcher, glyphCoverageChecker)

    private val textEngine = CanonicalTextLayoutEngine(BundledFontResolver(fontRegistry, glyphCoverageChecker))

    suspend fun render(request: CanonicalRenderRequest): EncodedRenderedImage = withContext(dispatcher) {
        coroutineContext.ensureActive()
        validateDocumentLimits(request)
        val plan = request.policy.resourcePlan
        val contentWidth = plan.outputWidthPx - plan.horizontalPaddingPx * 2
        val regions = request.document.imageRegions.associateBy { it.regionId }
        requireRegionCoverage(request.document)
        val blocksById = request.document.rootBlocks.associateBy { it.id }
        val orderedBlocks = request.document.readingOrder.blockIds.map { id ->
            blocksById[id] ?: throw RenderException(RenderFailureCode.UNSUPPORTED_CANONICAL_BLOCK)
        }
        val items = mutableListOf<PreparedItem>()
        var hasSemanticContent = false
        for (block in orderedBlocks) {
            coroutineContext.ensureActive()
            val prepared = prepareBlock(block, contentWidth, regions, request)
            if (prepared != null) {
                items += prepared
                hasSemanticContent = hasSemanticContent || prepared.hasSemanticContent
            }
        }
        if (items.isEmpty() || !hasSemanticContent) {
            throw RenderException(RenderFailureCode.EMPTY_DOCUMENT)
        }
        val height = measuredHeight(items, plan)
        plan.requireHeight(height)
        val output = allocateFreshBitmap(plan.outputWidthPx, height)
        val operations = mutableListOf(
            RenderOperation(RenderOperationCode.FRESH_CANVAS_ALLOCATED),
        )
        val canvas = Canvas(output)
        try {
            canvas.drawColor(request.policy.backgroundColor)
            var top = plan.verticalPaddingPx
            items.forEachIndexed { index, item ->
                coroutineContext.ensureActive()
                item.draw(
                    canvas = canvas,
                    left = plan.horizontalPaddingPx,
                    top = top,
                    width = contentWidth,
                    policy = request.policy,
                    operations = operations,
                )
                top = Math.addExact(top, item.height)
                if (index != items.lastIndex) top = Math.addExact(top, plan.blockSpacingPx)
            }
            forceOpaqueAlpha(output)
            operations += RenderOperation(RenderOperationCode.ALPHA_FLATTENED)
            operations += RenderOperation(RenderOperationCode.CANONICAL_COLOUR_APPLIED)
            val dependencyMap = buildOutputDependencyMap(request.document, items)
            val (bytes, evidence) = pngSerializer.serializeOpaque(output)
            operations += RenderOperation(RenderOperationCode.PNG_SERIALIZED)
            operations += RenderOperation(RenderOperationCode.PNG_REOPENED)
            EncodedRenderedImage(
                bytes = bytes,
                pixelSize = evidence.pixelSize,
                mimeType = request.policy.outputMimeType,
                sourceDependencyMap = dependencyMap,
                operations = operations.toList(),
            )
        } finally {
            output.eraseColor(0)
            output.recycle()
        }
    }

    private fun validateDocumentLimits(request: CanonicalRenderRequest) {
        val plan = request.policy.resourcePlan
        val document = request.document
        if (document.rootBlocks.size > plan.maximumBlockCount ||
            document.imageRegions.size > plan.maximumRegionCount
        ) {
            throw RenderException(RenderFailureCode.INVALID_RESOURCE_PLAN)
        }
        var scalarCount = 0L
        document.rootBlocks.forEach { block ->
            block.textFragments().forEach { text ->
                scalarCount += text.codePointCount(0, text.length).toLong()
                if (scalarCount > plan.maximumTextScalars) {
                    throw RenderException(RenderFailureCode.INVALID_RESOURCE_PLAN)
                }
            }
        }
    }

    private fun requireRegionCoverage(document: CanonicalDocument) {
        val referenced = document.rootBlocks.mapNotNull { block ->
            when (block) {
                is ImagePlaceholderBlock -> block.regionId
                is RedactionBlock -> block.regionId
                is UnknownRegionBlock -> block.regionId
                else -> null
            }
        }.toSet()
        val unexplained = document.imageRegions.any { region ->
            region.policy !in setOf(ImageRegionPolicy.REMOVE, ImageRegionPolicy.CROP) &&
                region.regionId !in referenced
        }
        if (unexplained) throw RenderException(RenderFailureCode.REGION_POLICY_UNRESOLVED)
    }

    private fun prepareBlock(
        block: CanonicalBlock,
        contentWidth: Int,
        regions: Map<ImageRegionId, ImageRegion>,
        request: CanonicalRenderRequest,
    ): PreparedItem? = when (block) {
        is ParagraphBlock -> textItem(block.id, block.runs.segments(), contentWidth, request, Decoration.NONE)
        is HeadingBlock -> textItem(
            block.id,
            block.runs.segments(),
            contentWidth,
            request,
            Decoration.HEADING,
            textScale = when (block.level) {
                1 -> 1.65f
                2 -> 1.45f
                3 -> 1.3f
                else -> 1.15f
            },
        )
        is ListBlock -> {
            val segments = mutableListOf<CanonicalTextSegment>()
            block.items.forEachIndexed { index, item ->
                if (index > 0) segments += CanonicalTextSegment("\n", setOf(ScriptCode.COMMON))
                val marker = if (block.ordered) "${index + 1}. " else "• "
                segments += CanonicalTextSegment(marker, setOf(ScriptCode.COMMON))
                segments += item.runs.segments()
            }
            textItem(block.id, segments, contentWidth, request, Decoration.LIST)
        }
        is QuoteBlock -> textItem(block.id, block.runs.segments(), contentWidth, request, Decoration.QUOTE)
        is MessageBlock -> textItem(
            block.id,
            block.runs.segments(),
            (contentWidth * MESSAGE_WIDTH_FRACTION).toInt(),
            request,
            when (block.role) {
                MessageRole.AUTHOR -> Decoration.MESSAGE_END
                else -> Decoration.MESSAGE_START
            },
        )
        is CodeBlock -> textItem(
            block.id,
            listOf(CanonicalTextSegment(block.code)),
            contentWidth,
            request,
            Decoration.CODE,
        )
        is TableLikeBlock -> {
            val segments = mutableListOf<CanonicalTextSegment>()
            block.rows.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) segments += CanonicalTextSegment("\n", setOf(ScriptCode.COMMON))
                row.cells.forEachIndexed { cellIndex, cell ->
                    if (cellIndex > 0) segments += CanonicalTextSegment("  |  ", setOf(ScriptCode.COMMON))
                    segments += cell.runs.segments()
                }
            }
            textItem(block.id, segments, contentWidth, request, Decoration.TABLE)
        }
        is LinkBlock -> {
            val segments = if (block.displayRuns.isEmpty()) {
                val token = request.document.urlTokens.first { it.tokenId == block.urlTokenId }
                listOf(CanonicalTextSegment(token.finalText))
            } else {
                block.displayRuns.segments()
            }
            textItem(block.id, segments, contentWidth, request, Decoration.LINK)
        }
        is GenericUiBlock -> textItem(
            block.id,
            block.runs.segments(),
            contentWidth,
            request,
            Decoration.GENERIC_UI,
        )
        is ImagePlaceholderBlock -> {
            val region = regions[block.regionId]
                ?: throw RenderException(RenderFailureCode.REGION_POLICY_UNRESOLVED)
            if (region.policy != ImageRegionPolicy.REPLACE_WITH_PLACEHOLDER) {
                throw RenderException(RenderFailureCode.REGION_POLICY_UNRESOLVED)
            }
            prepareRegionItem(block.id, region, block.label, contentWidth, request)
        }
        is RedactionBlock -> {
            val region = regions[block.regionId]
                ?: throw RenderException(RenderFailureCode.REGION_POLICY_UNRESOLVED)
            if (region.policy != ImageRegionPolicy.SOLID_REDACT) {
                throw RenderException(RenderFailureCode.REGION_POLICY_UNRESOLVED)
            }
            prepareRegionItem(block.id, region, block.label, contentWidth, request)
        }
        is UnknownRegionBlock -> {
            val region = regions[block.regionId]
                ?: throw RenderException(RenderFailureCode.REGION_POLICY_UNRESOLVED)
            prepareRegionItem(block.id, region, null, contentWidth, request)
        }
    }

    private fun textItem(
        blockId: CanonicalBlockId,
        segments: List<CanonicalTextSegment>,
        width: Int,
        request: CanonicalRenderRequest,
        decoration: Decoration,
        textScale: Float = 1f,
    ): PreparedTextItem {
        val inset = if (decoration.hasSurface) SURFACE_PADDING * 2 else 0
        val textWidth = width - inset - if (decoration == Decoration.QUOTE) QUOTE_BAR_WIDTH else 0
        val layout = textEngine.prepare(
            segments = segments,
            widthPx = textWidth,
            textSizePx = request.policy.resourcePlan.bodyTextSizePx * textScale,
            foregroundColor = request.policy.foregroundColor,
            alignment = Layout.Alignment.ALIGN_NORMAL,
        )
        val text = segments.joinToString(separator = "") { it.text }
        return PreparedTextItem(
            blockId = blockId,
            layout = layout,
            height = layout.height + if (decoration.hasSurface) SURFACE_PADDING * 2 else 0,
            decoration = decoration,
            hasSemanticContent = text.isNotEmpty(),
        )
    }

    private fun prepareRegionItem(
        blockId: CanonicalBlockId,
        region: ImageRegion,
        canonicalLabel: String?,
        contentWidth: Int,
        request: CanonicalRenderRequest,
    ): PreparedRegionItem? {
        val height = min(REGION_MAX_HEIGHT, maxOf(REGION_MIN_HEIGHT, ceil(contentWidth * 0.56f).toInt()))
        val label = when (region.policy) {
            ImageRegionPolicy.REPLACE_WITH_PLACEHOLDER -> canonicalLabel ?: "Image region"
            ImageRegionPolicy.SOLID_REDACT -> canonicalLabel ?: "Redacted region"
            else -> null
        }
        val labelLayout = label?.let {
            textEngine.prepare(
                segments = listOf(CanonicalTextSegment(it)),
                widthPx = contentWidth - SURFACE_PADDING * 2,
                textSizePx = request.policy.resourcePlan.bodyTextSizePx,
                foregroundColor = request.policy.foregroundColor,
                alignment = Layout.Alignment.ALIGN_CENTER,
            )
        }
        val payload: RegionPayload = when (region.policy) {
            ImageRegionPolicy.REPLACE_WITH_PLACEHOLDER -> RegionPayload.Placeholder
            ImageRegionPolicy.SOLID_REDACT -> RegionPayload.Redaction
            ImageRegionPolicy.REBUILD_FROM_STRUCTURED_DATA -> {
                if (!region.userApproved || region.sourcePixelRetained) {
                    throw RenderException(RenderFailureCode.SOURCE_REGION_NOT_APPROVED)
                }
                val data = request.structuredRegionProvider?.load(region.regionId)
                    ?: throw RenderException(RenderFailureCode.REGION_POLICY_UNRESOLVED)
                if (data.regionId != region.regionId || !approvedDecisionAndDependency(
                        request.document,
                        region.regionId,
                        data.decisionId,
                        requireRetainedPixels = false,
                    )
                ) {
                    throw RenderException(RenderFailureCode.SOURCE_REGION_DEPENDENCY_MISSING)
                }
                RegionPayload.Structured(data)
            }
            ImageRegionPolicy.RETAIN_SOURCE_PIXELS -> {
                if (!region.userApproved || !region.sourcePixelRetained) {
                    throw RenderException(RenderFailureCode.SOURCE_REGION_NOT_APPROVED)
                }
                val canonicalBounds = region.canonicalBounds
                    ?: throw RenderException(RenderFailureCode.SOURCE_REGION_BOUNDS_MISSING)
                if (region.sourceBounds.right <= region.sourceBounds.left ||
                    region.sourceBounds.bottom <= region.sourceBounds.top ||
                    canonicalBounds.right <= canonicalBounds.left ||
                    canonicalBounds.bottom <= canonicalBounds.top
                ) {
                    throw RenderException(RenderFailureCode.SOURCE_REGION_BOUNDS_MISSING)
                }
                val pixels = request.sourceRegionProvider?.load(region.regionId)
                    ?: throw RenderException(RenderFailureCode.SOURCE_REGION_PIXELS_MISSING)
                if (pixels.regionId != region.regionId || pixels.sourceBounds != region.sourceBounds) {
                    throw RenderException(RenderFailureCode.SOURCE_REGION_BOUNDS_MISSING)
                }
                if (!pixels.transformedUnderDeclaredDerivativePolicy) {
                    throw RenderException(RenderFailureCode.SOURCE_REGION_TRANSFORM_UNDECLARED)
                }
                if (!approvedDecisionAndDependency(
                        request.document,
                        region.regionId,
                        pixels.decisionId,
                        requireRetainedPixels = true,
                    )
                ) {
                    throw RenderException(RenderFailureCode.SOURCE_REGION_DEPENDENCY_MISSING)
                }
                RegionPayload.SourcePixels(pixels)
            }
            ImageRegionPolicy.REMOVE, ImageRegionPolicy.CROP -> return null
        }
        return PreparedRegionItem(
            blockId = blockId,
            regionId = region.regionId,
            height = height,
            labelLayout = labelLayout,
            payload = payload,
        )
    }

    private fun approvedDecisionAndDependency(
        document: CanonicalDocument,
        regionId: ImageRegionId,
        decisionId: app.shareguard.core.model.DecisionId,
        requireRetainedPixels: Boolean,
    ): Boolean {
        val decisionApproved = document.userDecisions.any { decision ->
            decision.decisionId == decisionId &&
                decision.status == app.shareguard.core.model.DecisionStatus.APPROVED &&
                decision.canonicalRevision == document.revision
        }
        val dependencyLinked = document.sourceDependencyMap.entries.any { dependency ->
            dependency.imageRegionId == regionId &&
                dependency.decisionId == decisionId &&
                if (requireRetainedPixels) {
                    dependency.type == DependencyType.RETAINED_SOURCE_PIXELS && dependency.sourcePixelRetained
                } else {
                    dependency.type == DependencyType.USER_DECISION && !dependency.sourcePixelRetained
                }
        }
        return decisionApproved && dependencyLinked
    }

    private fun measuredHeight(items: List<PreparedItem>, plan: RenderResourcePlan): Int = try {
        var result = Math.multiplyExact(plan.verticalPaddingPx, 2)
        items.forEachIndexed { index, item ->
            result = Math.addExact(result, item.height)
            if (index != items.lastIndex) result = Math.addExact(result, plan.blockSpacingPx)
        }
        result
    } catch (_: ArithmeticException) {
        throw RenderException(RenderFailureCode.OUTPUT_HEIGHT_EXCEEDED)
    }

    private fun allocateFreshBitmap(width: Int, height: Int): Bitmap = try {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.setHasAlpha(false) }
    } catch (_: IllegalArgumentException) {
        throw RenderException(RenderFailureCode.CANVAS_ALLOCATION_FAILED)
    } catch (_: OutOfMemoryError) {
        throw RenderException(RenderFailureCode.CANVAS_ALLOCATION_FAILED)
    }

    private fun forceOpaqueAlpha(bitmap: Bitmap) {
        val row = IntArray(bitmap.width)
        try {
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (index in row.indices) row[index] = row[index] or 0xff000000.toInt()
                bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            }
            bitmap.setHasAlpha(false)
        } finally {
            row.fill(0)
        }
    }

    private fun buildOutputDependencyMap(
        document: CanonicalDocument,
        items: List<PreparedItem>,
    ): SourceDependencyMap {
        val entries = document.sourceDependencyMap.entries.toMutableList()
        val usedIds = entries.mapTo(mutableSetOf()) { it.dependencyId }
        var sequence = 1
        fun nextId(): DependencyId {
            while (true) {
                val candidate = DependencyId("ren-${document.revision.value}-${sequence++}")
                if (usedIds.add(candidate)) return candidate
            }
        }
        fun add(
            type: DependencyType,
            origin: DependencyOrigin,
            blockId: CanonicalBlockId?,
            regionId: ImageRegionId? = null,
            reason: String,
        ) {
            entries += SourceDependency(
                dependencyId = nextId(),
                type = type,
                origin = origin,
                canonicalRevision = document.revision,
                canonicalBlockId = blockId,
                imageRegionId = regionId,
                reason = SafeSummary(reason),
            )
        }
        add(
            DependencyType.CANONICAL_DOCUMENT_REVISION,
            DependencyOrigin.CANONICAL_DOCUMENT,
            null,
            reason = "Approved Canonical Document revision",
        )
        items.forEach { item ->
            item.fontFamilies.sorted().forEach { family ->
                add(
                    DependencyType.BUNDLED_ASSET,
                    DependencyOrigin.BUNDLED,
                    item.blockId,
                    reason = "Bundled font $family",
                )
            }
            add(
                DependencyType.RENDERER_GENERATED_PRIMITIVE,
                DependencyOrigin.GENERATED,
                item.blockId,
                item.regionId,
                reason = "Fresh canonical renderer primitives",
            )
        }
        return SourceDependencyMap(
            canonicalRevision = document.revision,
            entries = entries.toImmutableList(),
            scope = SafeSummary("App-defined dependencies; platform and library internals are not enumerated"),
        )
    }

    private sealed interface PreparedItem {
        val blockId: CanonicalBlockId
        val regionId: ImageRegionId? get() = null
        val height: Int
        val fontFamilies: Set<String>
        val hasSemanticContent: Boolean

        fun draw(
            canvas: Canvas,
            left: Int,
            top: Int,
            width: Int,
            policy: CanonicalRenderPolicy,
            operations: MutableList<RenderOperation>,
        )
    }

    private data class PreparedTextItem(
        override val blockId: CanonicalBlockId,
        val layout: PreparedTextLayout,
        override val height: Int,
        val decoration: Decoration,
        override val hasSemanticContent: Boolean,
    ) : PreparedItem {
        override val fontFamilies: Set<String> get() = layout.fontFamilies

        override fun draw(
            canvas: Canvas,
            left: Int,
            top: Int,
            width: Int,
            policy: CanonicalRenderPolicy,
            operations: MutableList<RenderOperation>,
        ) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val surfaceLeft = when (decoration) {
                Decoration.MESSAGE_END -> left + (width * (1f - MESSAGE_WIDTH_FRACTION)).toInt()
                else -> left
            }
            val surfaceWidth = when (decoration) {
                Decoration.MESSAGE_START, Decoration.MESSAGE_END -> (width * MESSAGE_WIDTH_FRACTION).toInt()
                else -> width
            }
            if (decoration.hasSurface) {
                paint.color = policy.secondarySurfaceColor
                canvas.drawRect(
                    surfaceLeft.toFloat(),
                    top.toFloat(),
                    (surfaceLeft + surfaceWidth).toFloat(),
                    (top + height).toFloat(),
                    paint,
                )
            }
            if (decoration == Decoration.QUOTE) {
                paint.color = policy.foregroundColor
                canvas.drawRect(
                    left.toFloat(),
                    top.toFloat(),
                    (left + QUOTE_BAR_WIDTH / 2).toFloat(),
                    (top + height).toFloat(),
                    paint,
                )
            }
            val textLeft = surfaceLeft +
                (if (decoration.hasSurface) SURFACE_PADDING else 0) +
                (if (decoration == Decoration.QUOTE) QUOTE_BAR_WIDTH else 0)
            val textTop = top + if (decoration.hasSurface) SURFACE_PADDING else 0
            layout.draw(canvas, textLeft.toFloat(), textTop.toFloat())
            operations += RenderOperation(RenderOperationCode.BUNDLED_FONT_RESOLVED, blockId.value)
            operations += RenderOperation(RenderOperationCode.TEXT_SHAPED, blockId.value)
            operations += RenderOperation(RenderOperationCode.GENERIC_PRIMITIVE_RENDERED, blockId.value)
        }
    }

    private data class PreparedRegionItem(
        override val blockId: CanonicalBlockId,
        override val regionId: ImageRegionId,
        override val height: Int,
        val labelLayout: PreparedTextLayout?,
        val payload: RegionPayload,
    ) : PreparedItem {
        override val fontFamilies: Set<String> get() = labelLayout?.fontFamilies.orEmpty()
        override val hasSemanticContent: Boolean = true

        override fun draw(
            canvas: Canvas,
            left: Int,
            top: Int,
            width: Int,
            policy: CanonicalRenderPolicy,
            operations: MutableList<RenderOperation>,
        ) {
            val destination = RectF(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            when (val selected = payload) {
                RegionPayload.Placeholder -> {
                    paint.color = policy.secondarySurfaceColor
                    canvas.drawRect(destination, paint)
                    paint.color = policy.foregroundColor
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2f
                    canvas.drawRect(destination, paint)
                    canvas.drawLine(destination.left, destination.top, destination.right, destination.bottom, paint)
                    canvas.drawLine(destination.right, destination.top, destination.left, destination.bottom, paint)
                    operations += RenderOperation(RenderOperationCode.PLACEHOLDER_RENDERED, blockId.value, regionId.value)
                }
                RegionPayload.Redaction -> {
                    paint.color = policy.redactionColor
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(destination, paint)
                    operations += RenderOperation(RenderOperationCode.REDACTION_RENDERED, blockId.value, regionId.value)
                }
                is RegionPayload.Structured -> {
                    paint.style = Paint.Style.FILL
                    when (val data = selected.data) {
                        is ApprovedBarChartData -> drawBarChart(canvas, destination, paint, policy, data)
                        is ApprovedModuleGridData -> drawModuleGrid(canvas, destination, paint, policy, data)
                    }
                    operations += RenderOperation(
                        RenderOperationCode.STRUCTURED_REGION_RENDERED,
                        blockId.value,
                        regionId.value,
                    )
                }
                is RegionPayload.SourcePixels -> {
                    if (selected.pixels.bitmap.isRecycled) {
                        throw RenderException(RenderFailureCode.SOURCE_REGION_PIXELS_MISSING)
                    }
                    paint.isFilterBitmap = true
                    canvas.drawBitmap(selected.pixels.bitmap, null, destination, paint)
                    operations += RenderOperation(
                        RenderOperationCode.APPROVED_SOURCE_REGION_IMPORTED,
                        blockId.value,
                        regionId.value,
                    )
                }
            }
            labelLayout?.let { layout ->
                val labelTop = top + (height - layout.height) / 2
                layout.draw(canvas, (left + SURFACE_PADDING).toFloat(), labelTop.toFloat())
                operations += RenderOperation(RenderOperationCode.BUNDLED_FONT_RESOLVED, blockId.value)
                operations += RenderOperation(RenderOperationCode.TEXT_SHAPED, blockId.value)
            }
        }

        private fun drawBarChart(
            canvas: Canvas,
            destination: RectF,
            paint: Paint,
            policy: CanonicalRenderPolicy,
            data: ApprovedBarChartData,
        ) {
            paint.color = policy.secondarySurfaceColor
            canvas.drawRect(destination, paint)
            val gap = maxOf(1f, destination.width() / (data.normalizedValues.size * 8f))
            val barWidth = (destination.width() - gap * (data.normalizedValues.size + 1)) /
                data.normalizedValues.size
            data.normalizedValues.forEachIndexed { index, value ->
                paint.color = policy.foregroundColor
                val barLeft = destination.left + gap + index * (barWidth + gap)
                val barTop = destination.bottom - destination.height() * value
                canvas.drawRect(barLeft, barTop, barLeft + barWidth, destination.bottom, paint)
            }
        }

        private fun drawModuleGrid(
            canvas: Canvas,
            destination: RectF,
            paint: Paint,
            policy: CanonicalRenderPolicy,
            data: ApprovedModuleGridData,
        ) {
            paint.color = policy.backgroundColor
            canvas.drawRect(destination, paint)
            val side = min(destination.width(), destination.height())
            val cell = side / (data.sideModules + MODULE_QUIET_ZONE * 2)
            val gridSide = cell * data.sideModules
            val startX = destination.centerX() - gridSide / 2f
            val startY = destination.centerY() - gridSide / 2f
            paint.color = policy.foregroundColor
            for (row in 0 until data.sideModules) {
                for (column in 0 until data.sideModules) {
                    if (data.isDark(row, column)) {
                        canvas.drawRect(
                            startX + column * cell,
                            startY + row * cell,
                            startX + (column + 1) * cell,
                            startY + (row + 1) * cell,
                            paint,
                        )
                    }
                }
            }
        }
    }

    private sealed interface RegionPayload {
        data object Placeholder : RegionPayload
        data object Redaction : RegionPayload
        data class Structured(val data: ApprovedStructuredRegionData) : RegionPayload
        data class SourcePixels(val pixels: ApprovedSourceRegionPixels) : RegionPayload
    }

    private enum class Decoration(val hasSurface: Boolean) {
        NONE(false),
        HEADING(false),
        LIST(false),
        QUOTE(false),
        MESSAGE_START(true),
        MESSAGE_END(true),
        CODE(true),
        TABLE(true),
        LINK(false),
        GENERIC_UI(true),
    }

    private companion object {
        val AndroidGlyphCoverage = GlyphCoverageChecker { face, grapheme ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face.typeface }.hasGlyph(grapheme)
        }
        const val SURFACE_PADDING = 16
        const val QUOTE_BAR_WIDTH = 12
        const val REGION_MIN_HEIGHT = 96
        const val REGION_MAX_HEIGHT = 480
        const val MESSAGE_WIDTH_FRACTION = 0.82f
        const val MODULE_QUIET_ZONE = 4
    }
}

private fun Iterable<TextRun>.segments(): List<CanonicalTextSegment> = map { run ->
    CanonicalTextSegment(run.canonicalText, run.scriptSet.toSet())
}

private fun CanonicalBlock.textFragments(): List<String> = when (this) {
    is ParagraphBlock -> runs.map { it.canonicalText }
    is HeadingBlock -> runs.map { it.canonicalText }
    is ListBlock -> items.flatMap { item -> item.runs.map { it.canonicalText } }
    is QuoteBlock -> runs.map { it.canonicalText }
    is MessageBlock -> runs.map { it.canonicalText }
    is CodeBlock -> listOf(code)
    is TableLikeBlock -> rows.flatMap { row -> row.cells.flatMap { cell -> cell.runs.map { it.canonicalText } } }
    is LinkBlock -> displayRuns.map { it.canonicalText }
    is GenericUiBlock -> runs.map { it.canonicalText }
    is ImagePlaceholderBlock -> listOfNotNull(label)
    is RedactionBlock -> listOfNotNull(label)
    is UnknownRegionBlock -> emptyList()
}
