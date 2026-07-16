package app.shareguard.feature.output

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManagedShareIntentFactoryTest {
    @Test
    fun canonicalTextCarriesOnlyTheReviewedTextAndNoDestination() {
        val intent = ManagedShareIntentFactory.canonicalText("reviewed text")

        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(intent.type).isEqualTo("text/plain")
        assertThat(intent.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("reviewed text")
        assertThat(intent.component).isNull()
        assertThat(intent.`package`).isNull()
        assertThat(intent.hasExtra(Intent.EXTRA_STREAM)).isFalse()
    }

    @Test
    @Suppress("DEPRECATION")
    fun imageUsesOnlyScopedContentUriAndTemporaryReadPermission() {
        val uri = Uri.parse("content://app.shareguard.canonical.managed-share/result")
        val intent = ManagedShareIntentFactory.image(uri, "image/png")

        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(intent.type).isEqualTo("image/png")
        assertThat(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)).isEqualTo(uri)
        assertThat(intent.clipData?.getItemAt(0)?.uri).isEqualTo(uri)
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(intent.component).isNull()
        assertThat(intent.`package`).isNull()
    }

    @Test
    fun bothRepresentationsShareOneTextAndOneScopedImage() {
        val uri = Uri.parse("content://app.shareguard.canonical.managed-share/result")

        val intent = ManagedShareIntentFactory.textAndImage("same revision", uri, "image/png")

        assertThat(intent.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("same revision")
        assertThat(intent.clipData?.itemCount).isEqualTo(1)
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    }
}
