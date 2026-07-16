package app.shareguard.core.ui

import app.shareguard.core.model.AssuranceClass
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClaimLanguageTest {
    @Test
    fun assuranceLabelsAreScopedEvidenceNotAbsoluteClaims() {
        val labels = AssuranceClass.entries.map(ClaimLanguage::assuranceLabel)
        labels.forEach { label ->
            assertThat(label.lowercase()).doesNotContain("safe")
            assertThat(label.lowercase()).doesNotContain("anonymous")
            assertThat(label.lowercase()).doesNotContain("watermark removed")
        }
    }

    @Test
    fun elapsedFormattingNeverReturnsNegativeText() {
        assertThat(formatElapsedMillis(-9_000)).isEqualTo("less than a minute")
        assertThat(formatElapsedMillis(3_661_000)).isEqualTo("1 h 1 min")
        assertThat(formatElapsedMillis((2 * 24L + 3L) * 3_600_000L)).isEqualTo("2 d 3 h")
    }

    @Test
    fun externalCopyAndDeletionLimitsRemainExplicit() {
        assertThat(ClaimLanguage.MANAGED_BOUNDARY).contains("not monitored")
        assertThat(ClaimLanguage.DELETE_LIMIT).contains("does not claim physical flash erasure")
        assertThat(ClaimLanguage.TIMER_LIMIT).contains("not a security guarantee")
    }
}
