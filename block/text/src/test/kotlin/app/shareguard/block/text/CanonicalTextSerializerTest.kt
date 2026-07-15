package app.shareguard.block.text

import app.shareguard.core.model.CanonicalBlock
import app.shareguard.core.model.CanonicalBlockId
import app.shareguard.core.model.CanonicalDocument
import app.shareguard.core.model.CanonicalListItem
import app.shareguard.core.model.CanonicalRevision
import app.shareguard.core.model.ChangeLedgerId
import app.shareguard.core.model.CodeBlock
import app.shareguard.core.model.ConfidenceClass
import app.shareguard.core.model.LayoutElement
import app.shareguard.core.model.LayoutKind
import app.shareguard.core.model.LayoutModel
import app.shareguard.core.model.LinkBlock
import app.shareguard.core.model.ListBlock
import app.shareguard.core.model.ParagraphBlock
import app.shareguard.core.model.QuoteBlock
import app.shareguard.core.model.ReadingOrder
import app.shareguard.core.model.SchemaVersion
import app.shareguard.core.model.SemanticRole
import app.shareguard.core.model.SourceDependencyMap
import app.shareguard.core.model.TableCell
import app.shareguard.core.model.TableLikeBlock
import app.shareguard.core.model.TableRow
import app.shareguard.core.model.TextRun
import app.shareguard.core.model.UrlComponents
import app.shareguard.core.model.UrlPolicy
import app.shareguard.core.model.UrlToken
import app.shareguard.core.model.UrlTokenId
import app.shareguard.core.model.immutableListOf
import app.shareguard.core.model.toImmutableList
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class CanonicalTextSerializerTest {
    private val serializer = CanonicalTextSerializer()

    @Test
    fun structuralSerialization_isDeterministicAndFresh() {
        val document = document()
        val first = serializer.serialize(document, document.revision)
        val second = serializer.serialize(document, document.revision)

        assertThat(first).isEqualTo(second)
        assertThat(first.text).isEqualTo(
            "Paragraph\n\n" +
                "1. First\n2. Second\n\n" +
                "> Quoted\n\n" +
                "if (x) {\n  run();\n}\n\n" +
                "A\tB\n\n" +
                "Help (https://example.com/help)",
        )
        assertThat(serializer.reparse(first.text, CanonicalNewlineProfile.LF)).isEqualTo(first.text)
    }

    @Test
    fun crlfProfile_roundTripsToCanonicalLf() {
        val document = document()
        val result = serializer.serialize(
            document,
            document.revision,
            CanonicalTextSerializerOptions(newlineProfile = CanonicalNewlineProfile.CRLF),
        )

        assertThat(result.text).contains("\r\n")
        assertThat(serializer.reparse(result.text, CanonicalNewlineProfile.CRLF))
            .doesNotContain("\r")
    }

    @Test
    fun serializer_rejectsRevisionDriftAndMalformedScalars() {
        val document = document()
        assertThrows(IllegalArgumentException::class.java) {
            serializer.serialize(document, CanonicalRevision(2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            serializer.reparse("\uD800", CanonicalNewlineProfile.LF)
        }
    }

    private fun document(): CanonicalDocument {
        val revision = CanonicalRevision(1)
        val blocks: List<CanonicalBlock> = listOf(
            ParagraphBlock(id("paragraph"), immutableListOf(run("Paragraph"))),
            ListBlock(
                id("list"),
                ordered = true,
                items = immutableListOf(
                    CanonicalListItem(immutableListOf(run("First"))),
                    CanonicalListItem(immutableListOf(run("Second"))),
                ),
            ),
            QuoteBlock(id("quote"), immutableListOf(run("Quoted"))),
            CodeBlock(id("code"), "if (x) {\n  run();\n}"),
            TableLikeBlock(
                id("table"),
                immutableListOf(
                    TableRow(
                        immutableListOf(
                            TableCell(immutableListOf(run("A"))),
                            TableCell(immutableListOf(run("B"))),
                        ),
                    ),
                ),
            ),
            LinkBlock(id("link"), UrlTokenId("url-help"), immutableListOf(run("Help"))),
        )
        return CanonicalDocument(
            schemaVersion = SchemaVersion(1),
            revision = revision,
            declaredLanguagePolicy = app.shareguard.core.model.LanguagePolicy.create(),
            rootBlocks = blocks.toImmutableList(),
            urlTokens = immutableListOf(
                UrlToken(
                    tokenId = UrlTokenId("url-help"),
                    originalReference = null,
                    displayText = "Help",
                    parsedComponents = UrlComponents.create(
                        scheme = "https",
                        host = "example.com",
                        registrableDomain = "example.com",
                        pathSegments = listOf("help"),
                    ),
                    normalizedComponents = UrlComponents.create(
                        scheme = "https",
                        host = "example.com",
                        registrableDomain = "example.com",
                        pathSegments = listOf("help"),
                    ),
                    chosenPolicy = UrlPolicy.KEEP_FULL,
                    finalText = "https://example.com/help",
                    functionalityWarning = null,
                    userApproved = false,
                ),
            ),
            imageRegions = immutableListOf(),
            layoutModel = LayoutModel(
                LayoutKind.PLAIN_DOCUMENT,
                blocks.mapIndexed { index, block -> LayoutElement(block.id, null, index) }.toImmutableList(),
            ),
            readingOrder = ReadingOrder(blocks.map { it.id }.toImmutableList()),
            userDecisions = immutableListOf(),
            sourceDependencyMap = SourceDependencyMap.create(revision),
            changeLedgerReference = ChangeLedgerId("ledger-text"),
        )
    }

    private fun id(value: String): CanonicalBlockId = CanonicalBlockId(value)

    private fun run(value: String): TextRun = TextRun.create(
        canonicalText = value,
        semanticRole = SemanticRole.BODY,
        confidenceClass = ConfidenceClass.CERTAIN_BY_PARSER,
    )
}
