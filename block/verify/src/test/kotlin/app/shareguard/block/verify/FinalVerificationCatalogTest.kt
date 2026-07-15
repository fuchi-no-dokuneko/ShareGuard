package app.shareguard.block.verify

import app.shareguard.core.model.VerificationLayer
import app.shareguard.core.model.VerificationType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FinalVerificationCatalogTest {
    @Test
    fun `every verification type has one immutable descriptor and a layer`() {
        assertThat(FinalVerificationCatalog.descriptors.map { it.type })
            .containsExactlyElementsIn(VerificationType.entries)
        assertThat(FinalVerificationCatalog.descriptors.map { it.verificationId }.distinct())
            .hasSize(VerificationType.entries.size)
        assertThat(FinalVerificationCatalog.descriptors.all { it.layers.isNotEmpty() }).isTrue()
        assertThat(FinalVerificationCatalog.require(VerificationType.PERSISTENT_REOPEN_AND_DIGEST).normativeBlockId)
            .isNull()
    }

    @Test
    fun `five final verification layers are represented`() {
        assertThat(FinalVerificationCatalog.descriptors.flatMap { it.layers }.toSet())
            .containsExactlyElementsIn(VerificationLayer.entries)
    }
}
