package app.shareguard.block.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.shareguard.core.model.ScriptCode
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledFontAndPngInstrumentedTest {
    @Test
    fun everySupportedScriptUsesPackagedTypefaceWithRepresentativeGlyph() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val faces = defaultBundledFontRegistry(context).faces()
        val samples = mapOf(
            ScriptCode.LATIN to "A",
            ScriptCode.GREEK to "Ω",
            ScriptCode.CYRILLIC to "Ж",
            ScriptCode.ARABIC to "م",
            ScriptCode.HEBREW to "ש",
            ScriptCode.DEVANAGARI to "क",
            ScriptCode.HAN to "漢",
            ScriptCode.HANGUL to "한",
            ScriptCode.KANA to "あ",
        )

        samples.forEach { (script, glyph) ->
            val face = faces.firstOrNull { script in it.scripts }
            assertThat(face).isNotNull()
            assertThat(Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face!!.typeface }.hasGlyph(glyph)).isTrue()
        }
    }

    @Test
    fun maintainedAndroidEncoderProducesOnlyCanonicalOpaquePngEvidence() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(12, 34, 56))
            setHasAlpha(false)
        }

        val (bytes, evidence) = StrictPngSerializer().serializeOpaque(bitmap)

        assertThat(bytes).isNotEmpty()
        assertThat(evidence.colorType).isAnyOf(2, 6)
        assertThat(evidence.chunkTypes.toSet()).containsExactly("IHDR", "IDAT", "IEND")
        assertThat(evidence.opaqueDecodedPixels).isTrue()
        bitmap.recycle()
    }
}
