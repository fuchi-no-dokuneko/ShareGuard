package app.shareguard.block.render

import android.content.Context
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import app.shareguard.core.model.ScriptCode

data class BundledFontResource(
    val familyId: String,
    val version: String,
    val scripts: Set<ScriptCode>,
    @param:FontRes val resourceId: Int,
)

class ResourceBundledFontRegistry(
    private val context: Context,
    resources: Iterable<BundledFontResource>,
) : BundledFontRegistry {
    private val descriptors = resources.toList()

    override fun faces(): List<BundledFontFace> = descriptors.map { descriptor ->
        val typeface = ResourcesCompat.getFont(context, descriptor.resourceId)
            ?: throw RenderException(RenderFailureCode.MISSING_BUNDLED_FONT)
        BundledFontFace(
            familyId = descriptor.familyId,
            version = descriptor.version,
            scripts = descriptor.scripts,
            typeface = typeface,
        )
    }
}
