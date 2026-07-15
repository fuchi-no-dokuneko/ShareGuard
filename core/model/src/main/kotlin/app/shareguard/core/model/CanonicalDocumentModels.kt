package app.shareguard.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LanguagePolicy(
    val primaryLanguage: LanguageTag?,
    val allowedLanguages: ImmutableList<LanguageTag>,
    val allowedScripts: ImmutableList<ScriptCode>,
    val multilingual: Boolean,
) {
    init {
        require(allowedLanguages.distinct().size == allowedLanguages.size) { "Language tags must be unique" }
        require(allowedScripts.distinct().size == allowedScripts.size) { "Scripts must be unique" }
        if (primaryLanguage != null) {
            require(primaryLanguage in allowedLanguages) { "Primary language must be allowed" }
        }
        require(multilingual || allowedLanguages.size <= 1) {
            "Multiple allowed languages require multilingual mode"
        }
    }

    companion object {
        fun create(
            primaryLanguage: LanguageTag? = null,
            allowedLanguages: Iterable<LanguageTag> = listOfNotNull(primaryLanguage),
            allowedScripts: Iterable<ScriptCode> = emptyList(),
            multilingual: Boolean = false,
        ): LanguagePolicy = LanguagePolicy(
            primaryLanguage = primaryLanguage,
            allowedLanguages = allowedLanguages.toImmutableList(),
            allowedScripts = allowedScripts.toImmutableList(),
            multilingual = multilingual,
        )
    }
}

@Serializable
data class SourceReference(
    val sourceId: SourceId,
    val location: SourceLocation,
)

@Serializable
data class TextRun(
    val canonicalText: String,
    val languageTag: LanguageTag?,
    val scriptSet: ImmutableList<ScriptCode>,
    val semanticRole: SemanticRole,
    val emphasisRole: EmphasisRole? = null,
    val sourceReference: SourceReference? = null,
    val confidenceClass: ConfidenceClass,
    val userLocked: Boolean,
) {
    init {
        require(scriptSet.distinct().size == scriptSet.size) { "TextRun scripts must be unique" }
        require(!canonicalText.contains('\u0000')) { "Canonical text cannot contain NUL" }
    }

    companion object {
        fun create(
            canonicalText: String,
            languageTag: LanguageTag? = null,
            scriptSet: Iterable<ScriptCode> = emptyList(),
            semanticRole: SemanticRole = SemanticRole.BODY,
            emphasisRole: EmphasisRole? = null,
            sourceReference: SourceReference? = null,
            confidenceClass: ConfidenceClass = ConfidenceClass.CERTAIN_BY_PARSER,
            userLocked: Boolean = false,
        ): TextRun = TextRun(
            canonicalText = canonicalText,
            languageTag = languageTag,
            scriptSet = scriptSet.toImmutableList(),
            semanticRole = semanticRole,
            emphasisRole = emphasisRole,
            sourceReference = sourceReference,
            confidenceClass = confidenceClass,
            userLocked = userLocked,
        )
    }
}

@Serializable
data class UrlQueryParameter(
    val name: String,
    val value: String?,
) {
    init { require(name.isNotEmpty()) { "URL query parameter name cannot be empty" } }
}

@Serializable
data class UrlComponents(
    val scheme: String?,
    val userInfo: String?,
    val host: String,
    val port: Int?,
    val registrableDomain: String?,
    val subdomain: String?,
    val pathSegments: ImmutableList<String>,
    val queryParameters: ImmutableList<UrlQueryParameter>,
    val fragment: String?,
) {
    init {
        require(host.isNotBlank()) { "Parsed URL host cannot be blank" }
        require(port == null || port in 1..65_535) { "Invalid URL port" }
    }

    companion object {
        fun create(
            scheme: String? = null,
            userInfo: String? = null,
            host: String,
            port: Int? = null,
            registrableDomain: String? = null,
            subdomain: String? = null,
            pathSegments: Iterable<String> = emptyList(),
            queryParameters: Iterable<UrlQueryParameter> = emptyList(),
            fragment: String? = null,
        ): UrlComponents = UrlComponents(
            scheme = scheme,
            userInfo = userInfo,
            host = host,
            port = port,
            registrableDomain = registrableDomain,
            subdomain = subdomain,
            pathSegments = pathSegments.toImmutableList(),
            queryParameters = queryParameters.toImmutableList(),
            fragment = fragment,
        )
    }
}

@Serializable
data class UrlToken(
    val tokenId: UrlTokenId,
    val originalReference: SourceReference?,
    val displayText: String,
    val parsedComponents: UrlComponents,
    val normalizedComponents: UrlComponents,
    val chosenPolicy: UrlPolicy,
    val finalText: String,
    val functionalityWarning: SafeSummary?,
    val userApproved: Boolean,
) {
    init {
        if (chosenPolicy !in setOf(UrlPolicy.KEEP_FULL, UrlPolicy.REMOVE_KNOWN_TRACKING)) {
            require(userApproved) { "Potentially functional URL changes require approval" }
        }
    }
}

@Serializable
data class ImageRegion(
    val regionId: ImageRegionId,
    val regionType: ImageRegionType,
    val sourceBounds: NormalizedRect,
    val canonicalBounds: NormalizedRect?,
    val policy: ImageRegionPolicy,
    val sourcePixelRetained: Boolean,
    val replacementAssetId: ArtifactReference?,
    val userApproved: Boolean,
    val dependencyReason: SafeSummary,
) {
    init {
        require(sourcePixelRetained == (policy == ImageRegionPolicy.RETAIN_SOURCE_PIXELS)) {
            "sourcePixelRetained must exactly match region policy"
        }
        if (sourcePixelRetained) {
            require(userApproved) { "Source pixel retention requires explicit approval" }
        }
        if (policy == ImageRegionPolicy.REPLACE_WITH_PLACEHOLDER) {
            require(replacementAssetId != null) { "Placeholder replacement requires an asset reference" }
        }
    }
}

@Serializable
data class LayoutElement(
    val canonicalBlockId: CanonicalBlockId,
    val bounds: NormalizedRect?,
    val readingOrderIndex: Int,
) {
    init { require(readingOrderIndex >= 0) { "Reading order index cannot be negative" } }
}

@Serializable
data class LayoutModel(
    val kind: LayoutKind,
    val elements: ImmutableList<LayoutElement>,
) {
    init {
        require(elements.map { it.canonicalBlockId }.distinct().size == elements.size) {
            "Layout block references must be unique"
        }
        require(elements.map { it.readingOrderIndex }.distinct().size == elements.size) {
            "Layout reading-order indexes must be unique"
        }
    }
}

@Serializable
data class ReadingOrder(
    val blockIds: ImmutableList<CanonicalBlockId>,
) {
    init { require(blockIds.distinct().size == blockIds.size) { "Reading order cannot contain duplicate blocks" } }
}

@Serializable
sealed interface CanonicalBlock {
    val id: CanonicalBlockId
}

@Serializable
@SerialName("paragraph")
data class ParagraphBlock(
    override val id: CanonicalBlockId,
    val runs: ImmutableList<TextRun>,
) : CanonicalBlock

@Serializable
@SerialName("heading")
data class HeadingBlock(
    override val id: CanonicalBlockId,
    val level: Int,
    val runs: ImmutableList<TextRun>,
) : CanonicalBlock {
    init { require(level in 1..6) { "Heading level must be 1 through 6" } }
}

@Serializable
data class CanonicalListItem(val runs: ImmutableList<TextRun>)

@Serializable
@SerialName("list")
data class ListBlock(
    override val id: CanonicalBlockId,
    val ordered: Boolean,
    val items: ImmutableList<CanonicalListItem>,
) : CanonicalBlock {
    init { require(items.isNotEmpty()) { "List block cannot be empty" } }
}

@Serializable
@SerialName("quote")
data class QuoteBlock(
    override val id: CanonicalBlockId,
    val runs: ImmutableList<TextRun>,
) : CanonicalBlock

@Serializable
@SerialName("message")
data class MessageBlock(
    override val id: CanonicalBlockId,
    val role: MessageRole,
    val runs: ImmutableList<TextRun>,
) : CanonicalBlock

@Serializable
@SerialName("code")
data class CodeBlock(
    override val id: CanonicalBlockId,
    val code: String,
    val languageHint: String? = null,
) : CanonicalBlock

@Serializable
data class TableCell(val runs: ImmutableList<TextRun>)

@Serializable
data class TableRow(val cells: ImmutableList<TableCell>) {
    init { require(cells.isNotEmpty()) { "Table row cannot be empty" } }
}

@Serializable
@SerialName("table_like")
data class TableLikeBlock(
    override val id: CanonicalBlockId,
    val rows: ImmutableList<TableRow>,
) : CanonicalBlock {
    init { require(rows.isNotEmpty()) { "Table-like block cannot be empty" } }
}

@Serializable
@SerialName("link")
data class LinkBlock(
    override val id: CanonicalBlockId,
    val urlTokenId: UrlTokenId,
    val displayRuns: ImmutableList<TextRun>,
) : CanonicalBlock

@Serializable
@SerialName("image_placeholder")
data class ImagePlaceholderBlock(
    override val id: CanonicalBlockId,
    val regionId: ImageRegionId,
    val label: String?,
) : CanonicalBlock

@Serializable
@SerialName("redaction")
data class RedactionBlock(
    override val id: CanonicalBlockId,
    val regionId: ImageRegionId,
    val label: String?,
) : CanonicalBlock

@Serializable
@SerialName("generic_ui")
data class GenericUiBlock(
    override val id: CanonicalBlockId,
    val role: String,
    val runs: ImmutableList<TextRun>,
) : CanonicalBlock {
    init { require(role.isNotBlank()) { "Generic UI role cannot be blank" } }
}

@Serializable
@SerialName("unknown_region")
data class UnknownRegionBlock(
    override val id: CanonicalBlockId,
    val regionId: ImageRegionId,
    val description: SafeSummary,
) : CanonicalBlock

@Serializable
data class CanonicalDocument(
    val schemaVersion: SchemaVersion,
    val revision: CanonicalRevision,
    val declaredLanguagePolicy: LanguagePolicy,
    val rootBlocks: ImmutableList<CanonicalBlock>,
    val urlTokens: ImmutableList<UrlToken>,
    val imageRegions: ImmutableList<ImageRegion>,
    val layoutModel: LayoutModel,
    val readingOrder: ReadingOrder,
    val userDecisions: ImmutableList<UserDecision>,
    val sourceDependencyMap: SourceDependencyMap,
    val changeLedgerReference: ChangeLedgerId,
) {
    init {
        val blockIds = rootBlocks.map { it.id }
        val urlIds = urlTokens.map { it.tokenId }
        val regionIds = imageRegions.map { it.regionId }
        val decisionIds = userDecisions.map { it.decisionId }

        require(blockIds.distinct().size == blockIds.size) { "Canonical block IDs must be unique" }
        require(urlIds.distinct().size == urlIds.size) { "URL token IDs must be unique" }
        require(regionIds.distinct().size == regionIds.size) { "Image region IDs must be unique" }
        require(decisionIds.distinct().size == decisionIds.size) { "Decision IDs must be unique" }
        require(readingOrder.blockIds.toSet() == blockIds.toSet()) {
            "Reading order must reference every root block exactly once"
        }
        require(layoutModel.elements.map { it.canonicalBlockId }.all { it in blockIds }) {
            "Layout references an unknown canonical block"
        }
        require(rootBlocks.filterIsInstance<LinkBlock>().all { link -> urlIds.contains(link.urlTokenId) }) {
            "Link block references an unknown URL token"
        }
        require(rootBlocks.filterIsInstance<ImagePlaceholderBlock>().all { it.regionId in regionIds }) {
            "Image placeholder references an unknown region"
        }
        require(rootBlocks.filterIsInstance<RedactionBlock>().all { it.regionId in regionIds }) {
            "Redaction references an unknown region"
        }
        require(rootBlocks.filterIsInstance<UnknownRegionBlock>().all { it.regionId in regionIds }) {
            "Unknown-region block references an unknown region"
        }
        require(userDecisions.all { it.canonicalRevision == null || it.canonicalRevision == revision }) {
            "User decisions must link to this revision"
        }
        require(sourceDependencyMap.canonicalRevision == revision) {
            "Dependency map revision must match Canonical Document"
        }
        sourceDependencyMap.validateReferences(blockIds.toSet(), regionIds.toSet(), decisionIds.toSet())
    }

    fun nextRevision(
        rootBlocks: ImmutableList<CanonicalBlock> = this.rootBlocks,
        urlTokens: ImmutableList<UrlToken> = this.urlTokens,
        imageRegions: ImmutableList<ImageRegion> = this.imageRegions,
        layoutModel: LayoutModel = this.layoutModel,
        readingOrder: ReadingOrder = this.readingOrder,
        userDecisions: ImmutableList<UserDecision>? = null,
        sourceDependencyMap: SourceDependencyMap? = null,
    ): CanonicalDocument {
        val nextRevision = revision.next()
        val rebasedDecisions = userDecisions ?: this.userDecisions.map { decision ->
            if (decision.canonicalRevision == revision) {
                decision.copy(canonicalRevision = nextRevision)
            } else {
                decision
            }
        }.toImmutableList()
        val rebasedDependencies = sourceDependencyMap ?: SourceDependencyMap.create(
            canonicalRevision = nextRevision,
            entries = this.sourceDependencyMap.entries.map { it.copy(canonicalRevision = nextRevision) },
        )
        return copy(
            revision = nextRevision,
            rootBlocks = rootBlocks,
            urlTokens = urlTokens,
            imageRegions = imageRegions,
            layoutModel = layoutModel,
            readingOrder = readingOrder,
            userDecisions = rebasedDecisions,
            sourceDependencyMap = rebasedDependencies,
        )
    }
}
