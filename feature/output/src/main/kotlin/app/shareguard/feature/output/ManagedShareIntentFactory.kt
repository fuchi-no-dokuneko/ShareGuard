package app.shareguard.feature.output

import android.content.ClipData
import android.content.Intent
import android.net.Uri

/** Creates only Android Sharesheet inputs; it never launches or records a destination. */
object ManagedShareIntentFactory {
    fun canonicalText(text: String): Intent {
        require(text.isNotEmpty()) { "Verified canonical text cannot be empty" }
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }
    }

    fun image(readOnlyContentUri: Uri, mimeType: String): Intent {
        require(readOnlyContentUri.scheme == "content") { "Managed image share requires a content URI" }
        require(mimeType.startsWith("image/")) { "Managed image share requires an image MIME type" }
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, readOnlyContentUri)
            clipData = ClipData.newRawUri("Canonical Share managed result", readOnlyContentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun textAndImage(text: String, readOnlyContentUri: Uri, imageMimeType: String): Intent {
        require(text.isNotEmpty()) { "Verified canonical text cannot be empty" }
        require(readOnlyContentUri.scheme == "content") { "Managed image share requires a content URI" }
        require(imageMimeType.startsWith("image/")) { "Managed image share requires an image MIME type" }
        return image(readOnlyContentUri, imageMimeType).apply {
            putExtra(Intent.EXTRA_TEXT, text)
        }
    }

    fun chooser(sendIntent: Intent): Intent = Intent.createChooser(sendIntent, "Share verified result")
}

object NeutralExportName {
    private val allowedExtension = Regex("[a-z0-9]{1,8}")

    fun create(resultId: String, extension: String): String {
        require(resultId.matches(Regex("[A-Za-z0-9_-]{4,64}"))) { "Result ID is not suitable for an export name" }
        val normalizedExtension = extension.lowercase()
        require(allowedExtension.matches(normalizedExtension)) { "Invalid export extension" }
        return "canonical-share-${resultId.take(12)}.$normalizedExtension"
    }
}
