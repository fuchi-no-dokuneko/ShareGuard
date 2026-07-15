package app.shareguard.core.model

import kotlinx.serialization.Serializable

/** Session-only value. Saved Result persistence must never include this representation. */
@Serializable
@JvmInline
value class SensitiveRepresentation(val value: String) {
    init { require(!value.contains('\u0000')) { "Sensitive representation contains NUL" } }
}

@Serializable
data class ChangeEntry(
    val changeId: ChangeId,
    val blockId: BlockId,
    val blockVersion: BlockVersion,
    val canonicalRevision: CanonicalRevision,
    val category: FindingCategory,
    val sourceLocation: SourceLocation?,
    val beforeRepresentation: SensitiveRepresentation?,
    val afterRepresentation: SensitiveRepresentation?,
    val reason: SafeSummary,
    val reversibleBeforeExport: Boolean,
    val semanticImpact: SemanticImpact,
    val reviewLink: ReviewLink?,
    val verificationId: VerificationId?,
) {
    init {
        if (semanticImpact != SemanticImpact.NONE) {
            require(reviewLink != null) { "Possible semantic impact requires review linkage" }
        }
        if (reviewLink?.status == ReviewStatus.NOT_REQUIRED) {
            require(semanticImpact == SemanticImpact.NONE) {
                "Semantic changes cannot be marked review-not-required"
            }
        }
    }
}

@Serializable
data class ChangeLedger(
    val ledgerId: ChangeLedgerId,
    val canonicalRevision: CanonicalRevision,
    val entries: ImmutableList<ChangeEntry>,
) {
    init {
        require(entries.map { it.changeId }.distinct().size == entries.size) {
            "Change IDs must be unique"
        }
        require(entries.all { it.canonicalRevision.value <= canonicalRevision.value }) {
            "A ledger entry cannot reference a future canonical revision"
        }
    }

    fun append(entry: ChangeEntry): ChangeLedger {
        require(entry.canonicalRevision == canonicalRevision) {
            "New entry must link to the active canonical revision"
        }
        return copy(entries = entries.add(entry))
    }

    companion object {
        fun create(
            ledgerId: ChangeLedgerId,
            canonicalRevision: CanonicalRevision,
            entries: Iterable<ChangeEntry> = emptyList(),
        ): ChangeLedger = ChangeLedger(ledgerId, canonicalRevision, entries.toImmutableList())
    }
}
