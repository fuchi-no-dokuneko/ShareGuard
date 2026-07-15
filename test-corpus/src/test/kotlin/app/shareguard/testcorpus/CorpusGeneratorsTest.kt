package app.shareguard.testcorpus

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CorpusGeneratorsTest {
    @Test
    fun unicodeAndPunctuationGeneratorsProduceDistinctDeterministicFamilies() {
        assertThat(CorpusGenerators.zeroWidthVariants("canon", 2))
            .isEqualTo(CorpusGenerators.zeroWidthVariants("canon", 2))
        assertThat(CorpusGenerators.zeroWidthVariants("canon", 2).map { it.value }.toSet()).hasSize(3)
        assertThat(CorpusGenerators.unicodeSpaceVariants("left", "right").map { it.value }.toSet()).hasSize(5)
        assertThat(CorpusGenerators.newlineVariants(listOf("one", "two")).map { it.value }.toSet()).hasSize(3)
        assertThat(CorpusGenerators.combiningMarkVariants("Café").map { it.value }.toSet()).hasSize(2)
        assertThat(CorpusGenerators.punctuationVariants().map { it.value }.toSet()).hasSize(3)
    }

    @Test
    fun urlAndMetadataGeneratorsAreDeterministicAndKeepSourceMarkersInspectable() {
        val tracking = CorpusGenerators.knownTrackingUrlVariants("https://example.com/article")
        assertThat(tracking).isEqualTo(CorpusGenerators.knownTrackingUrlVariants("https://example.com/article"))
        assertThat(tracking).hasSize(3)
        assertThat(tracking.all { "utm_" in it.value }).isTrue()

        val encoded = CorpusGenerators.percentEncodingVariants("https://example.com", "USER-7F2")
        assertThat(encoded).hasSize(3)
        assertThat(encoded.any { "%55%53%45%52" in it.value }).isTrue()

        val metadata = CorpusGenerators.metadataVariants("same visible content")
        assertThat(metadata.map { it.value }.toSet()).containsExactly("same visible content")
        assertThat(metadata.flatMap { it.attributes.keys })
            .containsAtLeast("sourceFilename", "metadataId", "sourceUri")
    }
}
