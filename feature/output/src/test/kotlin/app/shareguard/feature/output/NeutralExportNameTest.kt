package app.shareguard.feature.output

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NeutralExportNameTest {
    @Test
    fun generatedNameContainsOnlyNeutralResultIdentifier() {
        assertThat(NeutralExportName.create("result_123456789", "PNG"))
            .isEqualTo("canonical-share-result_12345.png")
    }

    @Test(expected = IllegalArgumentException::class)
    fun sourceLikePathCannotBecomeExportName() {
        NeutralExportName.create("../../camera/my-name", "png")
    }
}
