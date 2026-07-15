package app.shareguard.core.session

import android.os.SystemClock
import app.shareguard.core.model.BootSessionReference
import app.shareguard.core.model.DurationMillis
import app.shareguard.core.model.ImportAnchor
import app.shareguard.core.model.ImportClockConfidence
import app.shareguard.core.model.MonotonicInstant
import app.shareguard.core.model.WallClockInstant

fun interface WallClockSource {
    fun now(): WallClockInstant
}

fun interface MonotonicClockSource {
    fun now(): MonotonicInstant
}

fun interface BootSessionReferenceSource {
    /** A boot-scoped opaque value only; never return an account, device, installation, or content ID. */
    fun current(): BootSessionReference?
}

object SystemWallClockSource : WallClockSource {
    override fun now(): WallClockInstant = WallClockInstant(System.currentTimeMillis().coerceAtLeast(0))
}

object AndroidMonotonicClockSource : MonotonicClockSource {
    override fun now(): MonotonicInstant = MonotonicInstant(SystemClock.elapsedRealtimeNanos())
}

/**
 * Records the Import Anchor only when the caller declares that an import has been accepted: after text was
 * immutably copied/submitted or after provider bytes were copied, sealed, and reopened successfully.
 */
class ImportAnchorRecorder(
    private val wallClock: WallClockSource,
    private val monotonicClock: MonotonicClockSource? = null,
    private val bootSessionReferenceSource: BootSessionReferenceSource? = null,
) {
    fun recordAcceptedImport(): ImportAnchor {
        val wall = wallClock.now()
        val bootReference = runCatching { bootSessionReferenceSource?.current() }.getOrNull()
        val monotonic = if (bootReference != null) {
            runCatching { monotonicClock?.now() }.getOrNull()
        } else {
            null
        }
        return if (monotonic != null && bootReference != null) {
            ImportAnchor(
                wallClock = wall,
                monotonic = monotonic,
                bootSessionReference = bootReference,
                clockConfidence = ImportClockConfidence.MONOTONIC_ACTIVE,
            )
        } else {
            ImportAnchor(
                wallClock = wall,
                monotonic = null,
                bootSessionReference = null,
                clockConfidence = ImportClockConfidence.WALL_CLOCK_RESTORED,
            )
        }
    }
}

enum class AdvisoryTimerAnomaly {
    NONE,
    BOOT_CHANGED,
    MONOTONIC_UNAVAILABLE,
    MONOTONIC_ROLLBACK,
    WALL_CLOCK_ROLLBACK,
    CLOCK_DIVERGENCE,
    UNKNOWN_LEGACY_TIME,
}

/** An awareness-only reading. It is never evidence of screenshot age, security, or exact event timing. */
data class AdvisoryTimerReading(
    val elapsed: DurationMillis,
    val confidence: ImportClockConfidence,
    val anomaly: AdvisoryTimerAnomaly,
) {
    val isExact: Boolean
        get() = false
}

/**
 * Same-boot readings prefer monotonic time. Restored/rebooted readings use wall-clock time, clamp negative
 * values to zero, report anomalies, and never move an already displayed value backwards.
 */
class AdvisoryImportTimer(
    private val anchor: ImportAnchor,
    private val wallClock: WallClockSource,
    private val monotonicClock: MonotonicClockSource? = null,
    private val bootSessionReferenceSource: BootSessionReferenceSource? = null,
    private val divergenceTolerance: DurationMillis = DurationMillis(2_000),
) {
    private var lastDisplayedMillis = 0L

    @Synchronized
    fun sample(): AdvisoryTimerReading {
        val wallNow = wallClock.now()
        val monotonicNow = runCatching { monotonicClock?.now() }.getOrNull()
        val currentBoot = runCatching { bootSessionReferenceSource?.current() }.getOrNull()
        val anchorMonotonic = anchor.monotonic
        val wallRolledBack = wallNow.epochMillis < anchor.wallClock.epochMillis
        val wallElapsed = saturatedNonNegativeDifference(wallNow.epochMillis, anchor.wallClock.epochMillis)

        val sameBoot = anchor.bootSessionReference != null &&
            currentBoot != null &&
            anchor.bootSessionReference == currentBoot
        val monotonicRolledBack = sameBoot &&
            anchorMonotonic != null &&
            monotonicNow != null &&
            monotonicNow.elapsedRealtimeNanos < anchorMonotonic.elapsedRealtimeNanos
        val monotonicUsable = sameBoot &&
            anchorMonotonic != null &&
            monotonicNow != null &&
            !monotonicRolledBack

        var anomaly: AdvisoryTimerAnomaly
        var confidence: ImportClockConfidence
        var elapsed = if (monotonicUsable) {
            val monotonicElapsed = saturatedNonNegativeDifference(
                monotonicNow.elapsedRealtimeNanos,
                anchorMonotonic.elapsedRealtimeNanos,
            ) / NANOS_PER_MILLISECOND
            val divergent = wallRolledBack ||
                absoluteDifference(monotonicElapsed, wallElapsed) > divergenceTolerance.value
            anomaly = when {
                wallRolledBack -> AdvisoryTimerAnomaly.WALL_CLOCK_ROLLBACK
                divergent -> AdvisoryTimerAnomaly.CLOCK_DIVERGENCE
                else -> AdvisoryTimerAnomaly.NONE
            }
            confidence = if (divergent) {
                ImportClockConfidence.CLOCK_CHANGE_DETECTED
            } else {
                ImportClockConfidence.MONOTONIC_ACTIVE
            }
            monotonicElapsed
        } else {
            anomaly = when {
                wallRolledBack -> AdvisoryTimerAnomaly.WALL_CLOCK_ROLLBACK
                monotonicRolledBack -> AdvisoryTimerAnomaly.MONOTONIC_ROLLBACK
                anchor.clockConfidence == ImportClockConfidence.UNKNOWN_LEGACY_IMPORT_TIME ->
                    AdvisoryTimerAnomaly.UNKNOWN_LEGACY_TIME
                anchor.bootSessionReference != null && currentBoot != null &&
                    anchor.bootSessionReference != currentBoot -> AdvisoryTimerAnomaly.BOOT_CHANGED
                else -> AdvisoryTimerAnomaly.MONOTONIC_UNAVAILABLE
            }
            confidence = when {
                wallRolledBack || monotonicRolledBack -> ImportClockConfidence.CLOCK_CHANGE_DETECTED
                anchor.clockConfidence == ImportClockConfidence.UNKNOWN_LEGACY_IMPORT_TIME ->
                    ImportClockConfidence.UNKNOWN_LEGACY_IMPORT_TIME
                else -> ImportClockConfidence.WALL_CLOCK_RESTORED
            }
            wallElapsed
        }

        if (elapsed < lastDisplayedMillis) {
            elapsed = lastDisplayedMillis
            anomaly = AdvisoryTimerAnomaly.WALL_CLOCK_ROLLBACK
            confidence = ImportClockConfidence.CLOCK_CHANGE_DETECTED
        }
        lastDisplayedMillis = elapsed
        return AdvisoryTimerReading(DurationMillis(elapsed), confidence, anomaly)
    }

    private fun saturatedNonNegativeDifference(later: Long, earlier: Long): Long {
        if (later < earlier) return 0
        return try {
            Math.subtractExact(later, earlier)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }

    private fun absoluteDifference(first: Long, second: Long): Long =
        if (first >= second) {
            saturatedNonNegativeDifference(first, second)
        } else {
            saturatedNonNegativeDifference(second, first)
        }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

object AdvisoryTimerFormatter {
    /** Locale- and timezone-neutral elapsed display. */
    fun format(reading: AdvisoryTimerReading): String {
        val totalSeconds = reading.elapsed.value / 1_000
        val seconds = totalSeconds % 60
        val totalMinutes = totalSeconds / 60
        val minutes = totalMinutes % 60
        val hours = totalMinutes / 60
        return listOf(hours, minutes, seconds)
            .joinToString(":") { value -> value.toString().padStart(2, '0') }
    }
}
