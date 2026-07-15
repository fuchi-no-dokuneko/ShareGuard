package app.shareguard.block.render

import android.content.Context
import app.shareguard.core.model.ScriptCode

/**
 * The only font set eligible for rebuilt-image output. These are packaged resources, never device
 * font-family names. Unknown scripts therefore stop for review instead of falling back to an OEM font.
 */
fun defaultBundledFontRegistry(context: Context): BundledFontRegistry = ResourceBundledFontRegistry(
    context = context.applicationContext,
    resources = listOf(
        BundledFontResource(
            familyId = "noto-sans",
            version = "snapshot-ffebf8c",
            scripts = setOf(ScriptCode.LATIN, ScriptCode.GREEK, ScriptCode.CYRILLIC),
            resourceId = R.font.noto_sans_regular,
        ),
        BundledFontResource(
            familyId = "noto-naskh-arabic",
            version = "snapshot-ffebf8c",
            scripts = setOf(ScriptCode.ARABIC),
            resourceId = R.font.noto_naskh_arabic_regular,
        ),
        BundledFontResource(
            familyId = "noto-sans-hebrew",
            version = "snapshot-ffebf8c",
            scripts = setOf(ScriptCode.HEBREW),
            resourceId = R.font.noto_sans_hebrew_regular,
        ),
        BundledFontResource(
            familyId = "noto-sans-devanagari",
            version = "snapshot-ffebf8c",
            scripts = setOf(ScriptCode.DEVANAGARI),
            resourceId = R.font.noto_sans_devanagari_regular,
        ),
        BundledFontResource(
            familyId = "noto-sans-cjk-jp",
            version = "2.004",
            scripts = setOf(ScriptCode.HAN, ScriptCode.HANGUL, ScriptCode.KANA),
            resourceId = R.font.noto_sans_cjk_jp_regular,
        ),
    ),
)
