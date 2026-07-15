package app.shareguard.block.image

import androidx.exifinterface.media.ExifInterface
import app.shareguard.core.model.SafeSummary
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Directory
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream

enum class MetadataFamily {
    EXIF,
    XMP,
    IPTC,
    ICC_PROFILE,
    EMBEDDED_THUMBNAIL,
    TEXT,
    APPLICATION_STRUCTURE,
    ENCODER_STRUCTURE,
    FORMAT_STRUCTURE,
    UNKNOWN,
}

enum class ThumbnailPolicy { DISCARD, RETAIN_WITH_EXPLICIT_APPROVAL }

data class MetadataTagInventory(
    /** A schema identifier supplied by the maintained parser; never a source metadata value. */
    val schemaIdentifier: String,
    val numericTagType: Int,
) {
    init {
        require(schemaIdentifier.matches(Regex("[A-Z0-9_?]{1,96}")))
    }
}

data class MetadataDirectoryInventory(
    val schemaIdentifier: String,
    val family: MetadataFamily,
    val tags: List<MetadataTagInventory>,
    val parserErrorCount: Int,
) {
    init {
        require(schemaIdentifier.matches(Regex("[A-Z0-9_?]{1,96}")))
        require(parserErrorCount >= 0)
    }
}

data class MetadataInventoryResult(
    val directories: List<MetadataDirectoryInventory>,
    val complete: Boolean,
    val parserFailed: Boolean,
    val profileDeclared: Boolean,
    val thumbnailDeclared: Boolean,
    val thumbnailUnreadable: Boolean,
    val thumbnailPolicy: ThumbnailPolicy,
    val orientation: ExifOrientation,
) {
    init {
        if (thumbnailPolicy == ThumbnailPolicy.RETAIN_WITH_EXPLICIT_APPROVAL) {
            require(thumbnailDeclared && !thumbnailUnreadable)
        }
    }
}

data class MetadataInventoryPolicy(
    val maximumDirectories: Int,
    val maximumTags: Int,
    val thumbnailPolicy: ThumbnailPolicy = ThumbnailPolicy.DISCARD,
    val thumbnailRetentionApproved: Boolean = false,
    val validationReference: SafeSummary,
) {
    init {
        require(maximumDirectories in 1..16_384)
        require(maximumTags in 1..262_144)
        require(validationReference.value.isNotBlank())
        if (thumbnailPolicy == ThumbnailPolicy.RETAIN_WITH_EXPLICIT_APPROVAL) {
            require(thumbnailRetentionApproved) { "Thumbnail retention requires explicit approval" }
        }
    }
}

interface MetadataInputSource : Closeable {
    val length: Long
    fun openStream(): InputStream
}

class ByteArrayMetadataInputSource(bytes: ByteArray) : MetadataInputSource {
    private val storage = bytes.copyOf()
    private var closed = false

    override val length: Long
        get() = storage.size.toLong()

    override fun openStream(): InputStream {
        check(!closed) { "Metadata source is closed" }
        return ByteArrayInputStream(storage)
    }

    override fun close() {
        if (!closed) {
            storage.fill(0)
            closed = true
        }
    }

    internal fun isZeroizedForTest(): Boolean = storage.all { it == 0.toByte() }
}

/**
 * Enumerates every parser directory and tag, including unknown structures, without copying source values into
 * diagnostics or the canonical model. The parser object remains local to this call.
 */
class MaintainedMetadataInventory {
    fun inspect(source: MetadataInputSource, policy: MetadataInventoryPolicy): MetadataInventoryResult {
        try {
            val orientation = readOrientation(source)
            val metadata = try {
                source.openStream().use { ImageMetadataReader.readMetadata(it, source.length) }
            } catch (_: Exception) {
                return MetadataInventoryResult(
                    directories = emptyList(),
                    complete = false,
                    parserFailed = true,
                    profileDeclared = false,
                    thumbnailDeclared = false,
                    thumbnailUnreadable = false,
                    thumbnailPolicy = ThumbnailPolicy.DISCARD,
                    orientation = orientation,
                )
            }

            val inventories = mutableListOf<MetadataDirectoryInventory>()
            var totalTags = 0
            var complete = true
            var thumbnailDeclared = false
            var thumbnailUnreadable = false
            for (directory in metadata.directories) {
                if (inventories.size >= policy.maximumDirectories) {
                    complete = false
                    break
                }
                val family = classify(directory)
                val tags = mutableListOf<MetadataTagInventory>()
                for (tag in directory.tags) {
                    if (totalTags >= policy.maximumTags) {
                        complete = false
                        break
                    }
                    tags += MetadataTagInventory(schemaName(tag.tagName, tag.tagType), tag.tagType)
                    totalTags++
                }
                if (family == MetadataFamily.EMBEDDED_THUMBNAIL) {
                    thumbnailDeclared = true
                    if (directory.hasErrors()) thumbnailUnreadable = true
                }
                inventories += MetadataDirectoryInventory(
                    schemaIdentifier = schemaName(directory.name, null),
                    family = family,
                    tags = tags,
                    parserErrorCount = directory.errorCount,
                )
                if (!complete) break
            }

            // Never silently retain a thumbnail that could not be independently read and inventoried.
            val effectiveThumbnailPolicy = if (
                policy.thumbnailPolicy == ThumbnailPolicy.RETAIN_WITH_EXPLICIT_APPROVAL &&
                thumbnailDeclared && !thumbnailUnreadable && complete
            ) policy.thumbnailPolicy else ThumbnailPolicy.DISCARD

            return MetadataInventoryResult(
                directories = inventories,
                complete = complete,
                parserFailed = false,
                profileDeclared = inventories.any { it.family == MetadataFamily.ICC_PROFILE },
                thumbnailDeclared = thumbnailDeclared,
                thumbnailUnreadable = thumbnailUnreadable,
                thumbnailPolicy = effectiveThumbnailPolicy,
                orientation = orientation,
            )
        } finally {
            source.close()
        }
    }

    private fun readOrientation(source: MetadataInputSource): ExifOrientation = try {
        source.openStream().use { stream ->
            ExifOrientation.fromExifValue(
                ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
            )
        }
    } catch (_: Exception) {
        ExifOrientation.NORMAL
    }

    private fun classify(directory: Directory): MetadataFamily {
        val type = directory.javaClass.name.lowercase()
        return when {
            "thumbnail" in type -> MetadataFamily.EMBEDDED_THUMBNAIL
            ".exif." in type || type.endsWith("exifdirectory") -> MetadataFamily.EXIF
            ".xmp." in type -> MetadataFamily.XMP
            ".iptc." in type -> MetadataFamily.IPTC
            ".icc." in type -> MetadataFamily.ICC_PROFILE
            "comment" in type || "text" in type -> MetadataFamily.TEXT
            "photoshop" in type || "adobe" in type || "ducky" in type -> MetadataFamily.APPLICATION_STRUCTURE
            "jfif" in type || "huffman" in type -> MetadataFamily.ENCODER_STRUCTURE
            type.startsWith("com.drew.metadata.") -> MetadataFamily.FORMAT_STRUCTURE
            else -> MetadataFamily.UNKNOWN
        }
    }

    private fun schemaName(value: String?, numericTagType: Int?): String {
        val sanitized = value.orEmpty().uppercase()
            .replace(Regex("[^A-Z0-9]+"), "_")
            .trim('_')
            .take(80)
        if (sanitized.isNotEmpty()) return sanitized
        return numericTagType?.let { "TAG_${it.toUInt().toString(16).uppercase()}" } ?: "UNKNOWN"
    }
}
