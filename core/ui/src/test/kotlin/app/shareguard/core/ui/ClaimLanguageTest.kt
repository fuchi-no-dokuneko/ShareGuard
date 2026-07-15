package app.shareguard.core.ui

import app.shareguard.core.model.AssuranceClass
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration

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
        assertThat(formatElapsed(Duration.ofSeconds(-9))).isEqualTo("less than a minute")
        assertThat(formatElapsed(Duration.ofSeconds(3_661))).isEqualTo("1 h 1 min")
        assertThat(formatElapsed(Duration.ofDays(2).plusHours(3))).isEqualTo("2 d 3 h")
    }

    @Test
    fun externalCopyAndDeletionLimitsRemainExplicit() {
        assertThat(ClaimLanguage.MANAGED_BOUNDARY).contains("not monitored")
        assertThat(ClaimLanguage.DELETE_LIMIT).contains("does not claim physical flash erasure")
        assertThat(ClaimLanguage.TIMER_LIMIT).contains("not a security guarantee")
    }
}
