package app.shareguard.canonical

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import java.security.MessageDigest

/** Runs in the test APK package, not the ShareGuard application package. */
class TestShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (uri == null) return finishWithFailure("URI_MISSING")
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("URI_UNREADABLE")
        }.getOrElse { return finishWithFailure("READ_DENIED") }
        try {
            val writable = runCatching {
                contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
            }.getOrDefault(false)
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_SHA_256, bytes.sha256())
                    .putExtra(EXTRA_BYTE_COUNT, bytes.size)
                    .putExtra(EXTRA_WRITE_OPEN_SUCCEEDED, writable),
            )
            finish()
        } finally {
            bytes.fill(0)
        }
    }

    private fun finishWithFailure(code: String) {
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_FAILURE_CODE, code))
        finish()
    }

    private fun ByteArray.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return try {
            digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } finally {
            digest.fill(0)
        }
    }

    companion object {
        const val EXTRA_SHA_256 = "test.sha256"
        const val EXTRA_BYTE_COUNT = "test.byte_count"
        const val EXTRA_WRITE_OPEN_SUCCEEDED = "test.write_open_succeeded"
        const val EXTRA_FAILURE_CODE = "test.failure_code"
    }
}
