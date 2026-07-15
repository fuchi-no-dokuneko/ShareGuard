package app.shareguard.block.text

import app.shareguard.core.model.BlockId
import app.shareguard.core.model.ConfidenceClass
import app.shareguard.core.model.DecisionAction
import app.shareguard.core.model.DecisionId
import app.shareguard.core.model.Finding
import app.shareguard.core.model.FindingCategory
import app.shareguard.core.model.FindingId
import app.shareguard.core.model.FindingStatus
import app.shareguard.core.model.LanguagePolicy
import app.shareguard.core.model.ReviewStatus
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.ScriptCode
import app.shareguard.core.model.SemanticRisk
import app.shareguard.core.model.Severity
import app.shareguard.core.model.SensitiveRepresentation
import app.shareguard.core.model.toImmutableList
import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UProperty
import com.ibm.icu.lang.UScript
import com.ibm.icu.text.BreakIterator
import com.ibm.icu.text.Normalizer2
import com.ibm.icu.text.SpoofChecker
import com.ibm.icu.util.ULocale

class UnicodeTextInspector(
    private val spoofChecker: SpoofChecker = defaultSpoofChecker(),
) {
    fun inspect(
        input: TextProcessingInput,
        selectedLanguagePolicy: LanguagePolicy = LanguagePolicy.create(),
        idPrefix: String = "text",
    ): TextInspection {
        val ids = TextIds(idPrefix)
        val scalars = scalarInventory(input.visibleText)
        val malformed = scalars.filter { it.malformedUtf16 }
        val unsupportedControls = scalars.filter { scalar ->
            scalar.codePoint?.let { Character.isISOControl(it) && it !in ALLOWED_TEXT_CONTROLS } == true
        }
        val failures = malformed.map {
            TextFailure(
                code = TextFailureCode.MALFORMED_UTF16,
                blockId = TXT_001,
                sourceLocation = textSourceLocation(it.scalarIndex, it.scalarIndex + 1, "MALFORMED_UTF16_LOCATION"),
            )
        }.toMutableList()
        failures += unsupportedControls.map {
            TextFailure(
                code = TextFailureCode.UNSUPPORTED_SCALAR,
                blockId = TXT_001,
                sourceLocation = textSourceLocation(it.scalarIndex, it.scalarIndex + 1, "UNSUPPORTED_CONTROL_LOCATION"),
            )
        }
        val protectedSpans = resolveProtectedSpans(input)
        val graphemes = if (malformed.isEmpty()) graphemeInventory(input.visibleText, scalars) else emptyList()
        val normalizationDeltas = if (malformed.isEmpty()) {
            normalizationInventory(input.visibleText, graphemes)
        } else {
            emptyList()
        }
        val invisibles = invisibleInventory(input.visibleText, scalars, selectedLanguagePolicy)
        val whitespace = whitespaceInventory(input.visibleText, scalars)
        val punctuation = punctuationInventory(scalars)
        val lineStructure = lineStructureInventory(input.visibleText, input.visualWrapOffsetsUtf16)
        val tokens = if (malformed.isEmpty()) {
            tokenInventory(input.visibleText, protectedSpans)
        } else {
            emptyList()
        }
        val languageResolution = resolveLanguagePolicy(scalars, selectedLanguagePolicy)

        val findings = mutableListOf<TaggedFinding>()
        malformed.forEach { scalar ->
            findings += taggedFinding(
                ids = ids,
                blockId = TXT_001,
                category = FindingCategory.UNICODE,
                severity = Severity.HIGH,
                confidence = ConfidenceClass.CERTAIN_BY_PARSER,
                scalarStart = scalar.scalarIndex,
                scalarEnd = scalar.scalarIndex + 1,
                title = "MALFORMED_UTF16",
                explanation = "UTF16_SCALAR_SEQUENCE_REQUIRES_REVIEW",
                semanticRisk = SemanticRisk.HIGH_IMPACT,
                reviewCode = TextReviewCode.MALFORMED_UNICODE_REVIEW,
            )
        }
        unsupportedControls.forEach { scalar ->
            findings += taggedFinding(
                ids = ids,
                blockId = TXT_001,
                category = FindingCategory.UNICODE,
                severity = Severity.HIGH,
                confidence = ConfidenceClass.CERTAIN_BY_PARSER,
                scalarStart = scalar.scalarIndex,
                scalarEnd = scalar.scalarIndex + 1,
                title = "UNSUPPORTED_CANONICAL_CONTROL",
                explanation = "CONTROL_REQUIRES_MANUAL_VISIBLE_RESOLUTION",
                semanticRisk = SemanticRisk.HIGH_IMPACT,
                reviewCode = TextReviewCode.UNSUPPORTED_CONTROL_REVIEW,
            )
        }
        normalizationDeltas.forEach { delta ->
            val compatibilityOnly = when (delta.form) {
                TextNormalizationForm.NFKC -> Normalizer2.getNFCInstance().normalize(delta.before.value) ==
                    delta.before.value
                TextNormalizationForm.NFKD -> Normalizer2.getNFDInstance().normalize(delta.before.value) ==
                    delta.before.value
                TextNormalizationForm.NFC,
                TextNormalizationForm.NFD,
                -> false
            }
            val compatibilityReview = compatibilityOnly && delta.before.value.codePoints().anyMatch {
                UCharacter.isLetterOrDigit(it)
            }
            findings += taggedFinding(
                ids = ids,
                blockId = TXT_002,
                category = FindingCategory.UNICODE,
                severity = if (compatibilityReview) Severity.MEDIUM else Severity.LOW,
                confidence = ConfidenceClass.CERTAIN_BY_PARSER,
                scalarStart = scalarIndexAt(scalars, delta.utf16Start),
                scalarEnd = scalarIndexAt(scalars, delta.utf16EndExclusive, endBoundary = true),
                title = if (compatibilityOnly) "COMPATIBILITY_NORMALIZATION_DELTA" else "CANONICAL_NORMALIZATION_DELTA",
                explanation = "NORMALIZATION_CHANGES_SCALAR_SEQUENCE",
                semanticRisk = if (compatibilityReview) SemanticRisk.POSSIBLE_MEANING_CHANGE else SemanticRisk.NONE,
                reviewCode = if (compatibilityReview) TextReviewCode.COMPATIBILITY_NORMALIZATION_REVIEW else null,
            )
        }
        invisibles.forEach { invisible ->
            val reviewCode = when {
                invisible.bidiControl -> TextReviewCode.BIDI_DISPLAY_REVIEW
                invisible.languageSensitive -> TextReviewCode.LANGUAGE_SENSITIVE_CONTROL_REVIEW
                else -> null
            }
            findings += taggedFinding(
                ids = ids,
                blockId = TXT_003,
                category = FindingCategory.UNICODE,
                severity = if (reviewCode == null) Severity.LOW else Severity.HIGH,
                confidence = ConfidenceClass.CERTAIN_BY_PARSER,
                scalarStart = invisible.scalarIndex,
                scalarEnd = invisible.scalarIndex + 1,
                title = "DEFAULT_IGNORABLE_OR_CONTROL",
                explanation = "INVISIBLE_CHARACTER_CLASSIFIED",
                semanticRisk = if (reviewCode == null) SemanticRisk.NONE else SemanticRisk.POSSIBLE_MEANING_CHANGE,
                reviewCode = reviewCode,
            )
        }
        whitespace.filter { record ->
            record.codePoint != 0x20 || record.lineTerminator || record.leading || record.trailing || record.repeated
        }.forEach { record ->
            findings += taggedFinding(
                ids = ids,
                blockId = TXT_004,
                category = FindingCategory.UNICODE,
                severity = Severity.LOW,
                confidence = ConfidenceClass.CERTAIN_BY_PARSER,
                scalarStart = record.scalarIndex,
                scalarEnd = record.scalarIndex + 1,
                title = "NON_CANONICAL_WHITESPACE",
                explanation = "WHITESPACE_TAXONOMY_VARIANT",
                semanticRisk = SemanticRisk.NONE,
            )
        }
        tokens.filter { it.mixedScript }.forEach { token ->
            val reviewCode = if (input.sourceKind == TextSourceKind.IDENTIFIER) {
                TextReviewCode.IDENTIFIER_CONFUSABLE_REVIEW
            } else {
                TextReviewCode.MIXED_SCRIPT_CONFUSABLE_REVIEW
            }
            findings += taggedFinding(
                ids = ids,
                blockId = TXT_005,
                category = FindingCategory.CONFUSABLE,
                severity = Severity.HIGH,
                confidence = ConfidenceClass.STRONG_HEURISTIC,
                scalarStart = scalarIndexAt(scalars, token.utf16Start),
                scalarEnd = scalarIndexAt(scalars, token.utf16EndExclusive, endBoundary = true),
                title = "MIXED_SCRIPT_TOKEN",
                explanation = "ICU_SPOOF_CHECK_REQUIRES_TOKEN_REVIEW",
                semanticRisk = SemanticRisk.HIGH_IMPACT,
                reviewCode = reviewCode,
            )
        }
        punctuation.filter { isPunctuationVariant(it.codePoint) }.forEach { record ->
            val protectedMath = protectedSpans.any {
                it.kind == ProtectedSpanKind.MATHEMATICS && it.contains(record.utf16Start)
            }
            val reviewCode = when {
                record.codePoint == 0x2212 || record.semanticallySensitive || protectedMath -> {
                    TextReviewCode.MATH_AND_RANGE_PUNCTUATION_REVIEW
                }
                record.codePoint in DASH_VARIANTS -> TextReviewCode.PUNCTUATION_SEMANTICS_REVIEW
                else -> null
            }
            findings += taggedFinding(
                ids = ids,
                blockId = TXT_006,
                category = FindingCategory.PUNCTUATION,
                severity = if (reviewCode == null) Severity.LOW else Severity.MEDIUM,
                confidence = ConfidenceClass.CERTAIN_BY_PARSER,
                scalarStart = record.scalarIndex,
                scalarEnd = record.scalarIndex + 1,
                title = "PUNCTUATION_VARIANT",
                explanation = "PUNCTUATION_PROFILE_DIFFERENCE",
                semanticRisk = if (reviewCode == null) SemanticRisk.NONE else SemanticRisk.POSSIBLE_MEANING_CHANGE,
                reviewCode = reviewCode,
            )
        }
        if (input.visualWrapOffsetsUtf16.isNotEmpty()) {
            val wrapFindings = input.visualWrapOffsetsUtf16.map { utf16Offset ->
                taggedFinding(
                    ids = ids,
                    blockId = TXT_007,
                    category = FindingCategory.LAYOUT,
                    severity = Severity.MEDIUM,
                    confidence = ConfidenceClass.WEAK_HEURISTIC,
                    scalarStart = scalarIndexAt(scalars, utf16Offset),
                    scalarEnd = scalarIndexAt(scalars, utf16Offset, endBoundary = true) + 1,
                    title = "POSSIBLE_VIEWPORT_WRAP",
                    explanation = "LINE_BREAK_REQUIRES_STRUCTURE_REVIEW",
                    semanticRisk = SemanticRisk.POSSIBLE_MEANING_CHANGE,
                    reviewCode = TextReviewCode.VIEWPORT_WRAP_REVIEW,
                )
            }
            findings += wrapFindings
        }
        if (languageResolution.requiresReview && tokens.none { it.mixedScript }) {
            findings += taggedFinding(
                ids = ids,
                blockId = TXT_008,
                category = FindingCategory.SEMANTIC,
                severity = Severity.MEDIUM,
                confidence = ConfidenceClass.WEAK_HEURISTIC,
                scalarStart = 0,
                scalarEnd = scalars.size,
                title = "MULTILINGUAL_POLICY_UNRESOLVED",
                explanation = "MULTIPLE_SCRIPT_SPANS_REQUIRE_LANGUAGE_POLICY_REVIEW",
                semanticRisk = SemanticRisk.POSSIBLE_MEANING_CHANGE,
                reviewCode = TextReviewCode.LANGUAGE_POLICY_REVIEW,
            )
        }
        if (input.sourceKind == TextSourceKind.CODE_BLOCK) {
            findings += taggedFinding(
                ids = ids,
                blockId = TXT_009,
                category = FindingCategory.SEMANTIC,
                severity = Severity.MEDIUM,
                confidence = ConfidenceClass.CERTAIN_BY_PARSER,
                scalarStart = 0,
                scalarEnd = scalars.size,
                title = "CODE_REGION_LOCKED",
                explanation = "CODE_WHITESPACE_AND_PUNCTUATION_PRESERVED",
                semanticRisk = SemanticRisk.HIGH_IMPACT,
                reviewCode = TextReviewCode.CODE_REGION_REVIEW,
            )
        }
        if (input.hiddenAlternativePresent || input.sourceKind == TextSourceKind.HTML_CLIPBOARD) {
            findings += taggedFinding(
                ids = ids,
                blockId = TXT_016,
                category = FindingCategory.SEMANTIC,
                severity = Severity.HIGH,
                confidence = ConfidenceClass.CERTAIN_BY_PARSER,
                scalarStart = 0,
                scalarEnd = scalars.size,
                title = "RICH_ALTERNATIVE_DISCARDED",
                explanation = "VISIBLE_AND_ALTERNATIVE_REPRESENTATIONS_REQUIRE_REVIEW",
                semanticRisk = SemanticRisk.POSSIBLE_MEANING_CHANGE,
                reviewCode = TextReviewCode.VISIBLE_TEXT_LINK_TARGET_REVIEW,
            )
        } else if (input.richRepresentationPresent || input.sourceKind == TextSourceKind.STYLED_TEXT) {
            findings += taggedFinding(
                ids = ids,
                blockId = TXT_016,
                category = FindingCategory.SEMANTIC,
                severity = Severity.LOW,
                confidence = ConfidenceClass.CERTAIN_BY_PARSER,
                scalarStart = 0,
                scalarEnd = scalars.size,
                title = "RICH_REPRESENTATION_DISCARDED",
                explanation = "ONLY_VISIBLE_SEMANTIC_TEXT_RETAINED",
                semanticRisk = SemanticRisk.NONE,
            )
        }

        val modelFindings = findings.map { it.finding }.toImmutableList()
        val reviewGates = findings
            .filter { it.reviewCode != null }
            .groupBy { requireNotNull(it.reviewCode) }
            .map { (code, grouped) ->
                TextReviewGate(
                    code = code,
                    decisionId = ids.decision(code),
                    findingIds = grouped.map { it.finding.findingId }.toImmutableList(),
                    blocking = true,
                    status = ReviewStatus.PENDING,
                    summary = SafeSummary(code.name),
                )
            }
            .sortedBy { it.code.name }
            .toImmutableList()

        return TextInspection(
            scalarInventory = scalars.toImmutableList(),
            graphemeClusters = graphemes.toImmutableList(),
            normalizationDeltas = normalizationDeltas.toImmutableList(),
            invisibleCharacters = invisibles.toImmutableList(),
            whitespace = whitespace.toImmutableList(),
            punctuation = punctuation.toImmutableList(),
            lineStructure = lineStructure.toImmutableList(),
            tokens = tokens.toImmutableList(),
            protectedSpans = protectedSpans.toImmutableList(),
            languageResolution = languageResolution,
            findings = modelFindings,
            reviewGates = reviewGates,
            failures = failures.toImmutableList(),
        )
    }

    fun reserializeScalars(inspection: TextInspection): String {
        require(inspection.scalarInventory.none { it.malformedUtf16 || it.codePoint == null }) { "MALFORMED_UTF16" }
        return buildString {
            inspection.scalarInventory.forEach { appendCodePoint(requireNotNull(it.codePoint)) }
        }
    }

    private fun scalarInventory(text: String): List<UnicodeScalarRecord> {
        val records = mutableListOf<UnicodeScalarRecord>()
        var utf16 = 0
        var scalar = 0
        while (utf16 < text.length) {
            val first = text[utf16]
            val wellFormedPair = first.isHighSurrogate() && utf16 + 1 < text.length && text[utf16 + 1].isLowSurrogate()
            val malformed = (first.isHighSurrogate() && !wellFormedPair) || first.isLowSurrogate()
            val codePoint = if (malformed) null else Character.codePointAt(text, utf16)
            val width = if (wellFormedPair) 2 else 1
            records += UnicodeScalarRecord(
                scalarIndex = scalar,
                utf16Start = utf16,
                utf16EndExclusive = utf16 + width,
                codePoint = codePoint,
                unicodeName = codePoint?.let { UCharacter.getName(it) } ?: "MALFORMED_UTF16",
                generalCategory = codePoint?.let { UCharacter.getType(it) } ?: -1,
                script = codePoint?.let { scriptForCodePoint(it) } ?: ScriptCode.OTHER,
                combiningClass = codePoint?.let { UCharacter.getCombiningClass(it) } ?: -1,
                malformedUtf16 = malformed,
            )
            utf16 += width
            scalar += 1
        }
        return records
    }

    private fun graphemeInventory(
        text: String,
        scalars: List<UnicodeScalarRecord>,
    ): List<GraphemeClusterRecord> {
        val iterator = BreakIterator.getCharacterInstance(ULocale.ROOT)
        iterator.setText(text)
        val records = mutableListOf<GraphemeClusterRecord>()
        var start = iterator.first()
        var end = iterator.next()
        var index = 0
        while (end != BreakIterator.DONE) {
            records += GraphemeClusterRecord(
                clusterIndex = index++,
                utf16Start = start,
                utf16EndExclusive = end,
                scalarStart = scalarIndexAt(scalars, start),
                scalarEndExclusive = scalarIndexAt(scalars, end, endBoundary = true),
            )
            start = end
            end = iterator.next()
        }
        return records
    }

    private fun normalizationInventory(
        text: String,
        graphemes: List<GraphemeClusterRecord>,
    ): List<NormalizationDelta> {
        val forms = listOf(
            TextNormalizationForm.NFC to Normalizer2.getNFCInstance(),
            TextNormalizationForm.NFD to Normalizer2.getNFDInstance(),
            TextNormalizationForm.NFKC to Normalizer2.getNFKCInstance(),
            TextNormalizationForm.NFKD to Normalizer2.getNFKDInstance(),
        )
        return buildList {
            graphemes.forEach { cluster ->
                val before = text.substring(cluster.utf16Start, cluster.utf16EndExclusive)
                forms.forEach { (form, normalizer) ->
                    val after = normalizer.normalize(before)
                    if (before != after) {
                        add(
                            NormalizationDelta(
                                form = form,
                                utf16Start = cluster.utf16Start,
                                utf16EndExclusive = cluster.utf16EndExclusive,
                                before = SensitiveRepresentation(before),
                                after = SensitiveRepresentation(after),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun invisibleInventory(
        text: String,
        scalars: List<UnicodeScalarRecord>,
        languagePolicy: LanguagePolicy,
    ): List<InvisibleCharacterRecord> = scalars.mapNotNull { scalar ->
        val codePoint = scalar.codePoint ?: return@mapNotNull null
        val defaultIgnorable = UCharacter.hasBinaryProperty(codePoint, UProperty.DEFAULT_IGNORABLE_CODE_POINT)
        val bidi = isBidiControl(codePoint)
        val variation = UCharacter.hasBinaryProperty(codePoint, UProperty.VARIATION_SELECTOR)
        if (!defaultIgnorable && !bidi && !variation && UCharacter.getType(codePoint) != UCharacter.FORMAT.toInt()) {
            return@mapNotNull null
        }
        val adjacentEmoji = adjacentCodePoints(text, scalar.utf16Start).any {
            UCharacter.hasBinaryProperty(it, UProperty.EMOJI)
        }
        val shapingScript = languagePolicy.allowedScripts.any {
            it in setOf(ScriptCode.ARABIC, ScriptCode.DEVANAGARI)
        }
        InvisibleCharacterRecord(
            scalarIndex = scalar.scalarIndex,
            utf16Start = scalar.utf16Start,
            codePoint = codePoint,
            defaultIgnorable = defaultIgnorable,
            bidiControl = bidi,
            variationSelector = variation,
            languageSensitive = when (codePoint) {
                0x200C, 0x200D -> adjacentEmoji || shapingScript
                else -> variation
            },
        )
    }

    private fun whitespaceInventory(
        text: String,
        scalars: List<UnicodeScalarRecord>,
    ): List<WhitespaceRecord> = scalars.mapNotNull { scalar ->
        val codePoint = scalar.codePoint ?: return@mapNotNull null
        if (!UCharacter.isUWhiteSpace(codePoint) && codePoint !in LINE_TERMINATORS) return@mapNotNull null
        val before = text.substring(0, scalar.utf16Start)
        val after = text.substring(scalar.utf16EndExclusive)
        val lineStart = before.lastIndexOfAny(charArrayOf('\n', '\r')) + 1
        val lineEndOffset = after.indexOfAny(charArrayOf('\n', '\r')).let { if (it < 0) after.length else it }
        val linePrefix = text.substring(lineStart, scalar.utf16Start)
        val lineSuffix = after.substring(0, lineEndOffset)
        val previousCodePoint = if (scalar.utf16Start > 0) text.codePointBefore(scalar.utf16Start) else null
        WhitespaceRecord(
            scalarIndex = scalar.scalarIndex,
            utf16Start = scalar.utf16Start,
            codePoint = codePoint,
            lineTerminator = codePoint in LINE_TERMINATORS,
            leading = linePrefix.isEmpty(),
            trailing = lineSuffix.isEmpty(),
            repeated = previousCodePoint != null && UCharacter.isUWhiteSpace(previousCodePoint) &&
                previousCodePoint !in LINE_TERMINATORS && codePoint !in LINE_TERMINATORS,
        )
    }

    private fun punctuationInventory(scalars: List<UnicodeScalarRecord>): List<PunctuationRecord> =
        scalars.mapNotNull { scalar ->
            val codePoint = scalar.codePoint ?: return@mapNotNull null
            val type = UCharacter.getType(codePoint)
            if (type !in PUNCTUATION_TYPES && codePoint != 0x2212) return@mapNotNull null
            PunctuationRecord(
                scalarIndex = scalar.scalarIndex,
                utf16Start = scalar.utf16Start,
                codePoint = codePoint,
                semanticallySensitive = codePoint == 0x2212,
            )
        }

    private fun lineStructureInventory(
        text: String,
        visualWrapOffsets: List<Int>,
    ): List<LineStructureRecord> {
        val records = mutableListOf<LineStructureRecord>()
        var lineStart = 0
        var offset = 0
        var lineIndex = 0
        while (offset <= text.length) {
            val atEnd = offset == text.length
            val codePoint = if (atEnd) -1 else text.codePointAt(offset)
            if (atEnd || codePoint in LINE_TERMINATORS) {
                val content = text.substring(lineStart, offset)
                val indentation = content.codePoints().takeWhile {
                    UCharacter.isUWhiteSpace(it) && it !in LINE_TERMINATORS
                }.count()
                val marker = LIST_MARKER.find(content)?.value
                val terminatorOffset = if (codePoint == '\r'.code &&
                    offset + 1 < text.length && text[offset + 1] == '\n'
                ) {
                    offset + 1
                } else {
                    offset
                }
                records += LineStructureRecord(
                    lineIndex = lineIndex++,
                    utf16Start = lineStart,
                    utf16EndExclusive = offset,
                    indentationScalars = indentation.toInt(),
                    blank = content.isBlank(),
                    listMarker = marker,
                    visualWrapTerminator = terminatorOffset in visualWrapOffsets,
                )
                if (atEnd) break
                val width = Character.charCount(codePoint)
                offset += width
                if (codePoint == '\r'.code && offset < text.length && text[offset] == '\n') offset += 1
                lineStart = offset
            } else {
                offset += Character.charCount(codePoint)
            }
        }
        return records
    }

    private fun tokenInventory(
        text: String,
        protectedSpans: List<ProtectedSpan>,
    ): List<TokenSecurityRecord> {
        val iterator = BreakIterator.getWordInstance(ULocale.ROOT)
        iterator.setText(text)
        val tokens = mutableListOf<TokenSecurityRecord>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val token = text.substring(start, end)
            if (token.codePoints().anyMatch { UCharacter.isLetterOrDigit(it) || it == '_'.code }) {
                val scripts = token.codePoints().toArray()
                    .map(::scriptForCodePoint)
                    .filter { it !in setOf(ScriptCode.COMMON, ScriptCode.INHERITED) }
                    .distinct()
                val result = SpoofChecker.CheckResult()
                spoofChecker.failsChecks(token, result)
                tokens += TokenSecurityRecord(
                    utf16Start = start,
                    utf16EndExclusive = end,
                    token = SensitiveRepresentation(token),
                    scripts = scripts.toImmutableList(),
                    confusableSkeleton = SensitiveRepresentation(spoofChecker.getSkeleton(token)),
                    spoofChecks = result.checks,
                    restrictionLevel = result.restrictionLevel?.toString() ?: "UNKNOWN",
                    mixedScript = scripts.size > 1,
                    protectedSpan = protectedSpans.any { it.startUtf16 <= start && it.endUtf16Exclusive >= end },
                )
            }
            start = end
            end = iterator.next()
        }
        return tokens
    }

    private fun resolveLanguagePolicy(
        scalars: List<UnicodeScalarRecord>,
        selected: LanguagePolicy,
    ): LanguageResolution {
        if (selected.allowedScripts.isNotEmpty() || selected.allowedLanguages.isNotEmpty()) {
            return LanguageResolution(selected, ConfidenceClass.CERTAIN_BY_PARSER, requiresReview = false)
        }
        val scripts = scalars.map { it.script }
            .filter { it !in setOf(ScriptCode.COMMON, ScriptCode.INHERITED) }
            .distinct()
        val confusableScriptCombination = ScriptCode.LATIN in scripts && scripts.any {
            it in setOf(ScriptCode.GREEK, ScriptCode.CYRILLIC)
        }
        return LanguageResolution(
            policy = LanguagePolicy.create(
                allowedScripts = scripts,
                multilingual = scripts.size > 1,
            ),
            confidenceClass = if (confusableScriptCombination) {
                ConfidenceClass.WEAK_HEURISTIC
            } else {
                ConfidenceClass.STRONG_HEURISTIC
            },
            requiresReview = confusableScriptCombination,
        )
    }

    private fun resolveProtectedSpans(input: TextProcessingInput): List<ProtectedSpan> {
        val spans = input.explicitProtectedSpans.toMutableList()
        when (input.sourceKind) {
            TextSourceKind.CODE_BLOCK -> if (input.visibleText.isNotEmpty()) {
                spans += ProtectedSpan(0, input.visibleText.length, ProtectedSpanKind.CODE, userLocked = true)
            }
            TextSourceKind.IDENTIFIER -> if (input.visibleText.isNotEmpty()) {
                spans += ProtectedSpan(0, input.visibleText.length, ProtectedSpanKind.IDENTIFIER, userLocked = true)
            }
            else -> Unit
        }
        spans += protectedMatches(input.visibleText, BACKTICK_CODE, ProtectedSpanKind.CODE)
        spans += protectedMatches(input.visibleText, EMAIL_TOKEN, ProtectedSpanKind.EMAIL)
        spans += protectedMatches(input.visibleText, URI_TOKEN, ProtectedSpanKind.URL)
        spans += protectedMatches(input.visibleText, IDENTIFIER_TOKEN, ProtectedSpanKind.IDENTIFIER)
        spans += protectedMatches(input.visibleText, CRYPTO_TOKEN, ProtectedSpanKind.CRYPTOGRAPHIC_STRING)
        spans += protectedMatches(input.visibleText, MATH_TOKEN, ProtectedSpanKind.MATHEMATICS)
        spans += protectedMatches(input.visibleText, QUOTED_LITERAL, ProtectedSpanKind.QUOTED_LITERAL)
        return mergeSpans(spans, input.visibleText.length)
    }

    private fun protectedMatches(text: String, regex: Regex, kind: ProtectedSpanKind): List<ProtectedSpan> =
        regex.findAll(text).map { match ->
            ProtectedSpan(match.range.first, match.range.last + 1, kind, userLocked = true)
        }.toList()

    private fun mergeSpans(spans: List<ProtectedSpan>, textLength: Int): List<ProtectedSpan> {
        if (spans.isEmpty()) return emptyList()
        require(spans.all { it.endUtf16Exclusive <= textLength }) { "INVALID_PROTECTED_SPAN" }
        val sorted = spans.sortedWith(compareBy<ProtectedSpan> { it.startUtf16 }.thenByDescending { it.endUtf16Exclusive })
        val result = mutableListOf<ProtectedSpan>()
        sorted.forEach { span ->
            val previous = result.lastOrNull()
            if (previous == null || span.startUtf16 >= previous.endUtf16Exclusive) {
                result += span
            } else {
                result[result.lastIndex] = ProtectedSpan(
                    startUtf16 = previous.startUtf16,
                    endUtf16Exclusive = maxOf(previous.endUtf16Exclusive, span.endUtf16Exclusive),
                    kind = if (previous.kind == ProtectedSpanKind.USER_LOCKED) previous.kind else span.kind,
                    userLocked = previous.userLocked || span.userLocked,
                )
            }
        }
        return result
    }

    private fun taggedFinding(
        ids: TextIds,
        blockId: BlockId,
        category: FindingCategory,
        severity: Severity,
        confidence: ConfidenceClass,
        scalarStart: Int,
        scalarEnd: Int,
        title: String,
        explanation: String,
        semanticRisk: SemanticRisk,
        reviewCode: TextReviewCode? = null,
    ): TaggedFinding {
        val findingId = ids.finding()
        return TaggedFinding(
            finding = Finding(
                findingId = findingId,
                blockId = blockId,
                category = category,
                severity = severity,
                confidenceClass = confidence,
                sourceLocation = textSourceLocation(scalarStart, scalarEnd, "${blockId.value}_LOCATION"),
                canonicalLocation = null,
                title = SafeSummary(title),
                explanation = SafeSummary(explanation),
                suggestedAction = if (reviewCode == null) null else DecisionAction.ACCEPT_PROPOSED_CHANGE,
                semanticRisk = semanticRisk,
                requiresUserDecision = reviewCode != null,
                status = if (reviewCode == null) FindingStatus.DETECTED else FindingStatus.REVIEW_REQUIRED,
                evidenceSummary = SafeSummary("${blockId.value}_EVIDENCE"),
            ),
            reviewCode = reviewCode,
        )
    }

    private data class TaggedFinding(
        val finding: Finding,
        val reviewCode: TextReviewCode?,
    )

    private class TextIds(private val prefix: String) {
        private var findingIndex = 0

        init {
            FindingId("$prefix-probe")
        }

        fun finding(): FindingId = FindingId("$prefix-f-${findingIndex++}")

        fun decision(code: TextReviewCode): DecisionId = DecisionId("$prefix-d-${code.name.lowercase()}")
    }

    companion object {
        private val TXT_001 = BlockId("TXT-001")
        private val TXT_002 = BlockId("TXT-002")
        private val TXT_003 = BlockId("TXT-003")
        private val TXT_004 = BlockId("TXT-004")
        private val TXT_005 = BlockId("TXT-005")
        private val TXT_006 = BlockId("TXT-006")
        private val TXT_007 = BlockId("TXT-007")
        private val TXT_008 = BlockId("TXT-008")
        private val TXT_009 = BlockId("TXT-009")
        private val TXT_016 = BlockId("TXT-016")

        private val LINE_TERMINATORS = setOf(0x0A, 0x0D, 0x85, 0x2028, 0x2029)
        private val ALLOWED_TEXT_CONTROLS = setOf(0x09, 0x0A, 0x0D)
        private val DASH_VARIANTS = setOf(0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015)
        private val PUNCTUATION_TYPES = setOf(
            UCharacter.CONNECTOR_PUNCTUATION.toInt(),
            UCharacter.DASH_PUNCTUATION.toInt(),
            UCharacter.START_PUNCTUATION.toInt(),
            UCharacter.END_PUNCTUATION.toInt(),
            UCharacter.INITIAL_PUNCTUATION.toInt(),
            UCharacter.FINAL_PUNCTUATION.toInt(),
            UCharacter.OTHER_PUNCTUATION.toInt(),
        )
        private val BACKTICK_CODE = Regex("`[^`\\r\\n]+`")
        private val EMAIL_TOKEN = Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}")
        private val URI_TOKEN = Regex("(?i)https?://[^\\s<>]+")
        private val IDENTIFIER_TOKEN = Regex("(?<![\\p{L}\\p{N}_])[\\p{L}_][\\p{L}\\p{N}_-]*_[\\p{L}\\p{N}_-]+(?![\\p{L}\\p{N}_])")
        private val CRYPTO_TOKEN = Regex("(?<![A-Za-z0-9])[A-Fa-f0-9]{24,}(?![A-Za-z0-9])")
        private val MATH_TOKEN = Regex("(?<!\\w)\\d+(?:\\s*[+×÷=−]\\s*\\d+)+(?!\\w)|(?<!\\w)\\d+–\\d+(?!\\w)")
        private val QUOTED_LITERAL = Regex("\"[^\"\\r\\n]+\"")
        private val LIST_MARKER = Regex("^\\s*(?:[-*+]|[0-9]+[.)])(?=\\s)")

        private fun defaultSpoofChecker(): SpoofChecker = SpoofChecker.Builder()
            .setChecks(SpoofChecker.ALL_CHECKS)
            .setRestrictionLevel(SpoofChecker.RestrictionLevel.MODERATELY_RESTRICTIVE)
            .build()
    }
}

internal fun scriptForCodePoint(codePoint: Int): ScriptCode = when (UScript.getScript(codePoint)) {
    UScript.LATIN -> ScriptCode.LATIN
    UScript.GREEK -> ScriptCode.GREEK
    UScript.CYRILLIC -> ScriptCode.CYRILLIC
    UScript.ARABIC -> ScriptCode.ARABIC
    UScript.HEBREW -> ScriptCode.HEBREW
    UScript.DEVANAGARI -> ScriptCode.DEVANAGARI
    UScript.HAN -> ScriptCode.HAN
    UScript.HANGUL -> ScriptCode.HANGUL
    UScript.HIRAGANA, UScript.KATAKANA -> ScriptCode.KANA
    UScript.COMMON -> ScriptCode.COMMON
    UScript.INHERITED -> ScriptCode.INHERITED
    else -> ScriptCode.OTHER
}

private fun scalarIndexAt(
    scalars: List<UnicodeScalarRecord>,
    utf16Offset: Int,
    endBoundary: Boolean = false,
): Int {
    if (utf16Offset == 0) return 0
    if (utf16Offset >= (scalars.lastOrNull()?.utf16EndExclusive ?: 0)) return scalars.size
    val index = scalars.indexOfFirst {
        if (endBoundary) utf16Offset <= it.utf16EndExclusive else utf16Offset < it.utf16EndExclusive
    }
    return if (index < 0) scalars.size else index + if (endBoundary && utf16Offset == scalars[index].utf16EndExclusive) 1 else 0
}

private fun adjacentCodePoints(text: String, utf16Offset: Int): List<Int> = buildList {
    if (utf16Offset > 0) add(text.codePointBefore(utf16Offset))
    val currentWidth = if (utf16Offset < text.length) Character.charCount(text.codePointAt(utf16Offset)) else 0
    val after = utf16Offset + currentWidth
    if (after < text.length) add(text.codePointAt(after))
}

private fun isBidiControl(codePoint: Int): Boolean = codePoint in setOf(
    0x061C,
    0x200E,
    0x200F,
    0x202A,
    0x202B,
    0x202C,
    0x202D,
    0x202E,
    0x2066,
    0x2067,
    0x2068,
    0x2069,
)

private fun isPunctuationVariant(codePoint: Int): Boolean = codePoint in setOf(
    0x00AB,
    0x00BB,
    0x2010,
    0x2011,
    0x2012,
    0x2013,
    0x2014,
    0x2015,
    0x2018,
    0x2019,
    0x201A,
    0x201B,
    0x201C,
    0x201D,
    0x201E,
    0x201F,
    0x2026,
    0x2212,
    0xFF01,
    0xFF0C,
    0xFF0E,
    0xFF1A,
    0xFF1B,
    0xFF1F,
)
