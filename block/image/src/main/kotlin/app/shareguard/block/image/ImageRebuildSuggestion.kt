package app.shareguard.block.image

import app.shareguard.core.model.SafeSummary

enum class GenericRebuildKind { PLAIN_TEXT_FLOW, GENERIC_CARD, GENERIC_TABLE, SOLID_PLACEHOLDER }

data class ImageRebuildSuggestion(
    val kind: GenericRebuildKind,
    val confidence: Float,
    val reviewRequired: Boolean,
    val usesSourcePixels: Boolean = false,
    val rationale: SafeSummary,
) {
    init {
        require(confidence.isFinite() && confidence in 0f..1f)
        require(!usesSourcePixels) { "Rebuild suggestions cannot contain proprietary source pixels" }
    }
}

class ConservativeImageRebuildSuggester {
    fun suggest(
        structuralEvidenceAvailable: Boolean,
        confidence: Float,
        proposedKind: GenericRebuildKind,
    ): ImageRebuildSuggestion {
        require(confidence.isFinite() && confidence in 0f..1f)
        // No global cutoff is invented: callers decide whether evidence met their corpus-backed acceptance policy.
        val kind = if (structuralEvidenceAvailable) proposedKind else GenericRebuildKind.PLAIN_TEXT_FLOW
        return ImageRebuildSuggestion(
            kind = kind,
            confidence = confidence,
            reviewRequired = !structuralEvidenceAvailable,
            rationale = if (structuralEvidenceAvailable) {
                SafeSummary("Generic reconstruction suggested from declared structural evidence")
            } else {
                SafeSummary("Plain layout used because structural evidence was not accepted")
            },
        )
    }
}
