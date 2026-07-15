package app.shareguard.block.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import app.shareguard.core.model.ScriptCode

internal data class CanonicalTextSegment(
    val text: String,
    val scripts: Set<ScriptCode> = emptySet(),
)

internal class PreparedTextLayout(
    private val layout: StaticLayout,
    val fontFamilies: Set<String>,
) {
    val height: Int get() = layout.height

    fun draw(canvas: Canvas, left: Float, top: Float) {
        val save = canvas.save()
        try {
            canvas.translate(left, top)
            layout.draw(canvas)
        } finally {
            canvas.restoreToCount(save)
        }
    }
}

internal class CanonicalTextLayoutEngine(
    private val resolver: BundledFontResolver,
) {
    fun prepare(
        segments: List<CanonicalTextSegment>,
        widthPx: Int,
        textSizePx: Float,
        foregroundColor: Int,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    ): PreparedTextLayout {
        if (widthPx <= 0 || !textSizePx.isFinite() || textSizePx <= 0f) {
            throw RenderException(RenderFailureCode.INVALID_RESOURCE_PLAN)
        }
        val styled = SpannableStringBuilder()
        val families = linkedSetOf<String>()
        segments.forEach { segment ->
            val resolvedRuns = resolver.resolveRuns(segment.text, segment.scripts)
            resolvedRuns.forEach { run ->
                val start = styled.length
                styled.append(run.text)
                styled.setSpan(
                    ExactTypefaceSpan(run.face.typeface),
                    start,
                    styled.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                families += "${run.face.familyId}@${run.face.version}"
            }
        }
        if (styled.isEmpty()) styled.append(" ")
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foregroundColor
            textSize = textSizePx
            isSubpixelText = false
            isLinearText = false
            letterSpacing = 0f
        }
        return try {
            val builder = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, widthPx)
                .setAlignment(alignment)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            if (Build.VERSION.SDK_INT >= 26) builder.setJustificationMode(Layout.JUSTIFICATION_MODE_NONE)
            PreparedTextLayout(builder.build(), families)
        } catch (_: RuntimeException) {
            throw RenderException(RenderFailureCode.TEXT_SHAPING_FAILED)
        }
    }
}

private class ExactTypefaceSpan(
    private val exactTypeface: Typeface,
) : MetricAffectingSpan() {
    override fun updateDrawState(textPaint: TextPaint) = applyTypeface(textPaint)

    override fun updateMeasureState(textPaint: TextPaint) = applyTypeface(textPaint)

    private fun applyTypeface(paint: Paint) {
        paint.typeface = exactTypeface
        paint.isFakeBoldText = false
        paint.textSkewX = 0f
    }
}
