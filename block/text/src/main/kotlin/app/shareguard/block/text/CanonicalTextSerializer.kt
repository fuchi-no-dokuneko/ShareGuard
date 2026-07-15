package app.shareguard.block.text

import app.shareguard.core.model.CanonicalBlock
import app.shareguard.core.model.CanonicalDocument
import app.shareguard.core.model.CanonicalListItem
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.CodeBlock
import app.shareguard.core.model.GenericUiBlock
import app.shareguard.core.model.HeadingBlock
import app.shareguard.core.model.ImagePlaceholderBlock
import app.shareguard.core.model.LinkBlock
import app.shareguard.core.model.ListBlock
import app.shareguard.core.model.MessageBlock
import app.shareguard.core.model.MessageRole
import app.shareguard.core.model.ParagraphBlock
import app.shareguard.core.model.QuoteBlock
import app.shareguard.core.model.RedactionBlock
import app.shareguard.core.model.TableCell
import app.shareguard.core.model.TableLikeBlock
import app.shareguard.core.model.TextRun
import app.shareguard.core.model.UnknownRegionBlock
import app.shareguard.core.model.UrlToken

enum class CanonicalNewlineProfile { LF, CRLF }

data class CanonicalTextSerialization(
    val canonicalRevision: CanonicalRevision,
    val text: String,
    val scalarCount: Int,
)

data class CanonicalTextSerializerOptions(
    val newlineProfile: CanonicalNewlineProfile = CanonicalNewlineProfile.LF,
    val includeMessageRoleLabels: Boolean = false,
    val includeImageLabels: Boolean = true,
)

/** Fresh, deterministic plain-text serialization of an already reviewed Canonical Document revision. */
class CanonicalTextSerializer {
    fun serialize(
        document: CanonicalDocument,
        lockedRevision: CanonicalRevision,
        options: CanonicalTextSerializerOptions = CanonicalTextSerializerOptions(),
    ): CanonicalTextSerialization {
        require(document.revision == lockedRevision) { TextFailureCode.CANONICAL_REVISION_MISMATCH.name }
        val blocksById = document.rootBlocks.associateBy { it.id }
        val urlsById = document.urlTokens.associateBy { it.tokenId }
        val canonicalLf = document.readingOrder.blockIds.joinToString(separator = "\n\n") { blockId ->
            val block = blocksById[blockId] ?: error("CANONICAL_BLOCK_REFERENCE_MISMATCH")
            serializeBlock(block, urlsById, options)
        }
        validateScalarSequence(canonicalLf)
        val serialized = when (options.newlineProfile) {
            CanonicalNewlineProfile.LF -> canonicalLf
            CanonicalNewlineProfile.CRLF -> canonicalLf.replace("\n", "\r\n")
        }
        val reparsed = reparse(serialized, options.newlineProfile)
        check(reparsed == canonicalLf) { TextFailureCode.SERIALIZER_SCALAR_MISMATCH.name }
        return CanonicalTextSerialization(
            canonicalRevision = lockedRevision,
            text = serialized,
            scalarCount = canonicalLf.codePointCount(0, canonicalLf.length),
        )
    }

    fun reparse(serialized: String, newlineProfile: CanonicalNewlineProfile): String {
        validateScalarSequence(serialized)
        return when (newlineProfile) {
            CanonicalNewlineProfile.LF -> {
                require(!serialized.contains('\r')) { TextFailureCode.SERIALIZER_SCALAR_MISMATCH.name }
                serialized
            }
            CanonicalNewlineProfile.CRLF -> {
                var index = 0
                while (index < serialized.length) {
                    if (serialized[index] == '\r') {
                        require(index + 1 < serialized.length && serialized[index + 1] == '\n') {
                            TextFailureCode.SERIALIZER_SCALAR_MISMATCH.name
                        }
                        index += 2
                    } else {
                        require(serialized[index] != '\n') { TextFailureCode.SERIALIZER_SCALAR_MISMATCH.name }
                        index += 1
                    }
                }
                serialized.replace("\r\n", "\n")
            }
        }
    }

    private fun serializeBlock(
        block: CanonicalBlock,
        urlsById: Map<app.shareguard.core.model.UrlTokenId, UrlToken>,
        options: CanonicalTextSerializerOptions,
    ): String = when (block) {
        is ParagraphBlock -> block.runs.text()
        is HeadingBlock -> block.runs.text()
        is ListBlock -> block.items.mapIndexed { index, item ->
            val marker = if (block.ordered) "${index + 1}. " else "- "
            marker + item.text()
        }.joinToString("\n")
        is QuoteBlock -> block.runs.text().lineSequence().joinToString("\n") { "> $it" }
        is MessageBlock -> {
            val text = block.runs.text()
            if (options.includeMessageRoleLabels) "${block.role.plainLabel()}: $text" else text
        }
        is CodeBlock -> block.code
        is TableLikeBlock -> block.rows.joinToString("\n") { row ->
            row.cells.joinToString("\t") { it.text() }
        }
        is LinkBlock -> {
            val display = block.displayRuns.text()
            val target = urlsById[block.urlTokenId]?.finalText ?: error("URL_TOKEN_REFERENCE_MISMATCH")
            when {
                target.isBlank() -> display
                display.isBlank() || display == target -> target
                else -> "$display ($target)"
            }
        }
        is ImagePlaceholderBlock -> if (options.includeImageLabels) {
            block.label?.let { "[Image: $it]" } ?: "[Image]"
        } else {
            ""
        }
        is RedactionBlock -> if (options.includeImageLabels) {
            block.label?.let { "[Redacted: $it]" } ?: "[Redacted]"
        } else {
            ""
        }
        is GenericUiBlock -> block.runs.text()
        is UnknownRegionBlock -> "[Unknown region]"
    }

    private fun validateScalarSequence(value: String) {
        require(!value.contains('\u0000')) { TextFailureCode.SERIALIZER_UNSUPPORTED_SCALAR.name }
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                character.isHighSurrogate() -> {
                    require(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                        TextFailureCode.SERIALIZER_UNSUPPORTED_SCALAR.name
                    }
                    index += 2
                }
                character.isLowSurrogate() -> throw IllegalArgumentException(
                    TextFailureCode.SERIALIZER_UNSUPPORTED_SCALAR.name,
                )
                else -> index += 1
            }
        }
    }
}

private fun List<TextRun>.text(): String = joinToString(separator = "") { it.canonicalText }

private fun CanonicalListItem.text(): String = runs.text()

private fun TableCell.text(): String = runs.text()

private fun MessageRole.plainLabel(): String = when (this) {
    MessageRole.AUTHOR -> "Author"
    MessageRole.RECIPIENT -> "Recipient"
    MessageRole.SYSTEM -> "System"
    MessageRole.UNKNOWN -> "Message"
}
