package app.shareguard.canonical

import android.os.SystemClock
import app.shareguard.core.model.BootSessionReference
import app.shareguard.core.session.BootSessionReferenceSource
import java.security.MessageDigest

/**
 * Derives a boot-scoped opaque reference without storing an installation or device identifier. A wall
 * clock change may deliberately change the value, which safely downgrades the advisory timer to its
 * ordinary-clock path.
 */
class EstimatedBootSessionReferenceSource : BootSessionReferenceSource {
    override fun current(): BootSessionReference? {
        val bootEpochBucket = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / BUCKET_MILLIS
        val bytes = "canonical-share-boot:$bootEpochBucket".encodeToByteArray()
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            BootSessionReference("boot-${digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }}")
        } catch (_: Exception) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    private companion object {
        const val BUCKET_MILLIS = 60_000L
    }
}
