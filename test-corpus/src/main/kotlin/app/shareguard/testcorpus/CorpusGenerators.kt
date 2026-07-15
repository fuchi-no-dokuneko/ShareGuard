package app.shareguard.testcorpus

import java.net.URI
import java.text.Normalizer

/** Deterministic source-variant generators. No detector or product threshold is defined here. */
object CorpusGenerators {
    fun zeroWidthVariants(value: String, insertionIndex: Int): List<GeneratedVariant> {
        require(insertionIndex in 0..value.length) { "insertionIndex must address the supplied string" }
        return listOf(
            GeneratedVariant("zero-width-space", value.insertAt(insertionIndex, "\u200B")),
            GeneratedVariant("word-joiner", value.insertAt(insertionIndex, "\u2060")),
            GeneratedVariant("zero-width-no-break-space", value.insertAt(insertionIndex, "\uFEFF")),
        )
    }

    fun unicodeSpaceVariants(left: String, right: String): List<GeneratedVariant> =
        listOf(
            GeneratedVariant("ascii-space", "$left $right"),
            GeneratedVariant("no-break-space", "$left\u00A0$right"),
            GeneratedVariant("thin-space", "$left\u2009$right"),
            GeneratedVariant("narrow-no-break-space", "$left\u202F$right"),
            GeneratedVariant("em-space", "$left\u2003$right"),
        )

    fun newlineVariants(lines: List<String>): List<GeneratedVariant> {
        require(lines.isNotEmpty()) { "At least one line is required" }
        return listOf(
            GeneratedVariant("lf", lines.joinToString("\n")),
            GeneratedVariant("crlf", lines.joinToString("\r\n")),
            GeneratedVariant("cr", lines.joinToString("\r")),
        )
    }

    fun combiningMarkVariants(value: String): List<GeneratedVariant> =
        listOf(
            GeneratedVariant("nfc", Normalizer.normalize(value, Normalizer.Form.NFC)),
            GeneratedVariant("nfd", Normalizer.normalize(value, Normalizer.Form.NFD)),
        ).distinctBy { it.value }

    fun punctuationVariants(): List<GeneratedVariant> =
        listOf(
            GeneratedVariant("ascii", "\"Don't - wait...\""),
            GeneratedVariant("typographic", "“Don’t — wait…”"),
            GeneratedVariant("mixed", "“Don't – wait...\""),
        )

    fun knownTrackingUrlVariants(baseUrl: String): List<GeneratedVariant> {
        val uri = URI(baseUrl)
        require(uri.scheme != null && uri.host != null) { "baseUrl must be absolute" }
        val separator = if (uri.query == null) "?" else "&"
        return listOf(
            GeneratedVariant("utm-source", "$baseUrl${separator}utm_source=mail"),
            GeneratedVariant("utm-campaign", "$baseUrl${separator}utm_campaign=summer"),
            GeneratedVariant("combined", "$baseUrl${separator}utm_source=mail&utm_campaign=summer"),
        )
    }

    fun percentEncodingVariants(origin: String, identifier: String): List<GeneratedVariant> {
        val encoded = identifier.encodeAsciiAsPercentBytes()
        return listOf(
            GeneratedVariant("plain-path-identifier", "$origin/$identifier"),
            GeneratedVariant("percent-encoded-path-identifier", "$origin/$encoded"),
            GeneratedVariant("percent-encoded-query-identifier", "$origin/?id=$encoded"),
        )
    }

    fun metadataVariants(visiblePayload: String): List<GeneratedVariant> =
        listOf(
            GeneratedVariant(
                variantId = "filename-a",
                value = visiblePayload,
                attributes = mapOf("sourceFilename" to "sender-A-7F2.png", "metadataId" to "meta-A-7F2"),
            ),
            GeneratedVariant(
                variantId = "filename-b",
                value = visiblePayload,
                attributes = mapOf("sourceFilename" to "sender-B-9C4.png", "metadataId" to "meta-B-9C4"),
            ),
            GeneratedVariant(
                variantId = "provider-uri",
                value = visiblePayload,
                attributes = mapOf("sourceUri" to "content://fixture/provider/item-3D8"),
            ),
        )

    private fun String.insertAt(index: Int, inserted: String): String =
        substring(0, index) + inserted + substring(index)

    private fun String.encodeAsciiAsPercentBytes(): String =
        encodeToByteArray().joinToString(separator = "") { byte -> "%%%02X".format(byte.toInt() and 0xFF) }
}
