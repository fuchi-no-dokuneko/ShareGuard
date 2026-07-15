package app.shareguard.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SourceDependency(
    val dependencyId: DependencyId,
    val type: DependencyType,
    val origin: DependencyOrigin,
    val canonicalRevision: CanonicalRevision,
    val canonicalBlockId: CanonicalBlockId? = null,
    val imageRegionId: ImageRegionId? = null,
    val decisionId: DecisionId? = null,
    val sourcePixelRetained: Boolean = false,
    val reason: SafeSummary,
) {
    init {
        require(sourcePixelRetained == (type == DependencyType.RETAINED_SOURCE_PIXELS)) {
            "sourcePixelRetained must exactly match RETAINED_SOURCE_PIXELS dependency type"
        }
        if (type == DependencyType.RETAINED_SOURCE_PIXELS) {
            require(imageRegionId != null && decisionId != null) {
                "Retained source pixels require region and user-decision linkage"
            }
        }
        if (type == DependencyType.USER_DECISION) {
            require(decisionId != null) { "User-decision dependency requires decisionId" }
        }
    }
}

@Serializable
data class SourceDependencyMap(
    val canonicalRevision: CanonicalRevision,
    val entries: ImmutableList<SourceDependency> = ImmutableList.empty(),
    val scope: SafeSummary = SafeSummary("Dependencies explicitly introduced or declared by pipeline blocks"),
) {
    init {
        require(entries.map { it.dependencyId }.distinct().size == entries.size) {
            "Dependency IDs must be unique"
        }
        require(entries.all { it.canonicalRevision == canonicalRevision }) {
            "All dependencies must link to the map's canonical revision"
        }
    }

    val retainsSourcePixels: Boolean
        get() = entries.any { it.sourcePixelRetained }

    val retainedRegionIds: ImmutableList<ImageRegionId>
        get() = entries.mapNotNull { if (it.sourcePixelRetained) it.imageRegionId else null }.toImmutableList()

    fun validateReferences(
        blockIds: Set<CanonicalBlockId>,
        regionIds: Set<ImageRegionId>,
        decisionIds: Set<DecisionId>,
    ) {
        require(entries.all { it.canonicalBlockId == null || it.canonicalBlockId in blockIds }) {
            "Dependency map references an unknown canonical block"
        }
        require(entries.all { it.imageRegionId == null || it.imageRegionId in regionIds }) {
            "Dependency map references an unknown image region"
        }
        require(entries.all { it.decisionId == null || it.decisionId in decisionIds }) {
            "Dependency map references an unknown user decision"
        }
    }

    companion object {
        fun create(
            canonicalRevision: CanonicalRevision,
            entries: Iterable<SourceDependency> = emptyList(),
        ): SourceDependencyMap = SourceDependencyMap(
            canonicalRevision = canonicalRevision,
            entries = entries.toImmutableList(),
        )
    }
}

@Serializable
data class SourceDependencySummary(
    val retainedSourcePixelRegions: ImmutableList<ImageRegionId> = ImmutableList.empty(),
    val dependencyTypes: ImmutableList<DependencyType> = ImmutableList.empty(),
) {
    init {
        require(retainedSourcePixelRegions.distinct().size == retainedSourcePixelRegions.size) {
            "Retained region summary cannot contain duplicates"
        }
        require(dependencyTypes.distinct().size == dependencyTypes.size) {
            "Dependency type summary cannot contain duplicates"
        }
    }
}
