package app.shareguard.block.url

import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SensitiveRepresentation
import app.shareguard.core.model.SourceLocation
import app.shareguard.core.model.UrlTokenId
import app.shareguard.core.model.toImmutableList

/** Lexically locates candidates; every candidate is subsequently accepted or rejected by OkHttp's parser. */
class UrlCandidateExtractor {
    fun extract(input: UrlProcessingInput, idPrefix: String = "url"): app.shareguard.core.model.ImmutableList<UrlCandidate> {
        UrlTokenId("$idPrefix-probe")
        val raw = when (input.sourceKind) {
            UrlSourceKind.QR_PAYLOAD,
            UrlSourceKind.PLAIN_TEXT_URL,
            UrlSourceKind.WRAPPED_TEXT,
            -> listOf(extractWholeValue(input))
            UrlSourceKind.MARKDOWN -> extractMarkdown(input) + extractAbsolute(input, markdownTargetsOnly = false)
            UrlSourceKind.PLAIN_TEXT,
            UrlSourceKind.OCR_TEXT,
            -> {
                val absolute = extractAbsolute(input, markdownTargetsOnly = false)
                val lexical = extractSchemeless(input) + extractEmails(input)
                absolute + lexical.filter { candidate ->
                    absolute.none { existing ->
                        candidate.startUtf16 < existing.endUtf16Exclusive &&
                            existing.startUtf16 < candidate.endUtf16Exclusive
                    }
                }
            }
        }
        return raw
            .filter { it.parseTarget.isNotBlank() }
            .distinctBy { it.targetStartUtf16 to it.parseTarget }
            .sortedWith(compareBy<RawCandidate> { it.startUtf16 }.thenBy { it.endUtf16Exclusive })
            .mapIndexed { index, candidate ->
                UrlCandidate(
                    tokenId = UrlTokenId("$idPrefix-u-$index"),
                    kind = candidate.kind,
                    sourceLocation = sourceLocation(input.text, candidate.startUtf16, candidate.endUtf16Exclusive),
                    originalReference = SensitiveRepresentation(candidate.originalReference),
                    displayText = SensitiveRepresentation(candidate.displayText),
                    parseTarget = SensitiveRepresentation(candidate.parseTarget),
                    schemeWasImplicit = candidate.schemeWasImplicit,
                    reconstructedVisualWrap = candidate.reconstructedVisualWrap,
                )
            }
            .toImmutableList()
    }

    private fun extractWholeValue(input: UrlProcessingInput): RawCandidate {
        val start = input.text.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
        val end = input.text.indexOfLast { !it.isWhitespace() }.let { if (it < 0) start else it + 1 }
        val original = input.text.substring(start, end)
        val reconstructed = reconstructApprovedVisualWraps(original, start, input.visualWrapOffsetsUtf16)
        return RawCandidate(
            startUtf16 = start,
            endUtf16Exclusive = end,
            kind = when {
                EMAIL.matches(reconstructed) -> UrlCandidateKind.EMAIL_LIKE
                input.sourceKind == UrlSourceKind.QR_PAYLOAD -> UrlCandidateKind.QR_PAYLOAD
                else -> if (hasHttpScheme(reconstructed)) UrlCandidateKind.ABSOLUTE else UrlCandidateKind.SCHEMELESS
            },
            originalReference = original,
            displayText = original,
            parseTarget = reconstructed,
            schemeWasImplicit = !hasHttpScheme(reconstructed),
            reconstructedVisualWrap = reconstructed != original,
        )
    }

    private fun extractMarkdown(input: UrlProcessingInput): List<RawCandidate> {
        val results = mutableListOf<RawCandidate>()
        var cursor = 0
        while (cursor < input.text.length) {
            val openLabel = input.text.indexOf('[', cursor)
            if (openLabel < 0) break
            val closeLabel = input.text.indexOf(']', openLabel + 1)
            if (closeLabel < 0 || closeLabel + 1 >= input.text.length || input.text[closeLabel + 1] != '(') {
                cursor = openLabel + 1
                continue
            }
            val closeTarget = findClosingParenthesis(input.text, closeLabel + 2)
            if (closeTarget < 0) {
                cursor = closeLabel + 2
                continue
            }
            val display = input.text.substring(openLabel + 1, closeLabel)
            val target = input.text.substring(closeLabel + 2, closeTarget).trim()
            val targetStart = input.text.indexOf(target, closeLabel + 2)
            if (target.isNotEmpty()) {
                results += RawCandidate(
                    startUtf16 = openLabel,
                    endUtf16Exclusive = closeTarget + 1,
                    kind = UrlCandidateKind.MARKDOWN_TARGET,
                    originalReference = target,
                    displayText = display,
                    parseTarget = target,
                    schemeWasImplicit = !hasHttpScheme(target),
                    reconstructedVisualWrap = false,
                    targetStartUtf16 = targetStart,
                )
            }
            cursor = closeTarget + 1
        }
        return results
    }

    private fun extractAbsolute(input: UrlProcessingInput, markdownTargetsOnly: Boolean): List<RawCandidate> {
        if (markdownTargetsOnly) return emptyList()
        val results = mutableListOf<RawCandidate>()
        var index = 0
        while (index < input.text.length) {
            val schemeLength = when {
                input.text.regionMatches(index, "https://", 0, 8, ignoreCase = true) -> 8
                input.text.regionMatches(index, "http://", 0, 7, ignoreCase = true) -> 7
                input.text.regionMatches(index, "mailto:", 0, 7, ignoreCase = true) -> 7
                else -> 0
            }
            if (schemeLength == 0) {
                index += 1
                continue
            }
            var end = index + schemeLength
            var parentheses = 0
            while (end < input.text.length) {
                val character = input.text[end]
                if (character.isWhitespace() || character == '<' || character == '>') break
                if (character == '(') parentheses += 1
                if (character == ')') {
                    if (parentheses == 0) break
                    parentheses -= 1
                }
                end += 1
            }
            end = trimTrailingProsePunctuation(input.text, index, end)
            val target = input.text.substring(index, end)
            results += RawCandidate(
                startUtf16 = index,
                endUtf16Exclusive = end,
                kind = if (target.startsWith("mailto:", ignoreCase = true)) {
                    UrlCandidateKind.EMAIL_LIKE
                } else {
                    UrlCandidateKind.ABSOLUTE
                },
                originalReference = target,
                displayText = target,
                parseTarget = target,
                schemeWasImplicit = false,
                reconstructedVisualWrap = false,
            )
            index = maxOf(end, index + 1)
        }
        return results
    }

    private fun extractSchemeless(input: UrlProcessingInput): List<RawCandidate> = SCHEMELESS.findAll(input.text)
        .mapNotNull { match ->
            val start = match.range.first
            var end = match.range.last + 1
            end = trimTrailingProsePunctuation(input.text, start, end)
            if (end <= start) return@mapNotNull null
            val target = input.text.substring(start, end)
            if (hasHttpScheme(target) || start > 0 && input.text[start - 1] == '@') return@mapNotNull null
            RawCandidate(
                startUtf16 = start,
                endUtf16Exclusive = end,
                kind = UrlCandidateKind.SCHEMELESS,
                originalReference = target,
                displayText = target,
                parseTarget = target,
                schemeWasImplicit = true,
                reconstructedVisualWrap = false,
            )
        }
        .toList()

    private fun extractEmails(input: UrlProcessingInput): List<RawCandidate> = EMAIL.findAll(input.text).map { match ->
        RawCandidate(
            startUtf16 = match.range.first,
            endUtf16Exclusive = match.range.last + 1,
            kind = UrlCandidateKind.EMAIL_LIKE,
            originalReference = match.value,
            displayText = match.value,
            parseTarget = match.value,
            schemeWasImplicit = false,
            reconstructedVisualWrap = false,
        )
    }.toList()

    private fun reconstructApprovedVisualWraps(
        value: String,
        globalStart: Int,
        visualWrapOffsets: List<Int>,
    ): String = buildString(value.length) {
        value.forEachIndexed { localIndex, character ->
            val globalOffset = globalStart + localIndex
            val wrappedCr = character == '\r' && value.getOrNull(localIndex + 1) == '\n' &&
                globalOffset + 1 in visualWrapOffsets
            val wrappedLf = character == '\n' && globalOffset in visualWrapOffsets
            if (!wrappedCr && !wrappedLf) append(character)
        }
    }

    private fun sourceLocation(text: String, start: Int, end: Int): SourceLocation = SourceLocation(
        scalarStart = text.codePointCount(0, start),
        scalarEndExclusive = text.codePointCount(0, end),
        safeDescription = SafeSummary("URL_CANDIDATE_LOCATION"),
    )

    private fun findClosingParenthesis(text: String, start: Int): Int {
        var depth = 0
        var index = start
        while (index < text.length) {
            when (text[index]) {
                '(' -> depth += 1
                ')' -> if (depth == 0) return index else depth -= 1
            }
            index += 1
        }
        return -1
    }

    private fun trimTrailingProsePunctuation(text: String, start: Int, initialEnd: Int): Int {
        var end = initialEnd
        while (end > start && text[end - 1] in TRAILING_PROSE_PUNCTUATION) end -= 1
        return end
    }

    private data class RawCandidate(
        val startUtf16: Int,
        val endUtf16Exclusive: Int,
        val kind: UrlCandidateKind,
        val originalReference: String,
        val displayText: String,
        val parseTarget: String,
        val schemeWasImplicit: Boolean,
        val reconstructedVisualWrap: Boolean,
        val targetStartUtf16: Int = startUtf16,
    )

    companion object {
        private val SCHEMELESS = Regex(
            "(?<![A-Za-z0-9@.-])(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}(?::[0-9]{1,5})?(?:/[^\\s<>()]*)?",
        )
        private val EMAIL = Regex("(?<![A-Za-z0-9.!#$%&'*+/=?^_`{|}~-])[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}(?![A-Za-z0-9.-])")
        private val TRAILING_PROSE_PUNCTUATION = setOf('.', ',', ';', ':', '!', ')', ']', '}')

        private fun hasHttpScheme(value: String): Boolean =
            value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)
    }
}
