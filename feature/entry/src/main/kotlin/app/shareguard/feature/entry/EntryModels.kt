package app.shareguard.feature.entry

import app.shareguard.core.model.AssuranceClass
import app.shareguard.core.model.OutputMode

enum class EntryRoute { HOME, TEXT, IMAGE_PREVIEW, OUTPUT_CHOICE, PRESET_CHOICE }

data class AcceptedImageSummary(
    val detectedFormat: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val metadataEntryCount: Int,
    val animated: Boolean,
) {
    init {
        require(detectedFormat.isNotBlank())
        require(pixelWidth > 0 && pixelHeight > 0)
        require(metadataEntryCount >= 0)
    }
}

data class OutputChoice(
    val mode: OutputMode,
    val title: String,
    val preserves: String,
    val discards: String,
    val reviewBurden: String,
    val assuranceCeiling: AssuranceClass,
    val experimental: Boolean = false,
)

val outputChoices: List<OutputChoice> = listOf(
    OutputChoice(
        mode = OutputMode.TEXT,
        title = "Canonical text",
        preserves = "Reviewed wording and semantic structure.",
        discards = "Source pixels, font styling, image layout and container metadata.",
        reviewBurden = "Review hidden characters, confusables, links and meaning-changing edits.",
        assuranceCeiling = AssuranceClass.AS_2_REVIEWED_CANONICAL_TEXT,
    ),
    OutputChoice(
        mode = OutputMode.REBUILT_IMAGE,
        title = "Rebuilt image",
        preserves = "Reviewed text and a generic, controlled visual layout.",
        discards = "Original text pixels, exact source styling and source container metadata.",
        reviewBurden = "Review OCR, reading order, links and every non-text region.",
        assuranceCeiling = AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE,
    ),
    OutputChoice(
        mode = OutputMode.BOTH,
        title = "Text and rebuilt image",
        preserves = "One reviewed canonical revision in two verified representations.",
        discards = "Original glyph pixels and unapproved source metadata.",
        reviewBurden = "Includes all text and rebuilt-image review steps.",
        assuranceCeiling = AssuranceClass.AS_4_FULLY_REBUILT_TEXTUAL_IMAGE,
    ),
    OutputChoice(
        mode = OutputMode.DERIVATIVE_IMAGE,
        title = "Appearance-preserving image",
        preserves = "Much of the source appearance and therefore source-pixel dependencies.",
        discards = "Known metadata and some simple pixel or channel variations.",
        reviewBurden = "Explicitly accept that robust or unknown signals may remain.",
        assuranceCeiling = AssuranceClass.AS_1_REENCODED_DERIVATIVE,
        experimental = true,
    ),
)

data class PresetChoice(
    val id: String,
    val title: String,
    val contentType: String,
    val reviewCategories: Int,
    val sourcePixelsMayRemain: Boolean,
    val urlBehavior: String,
    val outputType: String,
)

val presetChoices: List<PresetChoice> = listOf(
    PresetChoice("PRESET-TT-BALANCED", "Balanced", "Text", 4, false, "Known tracking removed", "Canonical text"),
    PresetChoice("PRESET-TT-STRICT-URL", "Strict URL", "Text or image OCR", 5, false, "Query, fragment, path and subdomain reviewed", "Canonical text"),
    PresetChoice("PRESET-TI-REBUILT", "Text reconstruction", "Text", 5, false, "Reviewed and re-scanned", "Rebuilt image"),
    PresetChoice("PRESET-TB-BOTH", "Text plus reconstruction", "Text", 5, false, "Reviewed and re-scanned", "Text and rebuilt image"),
    PresetChoice("PRESET-IT-CANONICAL", "Image to canonical text", "Image", 7, false, "OCR URLs reviewed and re-scanned", "Canonical text"),
    PresetChoice("PRESET-II-FULL-REBUILD", "Full reconstruction", "Image", 7, false, "Reviewed and re-scanned", "Rebuilt image"),
    PresetChoice("PRESET-IB-BOTH", "Image text plus reconstruction", "Image", 7, false, "Reviewed and re-scanned", "Text and rebuilt image"),
    PresetChoice("PRESET-II-DERIVATIVE", "Experimental derivative", "Image", 2, true, "Not rewritten unless selected", "Derivative image"),
)
