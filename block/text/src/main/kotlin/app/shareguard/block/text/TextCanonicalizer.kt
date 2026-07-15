package app.shareguard.block.text

import app.shareguard.core.model.BlockId
import app.shareguard.core.model.BlockVersion
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.ChangeEntry
import app.shareguard.core.model.ChangeId
import app.shareguard.core.model.ConfidenceClass
import app.shareguard.core.model.DecisionAction
import app.shareguard.core.model.DecisionStatus
import app.shareguard.core.model.Finding
import app.shareguard.core.model.FindingCategory
import app.shareguard.core.model.FindingId
import app.shareguard.core.model.FindingStatus
import app.shareguard.core.model.ReviewLink
import app.shareguard.core.model.ReviewStatus
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SemanticImpact
import app.shareguard.core.model.SemanticRisk
import app.shareguard.core.model.Severity
import app.shareguard.core.model.SensitiveRepresentation
import app.shareguard.core.model.SourceLocation
import app.shareguard.core.model.UserDecision
import app.shareguard.core.model.toImmutableList
import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UProperty
import com.ibm.icu.text.BreakIterator
import com.ibm.icu.text.Normalizer2
import com.ibm.icu.util.ULocale

class TextCanonicalizer(
    private val inspector: UnicodeTextInspector = UnicodeTextInspector(),
) {
    fun canonicalize(
        input: TextProcessingInput,
        canonicalRevision: CanonicalRevision,
        policy: TextCanonicalizationPolicy = TextCanonicalizationPolicy(),
        approvals: TextReviewApprovals = TextReviewApprovals.none(),
        idPrefix: String = "text",
    ): TextCanonicalizationResult {
        val inspection = inspector.inspect(input, policy.languagePolicy, idPrefix)
        val dynamicReview = detectNormalizationCollision(input, policy, inspection, idPrefix)
        val allFindings = (inspection.findings + listOfNotNull(dynamicReview?.finding)).toMutableList()
        val initialGates = (inspection.reviewGates + listOfNotNull(dynamicReview?.gate)).toList()
        val gateSatisfaction = initialGates.associate { gate ->
            gate.code to gateSatisfied(gate, input, inspection, approvals)
        }
        val gates = initialGates.map { gate ->
            gate.copy(status = if (gateSatisfaction[gate.code] == true) ReviewStatus.APPROVED else ReviewStatus.PENDING)
        }
        val decisions = gates.map { gate -> decisionFor(gate, canonicalRevision) }
        val segments = segmentInput(input.visibleText, inspection.protectedSpans)
        val proposals = mutableListOf<TextChangeProposal>()
        val ledger = mutableListOf<ChangeEntry>()
        var changeIndex = 0
        val transformationsAllowed = inspection.failures.isEmpty()

        fun applyTransform(
            blockId: BlockId,
            reviewCode: TextReviewCode? = null,
            semanticImpact: SemanticImpact = SemanticImpact.NONE,
            transform: (MutableSegment) -> String,
        ) {
            if (!transformationsAllowed) return
            val approved = reviewCode == null || gateSatisfaction[reviewCode] == true
            val findingIds = findingIdsFor(allFindings, blockId, reviewCode, gates)
            segments.filterNot { it.protectedSpan }.forEach { segment ->
                val before = segment.value
                val after = transform(segment)
                if (before == after) return@forEach
                proposals += TextChangeProposal(
                    blockId = blockId,
                    findingIds = findingIds,
                    before = SensitiveRepresentation(before),
                    after = SensitiveRepresentation(after),
                    reviewCode = reviewCode,
                    applied = approved,
                )
                if (approved) {
                    segment.value = after
                    ledger += changeEntry(
                        changeId = ChangeId("$idPrefix-c-${changeIndex++}"),
                        blockId = blockId,
                        canonicalRevision = canonicalRevision,
                        findingIds = findingIds,
                        decision = reviewCode?.let { code -> gates.singleOrNull { it.code == code } },
                        before = SensitiveRepresentation(before),
                        after = SensitiveRepresentation(after),
                        sourceLocation = segment.sourceLocation(input.visibleText),
                        semanticImpact = semanticImpact,
                    )
                }
            }
        }

        if (transformationsAllowed && (input.richRepresentationPresent || input.hiddenAlternativePresent ||
            input.sourceKind in setOf(TextSourceKind.STYLED_TEXT, TextSourceKind.HTML_CLIPBOARD)
            )
        ) {
            val reviewCode = if (input.hiddenAlternativePresent || input.sourceKind == TextSourceKind.HTML_CLIPBOARD) {
                TextReviewCode.VISIBLE_TEXT_LINK_TARGET_REVIEW
            } else {
                null
            }
            val approved = reviewCode == null || gateSatisfaction[reviewCode] == true
            val findingIds = findingIdsFor(allFindings, TXT_016, reviewCode, gates)
            proposals += TextChangeProposal(
                blockId = TXT_016,
                findingIds = findingIds,
                before = null,
                after = null,
                reviewCode = reviewCode,
                applied = approved,
            )
            if (approved) {
                ledger += changeEntry(
                    changeId = ChangeId("$idPrefix-c-${changeIndex++}"),
                    blockId = TXT_016,
                    canonicalRevision = canonicalRevision,
                    findingIds = findingIds,
                    decision = reviewCode?.let { code -> gates.singleOrNull { it.code == code } },
                    before = null,
                    after = null,
                    sourceLocation = null,
                    semanticImpact = if (reviewCode == null) SemanticImpact.NONE else SemanticImpact.POSSIBLE,
                )
            }
        }

        if (input.visualWrapOffsetsUtf16.isNotEmpty()) {
            applyTransform(
                blockId = TXT_015,
                reviewCode = TextReviewCode.VIEWPORT_WRAP_REVIEW,
                semanticImpact = SemanticImpact.POSSIBLE,
            ) { segment ->
                removeApprovedWraps(
                    value = segment.value,
                    segmentOriginalStart = segment.originalStart,
                    approvedOffsets = approvals.approvedVisualWrapOffsetsUtf16,
                )
            }
        }

        val normalizer = when (policy.normalizationForm) {
            TextNormalizationForm.NFC -> Normalizer2.getNFCInstance()
            TextNormalizationForm.NFD -> Normalizer2.getNFDInstance()
            TextNormalizationForm.NFKC -> Normalizer2.getNFKCInstance()
            TextNormalizationForm.NFKD -> Normalizer2.getNFKDInstance()
        }
        val normalizationReview = when {
            dynamicReview != null -> TextReviewCode.NORMALIZATION_COLLISION_REVIEW
            policy.normalizationForm in setOf(TextNormalizationForm.NFKC, TextNormalizationForm.NFKD) &&
                inspection.reviewGates.any { it.code == TextReviewCode.COMPATIBILITY_NORMALIZATION_REVIEW } -> {
                TextReviewCode.COMPATIBILITY_NORMALIZATION_REVIEW
            }
            else -> null
        }
        applyTransform(
            blockId = TXT_010,
            reviewCode = normalizationReview,
            semanticImpact = if (normalizationReview == null) SemanticImpact.NONE else SemanticImpact.POSSIBLE,
            transform = { segment -> normalizeNonWhitespace(segment.value, normalizer) },
        )

        val hasLanguageSensitiveControls = inspection.reviewGates.any {
            it.code == TextReviewCode.LANGUAGE_SENSITIVE_CONTROL_REVIEW
        }
        val hasBidiControls = inspection.reviewGates.any { it.code == TextReviewCode.BIDI_DISPLAY_REVIEW }
        applyTransform(
            blockId = TXT_011,
            reviewCode = when {
                hasBidiControls -> TextReviewCode.BIDI_DISPLAY_REVIEW
                hasLanguageSensitiveControls -> TextReviewCode.LANGUAGE_SENSITIVE_CONTROL_REVIEW
                else -> null
            },
            semanticImpact = if (hasBidiControls || hasLanguageSensitiveControls) {
                SemanticImpact.POSSIBLE
            } else {
                SemanticImpact.NONE
            },
        ) { segment -> removeDisallowedIgnorables(segment.value, policy.preserveEmojiJoiners) }

        if (inspection.tokens.any { it.mixedScript && !it.protectedSpan }) {
            applyTransform(
                blockId = TXT_012,
                reviewCode = TextReviewCode.MIXED_SCRIPT_CONFUSABLE_REVIEW,
                semanticImpact = SemanticImpact.POSSIBLE,
            ) { segment -> replaceReviewedTokens(segment.value, approvals.confusableTokenReplacements) }
        }

        applyTransform(blockId = TXT_013) { segment ->
            val index = segments.indexOf(segment)
            val previousEndsContent = segments.getOrNull(index - 1)?.value?.lastOrNull()?.let { it != '\n' } == true
            val nextStartsContent = segments.getOrNull(index + 1)?.value?.firstOrNull()?.let { it != '\n' } == true
            canonicalizeWhitespace(
                segment.value,
                policy.whitespaceProfile,
                preserveLeadingSeparator = previousEndsContent,
                preserveTrailingSeparator = nextStartsContent,
            )
        }

        val punctuationGate = if (inspection.reviewGates.any {
                it.code == TextReviewCode.PUNCTUATION_SEMANTICS_REVIEW
            }
        ) {
            TextReviewCode.PUNCTUATION_SEMANTICS_REVIEW
        } else {
            null
        }
        applyTransform(
            blockId = TXT_014,
            reviewCode = punctuationGate,
            semanticImpact = if (punctuationGate == null) SemanticImpact.NONE else SemanticImpact.POSSIBLE,
        ) { segment ->
            canonicalizePunctuation(segment.value, policy.punctuationProfile, punctuationGate != null)
        }

        val canonicalText = segments.joinToString(separator = "") { it.value }
        val unresolvedGates = gates.filter { it.blocking && it.status != ReviewStatus.APPROVED }
        val failures = inspection.failures.toMutableList()
        unresolvedGates.forEach { gate ->
            failures += TextFailure(
                code = TextFailureCode.UNRESOLVED_REVIEW,
                blockId = blockForReview(gate.code),
                sourceLocation = null,
            )
        }
        val locked = if (failures.isEmpty()) {
            LockedCanonicalText(canonicalRevision, canonicalText)
        } else {
            null
        }
        val changedFindingIds = ledger.flatMap { entry -> entry.reviewLink?.findingIds.orEmpty() }.toSet()
        allFindings.replaceAll { finding ->
            when {
                finding.findingId in changedFindingIds -> finding.copy(status = FindingStatus.CHANGED)
                gates.any { it.status == ReviewStatus.APPROVED && finding.findingId in it.findingIds } -> {
                    finding.copy(status = FindingStatus.ACCEPTED)
                }
                else -> finding
            }
        }
        return TextCanonicalizationResult(
            canonicalRevision = canonicalRevision,
            canonicalText = canonicalText,
            inspection = inspection,
            findings = allFindings.toImmutableList(),
            decisions = decisions.toImmutableList(),
            proposals = proposals.toImmutableList(),
            ledgerEntries = ledger.toImmutableList(),
            reviewGates = gates.toImmutableList(),
            failures = failures.distinct().toImmutableList(),
            locked = locked,
        )
    }

    private fun gateSatisfied(
        gate: TextReviewGate,
        input: TextProcessingInput,
        inspection: TextInspection,
        approvals: TextReviewApprovals,
    ): Boolean {
        if (!approvals.isApproved(gate.code)) return false
        return when (gate.code) {
            TextReviewCode.MIXED_SCRIPT_CONFUSABLE_REVIEW -> inspection.tokens
                .filter { it.mixedScript && !it.protectedSpan }
                .all { it.token.value in approvals.confusableTokenReplacements }
            TextReviewCode.VIEWPORT_WRAP_REVIEW ->
                input.visualWrapOffsetsUtf16.all { it in approvals.approvedVisualWrapOffsetsUtf16 }
            TextReviewCode.NORMALIZATION_COLLISION_REVIEW ->
                inspection.reviewGates.none { it.code == TextReviewCode.COMPATIBILITY_NORMALIZATION_REVIEW } ||
                    approvals.isApproved(TextReviewCode.COMPATIBILITY_NORMALIZATION_REVIEW)
            TextReviewCode.BIDI_DISPLAY_REVIEW ->
                inspection.reviewGates.none { it.code == TextReviewCode.LANGUAGE_SENSITIVE_CONTROL_REVIEW } ||
                    approvals.isApproved(TextReviewCode.LANGUAGE_SENSITIVE_CONTROL_REVIEW)
            TextReviewCode.MALFORMED_UNICODE_REVIEW -> false
            TextReviewCode.UNSUPPORTED_CONTROL_REVIEW -> false
            else -> true
        }
    }

    private fun decisionFor(
        gate: TextReviewGate,
        canonicalRevision: CanonicalRevision,
    ): UserDecision = UserDecision.create(
        decisionId = gate.decisionId,
        findingIds = gate.findingIds,
        action = when (gate.code) {
            TextReviewCode.CODE_REGION_REVIEW,
            TextReviewCode.MATH_AND_RANGE_PUNCTUATION_REVIEW,
            TextReviewCode.IDENTIFIER_CONFUSABLE_REVIEW,
            -> DecisionAction.RETAIN_SOURCE_MEANING
            else -> DecisionAction.ACCEPT_PROPOSED_CHANGE
        },
        status = if (gate.status == ReviewStatus.APPROVED) DecisionStatus.APPROVED else DecisionStatus.PENDING,
        semanticImpact = if (gate.code in setOf(
                TextReviewCode.CODE_REGION_REVIEW,
                TextReviewCode.MATH_AND_RANGE_PUNCTUATION_REVIEW,
                TextReviewCode.IDENTIFIER_CONFUSABLE_REVIEW,
            )
        ) {
            SemanticImpact.NONE
        } else {
            SemanticImpact.POSSIBLE
        },
        rationale = SafeSummary(gate.code.name),
        canonicalRevision = canonicalRevision.takeIf { gate.status == ReviewStatus.APPROVED },
    )

    private fun detectNormalizationCollision(
        input: TextProcessingInput,
        policy: TextCanonicalizationPolicy,
        inspection: TextInspection,
        idPrefix: String,
    ): DynamicReview? {
        if (policy.normalizationForm !in setOf(TextNormalizationForm.NFKC, TextNormalizationForm.NFKD) ||
            !inspection.isWellFormed
        ) {
            return null
        }
        val normalizer = if (policy.normalizationForm == TextNormalizationForm.NFKC) {
            Normalizer2.getNFKCInstance()
        } else {
            Normalizer2.getNFKDInstance()
        }
        val originalsByNormalized = WORD.findAll(input.visibleText)
            .groupBy({ normalizer.normalize(it.value) }, { it.value })
        if (originalsByNormalized.none { (_, originals) -> originals.distinct().size > 1 }) return null
        val findingId = FindingId("$idPrefix-extra-f-0")
        val finding = Finding(
            findingId = findingId,
            blockId = TXT_010,
            category = FindingCategory.SEMANTIC,
            severity = Severity.HIGH,
            confidenceClass = ConfidenceClass.CERTAIN_BY_PARSER,
            sourceLocation = null,
            canonicalLocation = null,
            title = SafeSummary("NORMALIZATION_IDENTIFIER_COLLISION"),
            explanation = SafeSummary("DISTINCT_IDENTIFIERS_SHARE_NORMALIZED_FORM"),
            suggestedAction = DecisionAction.RETAIN_SOURCE_MEANING,
            semanticRisk = SemanticRisk.HIGH_IMPACT,
            requiresUserDecision = true,
            status = FindingStatus.REVIEW_REQUIRED,
            evidenceSummary = SafeSummary("TXT-010_COLLISION_EVIDENCE"),
        )
        return DynamicReview(
            finding = finding,
            gate = TextReviewGate(
                code = TextReviewCode.NORMALIZATION_COLLISION_REVIEW,
                decisionId = app.shareguard.core.model.DecisionId("$idPrefix-d-normalization_collision_review"),
                findingIds = listOf(findingId).toImmutableList(),
                blocking = true,
                status = ReviewStatus.PENDING,
                summary = SafeSummary("NORMALIZATION_COLLISION_REVIEW"),
            ),
        )
    }

    private fun segmentInput(text: String, protectedSpans: List<ProtectedSpan>): MutableList<MutableSegment> {
        if (protectedSpans.isEmpty()) {
            return mutableListOf(MutableSegment(0, text.length, text, protectedSpan = false))
        }
        val segments = mutableListOf<MutableSegment>()
        var cursor = 0
        protectedSpans.forEach { span ->
            if (cursor < span.startUtf16) {
                segments += MutableSegment(
                    cursor,
                    span.startUtf16 - cursor,
                    text.substring(cursor, span.startUtf16),
                    protectedSpan = false,
                )
            }
            segments += MutableSegment(
                originalStart = span.startUtf16,
                originalLength = span.endUtf16Exclusive - span.startUtf16,
                value = text.substring(span.startUtf16, span.endUtf16Exclusive),
                protectedSpan = true,
            )
            cursor = span.endUtf16Exclusive
        }
        if (cursor < text.length) {
            segments += MutableSegment(cursor, text.length - cursor, text.substring(cursor), protectedSpan = false)
        }
        return segments
    }

    private fun removeApprovedWraps(
        value: String,
        segmentOriginalStart: Int,
        approvedOffsets: Set<Int>,
    ): String = buildString {
        value.forEachIndexed { index, character ->
            val globalOffset = segmentOriginalStart + index
            if (character == '\n' && globalOffset in approvedOffsets) {
                val previous = value.getOrNull(index - 1)
                val next = value.getOrNull(index + 1)
                if (previous != null && next != null && previous !in URL_JOIN_PUNCTUATION && next !in URL_JOIN_PUNCTUATION) {
                    append(' ')
                }
            } else {
                append(character)
            }
        }
    }

    private fun removeDisallowedIgnorables(value: String, preserveEmojiJoiners: Boolean): String = buildString {
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val width = Character.charCount(codePoint)
            val isIgnorable = UCharacter.hasBinaryProperty(codePoint, UProperty.DEFAULT_IGNORABLE_CODE_POINT) ||
                UCharacter.getType(codePoint) == UCharacter.FORMAT.toInt()
            val adjacentEmoji = if (codePoint == 0x200D && preserveEmojiJoiners) {
                val before = if (offset > 0) value.codePointBefore(offset) else null
                val afterOffset = offset + width
                val after = if (afterOffset < value.length) value.codePointAt(afterOffset) else null
                listOfNotNull(before, after).any { UCharacter.hasBinaryProperty(it, UProperty.EMOJI) }
            } else {
                false
            }
            if (!isIgnorable || adjacentEmoji) appendCodePoint(codePoint)
            offset += width
        }
    }

    private fun normalizeNonWhitespace(value: String, normalizer: Normalizer2): String {
        val output = StringBuilder(value.length)
        val pending = StringBuilder()
        fun flush() {
            if (pending.isNotEmpty()) {
                output.append(normalizer.normalize(pending))
                pending.setLength(0)
            }
        }
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (UCharacter.isUWhiteSpace(codePoint)) {
                flush()
                output.appendCodePoint(codePoint)
            } else {
                pending.appendCodePoint(codePoint)
            }
            offset += Character.charCount(codePoint)
        }
        flush()
        return output.toString()
    }

    private fun replaceReviewedTokens(value: String, replacements: Map<String, String>): String {
        val iterator = BreakIterator.getWordInstance(ULocale.ROOT)
        iterator.setText(value)
        val output = StringBuilder(value.length)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val token = value.substring(start, end)
            output.append(replacements[token] ?: token)
            start = end
            end = iterator.next()
        }
        return output.toString()
    }

    private fun canonicalizeWhitespace(
        value: String,
        profile: TextWhitespaceProfile,
        preserveLeadingSeparator: Boolean,
        preserveTrailingSeparator: Boolean,
    ): String {
        if (profile == TextWhitespaceProfile.CODE) return value
        val normalized = StringBuilder(value.length)
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val width = Character.charCount(codePoint)
            when {
                codePoint == '\r'.code -> {
                    val next = offset + width
                    if (next < value.length && value[next] == '\n') offset += 1
                    normalized.append('\n')
                }
                codePoint in setOf(0x85, 0x2028, 0x2029) -> normalized.append('\n')
                codePoint == '\n'.code -> normalized.append('\n')
                UCharacter.isUWhiteSpace(codePoint) -> normalized.append(' ')
                else -> normalized.appendCodePoint(codePoint)
            }
            offset += width
        }
        val output = StringBuilder(normalized.length)
        var pendingSpace = false
        normalized.forEach { character ->
            when (character) {
                ' ' -> pendingSpace = true
                '\n' -> {
                    pendingSpace = false
                    output.append('\n')
                }
                else -> {
                    if (pendingSpace &&
                        (output.isNotEmpty() && output.last() != '\n' || output.isEmpty() && preserveLeadingSeparator)
                    ) {
                        output.append(' ')
                    }
                    pendingSpace = false
                    output.append(character)
                }
            }
        }
        if (pendingSpace && preserveTrailingSeparator && (output.isEmpty() || output.last() != '\n')) {
            output.append(' ')
        }
        return output.toString()
    }

    private fun canonicalizePunctuation(
        value: String,
        profile: TextPunctuationProfile,
        dashReviewApproved: Boolean,
    ): String {
        if (profile == TextPunctuationProfile.PRESERVE_LANGUAGE) return value
        val output = StringBuilder(value.length)
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val width = Character.charCount(codePoint)
            when (codePoint) {
                0x00AB, 0x201C, 0x201D, 0x201E, 0x201F -> output.append('"')
                0x00BB -> output.append('"')
                0x2018, 0x2019, 0x201A, 0x201B -> output.append('\'')
                0x2026 -> output.append("...")
                in DASH_VARIANTS -> if (dashReviewApproved) {
                    while (output.isNotEmpty() && output.last() == ' ') output.deleteCharAt(output.lastIndex)
                    output.append(" - ")
                    var next = offset + width
                    while (next < value.length && value[next] == ' ') next += 1
                    offset = next - width
                } else {
                    output.appendCodePoint(codePoint)
                }
                else -> output.appendCodePoint(codePoint)
            }
            offset += width
        }
        return output.toString()
    }

    private fun changeEntry(
        changeId: ChangeId,
        blockId: BlockId,
        canonicalRevision: CanonicalRevision,
        findingIds: app.shareguard.core.model.ImmutableList<FindingId>,
        decision: TextReviewGate?,
        before: SensitiveRepresentation?,
        after: SensitiveRepresentation?,
        sourceLocation: SourceLocation?,
        semanticImpact: SemanticImpact,
    ): ChangeEntry = ChangeEntry(
        changeId = changeId,
        blockId = blockId,
        blockVersion = BlockVersion(1),
        canonicalRevision = canonicalRevision,
        category = categoryFor(blockId),
        sourceLocation = sourceLocation,
        beforeRepresentation = before,
        afterRepresentation = after,
        reason = SafeSummary("${blockId.value}_CANONICALIZATION"),
        reversibleBeforeExport = true,
        semanticImpact = semanticImpact,
        reviewLink = decision?.let {
            ReviewLink(
                decisionId = it.decisionId,
                findingIds = if (findingIds.isEmpty()) it.findingIds else findingIds,
                status = ReviewStatus.APPROVED,
            )
        },
        verificationId = null,
    )

    private fun findingIdsFor(
        findings: List<Finding>,
        blockId: BlockId,
        reviewCode: TextReviewCode?,
        gates: List<TextReviewGate>,
    ): app.shareguard.core.model.ImmutableList<FindingId> {
        val gateIds = reviewCode?.let { code -> gates.singleOrNull { it.code == code }?.findingIds }
        if (gateIds != null) return gateIds
        return findings.filter { it.blockId == blockId }.map { it.findingId }.toImmutableList()
    }

    private data class DynamicReview(val finding: Finding, val gate: TextReviewGate)

    private data class MutableSegment(
        val originalStart: Int,
        val originalLength: Int,
        var value: String,
        val protectedSpan: Boolean,
    ) {
        fun sourceLocation(original: String): SourceLocation {
            val scalarStart = original.codePointCount(0, originalStart)
            val end = minOf(original.length, originalStart + originalLength)
            return textSourceLocation(
                scalarStart = scalarStart,
                scalarEndExclusive = scalarStart + original.codePointCount(originalStart, end),
                code = "TEXT_TRANSFORM_LOCATION",
            )
        }
    }

    companion object {
        private val TXT_010 = BlockId("TXT-010")
        private val TXT_011 = BlockId("TXT-011")
        private val TXT_012 = BlockId("TXT-012")
        private val TXT_013 = BlockId("TXT-013")
        private val TXT_014 = BlockId("TXT-014")
        private val TXT_015 = BlockId("TXT-015")
        private val TXT_016 = BlockId("TXT-016")
        private val DASH_VARIANTS = setOf(0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015)
        private val URL_JOIN_PUNCTUATION = setOf('/', '?', '&', '=', '#', '-', '_', '.')
        private val WORD = Regex("[\\p{L}\\p{N}_-]+")

        private fun categoryFor(blockId: BlockId): FindingCategory = when (blockId) {
            TXT_010, TXT_011, TXT_013 -> FindingCategory.UNICODE
            TXT_012 -> FindingCategory.CONFUSABLE
            TXT_014 -> FindingCategory.PUNCTUATION
            TXT_015 -> FindingCategory.LAYOUT
            TXT_016 -> FindingCategory.SEMANTIC
            else -> FindingCategory.UNKNOWN
        }

        private fun blockForReview(code: TextReviewCode): BlockId = when (code) {
            TextReviewCode.MALFORMED_UNICODE_REVIEW -> BlockId("TXT-001")
            TextReviewCode.UNSUPPORTED_CONTROL_REVIEW -> BlockId("TXT-001")
            TextReviewCode.BIDI_DISPLAY_REVIEW,
            TextReviewCode.LANGUAGE_SENSITIVE_CONTROL_REVIEW,
            -> BlockId("TXT-003")
            TextReviewCode.MIXED_SCRIPT_CONFUSABLE_REVIEW,
            TextReviewCode.IDENTIFIER_CONFUSABLE_REVIEW,
            -> BlockId("TXT-005")
            TextReviewCode.PUNCTUATION_SEMANTICS_REVIEW,
            TextReviewCode.MATH_AND_RANGE_PUNCTUATION_REVIEW,
            -> BlockId("TXT-006")
            TextReviewCode.CODE_REGION_REVIEW -> BlockId("TXT-009")
            TextReviewCode.COMPATIBILITY_NORMALIZATION_REVIEW,
            TextReviewCode.NORMALIZATION_COLLISION_REVIEW,
            -> BlockId("TXT-010")
            TextReviewCode.VIEWPORT_WRAP_REVIEW -> BlockId("TXT-015")
            TextReviewCode.VISIBLE_TEXT_LINK_TARGET_REVIEW -> BlockId("TXT-016")
            TextReviewCode.LANGUAGE_POLICY_REVIEW -> BlockId("TXT-008")
        }
    }
}
