package app.shareguard.block.render

import android.graphics.Paint
import app.shareguard.core.model.ScriptCode
import java.text.BreakIterator
import java.util.Locale

internal fun interface GlyphCoverageChecker {
    fun hasGlyph(face: BundledFontFace, grapheme: String): Boolean
}

private object AndroidPaintGlyphCoverageChecker : GlyphCoverageChecker {
    override fun hasGlyph(face: BundledFontFace, grapheme: String): Boolean =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face.typeface }.hasGlyph(grapheme)
}

internal class BundledFontResolver(
    registry: BundledFontRegistry,
    private val glyphCoverageChecker: GlyphCoverageChecker = AndroidPaintGlyphCoverageChecker,
) {
    private val faces = registry.faces().toList()

    init {
        require(faces.isNotEmpty()) { "At least one bundled font must be configured" }
        require(faces.map { it.familyId }.distinct().size == faces.size) {
            "Bundled font identifiers must be unique"
        }
    }

    fun resolve(text: String, declaredScripts: Set<ScriptCode> = emptySet()): ResolvedFontRun {
        val runs = resolveRuns(text, declaredScripts)
        if (runs.size != 1) throw RenderException(RenderFailureCode.MISSING_BUNDLED_FONT)
        return runs.single()
    }

    fun resolveRuns(text: String, declaredScripts: Set<ScriptCode> = emptySet()): List<ResolvedFontRun> {
        validateCanonicalControls(text)
        if (text.isEmpty()) return emptyList()
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val resolved = mutableListOf<ResolvedFontRun>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val grapheme = text.substring(start, end)
            val scripts = inferScripts(grapheme).filterNot {
                it == ScriptCode.COMMON || it == ScriptCode.INHERITED
            }.toSet()
            val requiredScripts = if (scripts.isEmpty()) {
                declaredScripts.filterNot { it == ScriptCode.COMMON || it == ScriptCode.INHERITED }.toSet()
            } else {
                scripts
            }
            val candidates = faces.filter { face ->
                requiredScripts.isEmpty() || face.scripts.containsAll(requiredScripts)
            }
            val face = candidates.firstOrNull { hasEveryGlyph(it, grapheme) }
                ?: if (candidates.isEmpty()) {
                    throw RenderException(RenderFailureCode.MISSING_BUNDLED_FONT)
                } else {
                    throw RenderException(RenderFailureCode.MISSING_BUNDLED_GLYPH)
                }
            val prior = resolved.lastOrNull()
            if (prior?.face?.familyId == face.familyId) {
                resolved[resolved.lastIndex] = prior.copy(text = prior.text + grapheme)
            } else {
                resolved += ResolvedFontRun(grapheme, face)
            }
            start = end
            end = iterator.next()
        }
        return resolved
    }

    private fun hasEveryGlyph(face: BundledFontFace, text: String): Boolean {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val grapheme = text.substring(start, end)
            if (!grapheme.all(::isLayoutWhitespace) && !glyphCoverageChecker.hasGlyph(face, grapheme)) return false
            start = end
            end = iterator.next()
        }
        return true
    }

    private fun inferScripts(text: String): Set<ScriptCode> = buildSet {
        text.codePoints().forEach { scalar ->
            val script = when (Character.UnicodeScript.of(scalar)) {
                Character.UnicodeScript.LATIN -> ScriptCode.LATIN
                Character.UnicodeScript.GREEK -> ScriptCode.GREEK
                Character.UnicodeScript.CYRILLIC -> ScriptCode.CYRILLIC
                Character.UnicodeScript.ARABIC -> ScriptCode.ARABIC
                Character.UnicodeScript.HEBREW -> ScriptCode.HEBREW
                Character.UnicodeScript.DEVANAGARI -> ScriptCode.DEVANAGARI
                Character.UnicodeScript.HAN -> ScriptCode.HAN
                Character.UnicodeScript.HANGUL -> ScriptCode.HANGUL
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> ScriptCode.KANA
                Character.UnicodeScript.COMMON -> ScriptCode.COMMON
                Character.UnicodeScript.INHERITED -> ScriptCode.INHERITED
                else -> ScriptCode.OTHER
            }
            add(script)
        }
    }

    private fun validateCanonicalControls(text: String) {
        text.codePoints().forEach { scalar ->
            val allowedLayoutControl = scalar == '\n'.code || scalar == '\t'.code || scalar == '\r'.code
            if (!allowedLayoutControl && Character.getType(scalar) == Character.CONTROL.toInt()) {
                throw RenderException(RenderFailureCode.INVALID_CANONICAL_CONTROL)
            }
            if (Character.isIdentifierIgnorable(scalar)) {
                throw RenderException(RenderFailureCode.INVALID_CANONICAL_CONTROL)
            }
        }
    }

    private fun isLayoutWhitespace(character: Char): Boolean =
        character == '\n' || character == '\r' || character == '\t' || character == ' '
}
