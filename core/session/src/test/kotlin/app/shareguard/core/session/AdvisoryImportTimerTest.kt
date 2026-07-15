package app.shareguard.core.session

import app.shareguard.core.model.BootSessionReference
import app.shareguard.core.model.DurationMillis
import app.shareguard.core.model.ImportAnchor
import app.shareguard.core.model.ImportClockConfidence
import app.shareguard.core.model.MonotonicInstant
import app.shareguard.core.model.WallClockInstant
import com.google.common.truth.Truth.assertThat
import java.util.TimeZone
import org.junit.Test

class AdvisoryImportTimerTest {
    @Test
    fun `recorder captures monotonic anchor only with a boot reference`() {
        val recorder = ImportAnchorRecorder(
            wallClock = WallClockSource { WallClockInstant(10_000) },
            monotonicClock = MonotonicClockSource { MonotonicInstant(50_000_000) },
            bootSessionReferenceSource = BootSessionReferenceSource { BootSessionReference("boot-a") },
        )

        val anchor = recorder.recordAcceptedImport()

        assertThat(anchor.wallClock).isEqualTo(WallClockInstant(10_000))
        assertThat(anchor.monotonic).isEqualTo(MonotonicInstant(50_000_000))
        assertThat(anchor.bootSessionReference).isEqualTo(BootSessionReference("boot-a"))
        assertThat(anchor.clockConfidence).isEqualTo(ImportClockConfidence.MONOTONIC_ACTIVE)
    }

    @Test
    fun `recorder falls back to wall clock when boot identity is unavailable`() {
        var monotonicCalls = 0
        val recorder = ImportAnchorRecorder(
            wallClock = WallClockSource { WallClockInstant(10_000) },
            monotonicClock = MonotonicClockSource {
                monotonicCalls += 1
                MonotonicInstant(50_000_000)
            },
            bootSessionReferenceSource = BootSessionReferenceSource { null },
        )

        val anchor = recorder.recordAcceptedImport()

        assertThat(anchor.monotonic).isNull()
        assertThat(anchor.bootSessionReference).isNull()
        assertThat(anchor.clockConfidence).isEqualTo(ImportClockConfidence.WALL_CLOCK_RESTORED)
        assertThat(monotonicCalls).isEqualTo(0)
    }

    @Test
    fun `same boot prefers monotonic duration`() {
        val timer = timer(
            anchorWall = 100_000,
            anchorNanos = 5_000_000_000,
            anchorBoot = "boot-a",
            nowWall = 101_234,
            nowNanos = 6_234_000_000,
            nowBoot = "boot-a",
        )

        val reading = timer.sample()

        assertThat(reading.elapsed).isEqualTo(DurationMillis(1_234))
        assertThat(reading.confidence).isEqualTo(ImportClockConfidence.MONOTONIC_ACTIVE)
        assertThat(reading.anomaly).isEqualTo(AdvisoryTimerAnomaly.NONE)
        assertThat(reading.isExact).isFalse()
    }

    @Test
    fun `reboot uses persisted wall clock with reduced confidence`() {
        val timer = timer(
            anchorWall = 10_000,
            anchorNanos = 20_000_000,
            anchorBoot = "boot-a",
            nowWall = 17_500,
            nowNanos = 3_000_000,
            nowBoot = "boot-b",
        )

        val reading = timer.sample()

        assertThat(reading.elapsed).isEqualTo(DurationMillis(7_500))
        assertThat(reading.confidence).isEqualTo(ImportClockConfidence.WALL_CLOCK_RESTORED)
        assertThat(reading.anomaly).isEqualTo(AdvisoryTimerAnomaly.BOOT_CHANGED)
    }

    @Test
    fun `wall rollback never displays a negative duration`() {
        val timer = timer(
            anchorWall = 20_000,
            anchorNanos = null,
            anchorBoot = null,
            nowWall = 1_000,
            nowNanos = null,
            nowBoot = null,
        )

        val reading = timer.sample()

        assertThat(reading.elapsed).isEqualTo(DurationMillis(0))
        assertThat(reading.confidence).isEqualTo(ImportClockConfidence.CLOCK_CHANGE_DETECTED)
        assertThat(reading.anomaly).isEqualTo(AdvisoryTimerAnomaly.WALL_CLOCK_ROLLBACK)
    }

    @Test
    fun `manual wall clock jump is reported while monotonic duration remains authoritative`() {
        val timer = timer(
            anchorWall = 10_000,
            anchorNanos = 1_000_000_000,
            anchorBoot = "boot-a",
            nowWall = 3_610_000,
            nowNanos = 2_000_000_000,
            nowBoot = "boot-a",
        )

        val reading = timer.sample()

        assertThat(reading.elapsed).isEqualTo(DurationMillis(1_000))
        assertThat(reading.confidence).isEqualTo(ImportClockConfidence.CLOCK_CHANGE_DETECTED)
        assertThat(reading.anomaly).isEqualTo(AdvisoryTimerAnomaly.CLOCK_DIVERGENCE)
    }

    @Test
    fun `monotonic rollback falls back to nonnegative wall clock`() {
        val timer = timer(
            anchorWall = 10_000,
            anchorNanos = 5_000_000_000,
            anchorBoot = "boot-a",
            nowWall = 12_000,
            nowNanos = 1_000_000_000,
            nowBoot = "boot-a",
        )

        val reading = timer.sample()

        assertThat(reading.elapsed).isEqualTo(DurationMillis(2_000))
        assertThat(reading.confidence).isEqualTo(ImportClockConfidence.CLOCK_CHANGE_DETECTED)
        assertThat(reading.anomaly).isEqualTo(AdvisoryTimerAnomaly.MONOTONIC_ROLLBACK)
    }

    @Test
    fun `restored wall timer does not move an existing display backwards`() {
        val mutableWall = MutableWallClock(5_000)
        val anchor = ImportAnchor(
            wallClock = WallClockInstant(1_000),
            monotonic = null,
            bootSessionReference = null,
            clockConfidence = ImportClockConfidence.WALL_CLOCK_RESTORED,
        )
        val timer = AdvisoryImportTimer(anchor, mutableWall)
        assertThat(timer.sample().elapsed).isEqualTo(DurationMillis(4_000))

        mutableWall.value = 3_000
        val second = timer.sample()

        assertThat(second.elapsed).isEqualTo(DurationMillis(4_000))
        assertThat(second.confidence).isEqualTo(ImportClockConfidence.CLOCK_CHANGE_DETECTED)
        assertThat(second.anomaly).isEqualTo(AdvisoryTimerAnomaly.WALL_CLOCK_ROLLBACK)
    }

    @Test
    fun `legacy unknown anchor remains explicitly unknown`() {
        val anchor = ImportAnchor(
            wallClock = WallClockInstant(1_000),
            monotonic = null,
            bootSessionReference = null,
            clockConfidence = ImportClockConfidence.UNKNOWN_LEGACY_IMPORT_TIME,
        )
        val timer = AdvisoryImportTimer(anchor, WallClockSource { WallClockInstant(2_000) })

        val reading = timer.sample()

        assertThat(reading.elapsed).isEqualTo(DurationMillis(1_000))
        assertThat(reading.confidence).isEqualTo(ImportClockConfidence.UNKNOWN_LEGACY_IMPORT_TIME)
        assertThat(reading.anomaly).isEqualTo(AdvisoryTimerAnomaly.UNKNOWN_LEGACY_TIME)
    }

    @Test
    fun `elapsed formatting is unaffected by timezone or DST setting`() {
        val reading = AdvisoryTimerReading(
            elapsed = DurationMillis(3_661_999),
            confidence = ImportClockConfidence.WALL_CLOCK_RESTORED,
            anomaly = AdvisoryTimerAnomaly.NONE,
        )
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            val first = AdvisoryTimerFormatter.format(reading)
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val second = AdvisoryTimerFormatter.format(reading)

            assertThat(first).isEqualTo("01:01:01")
            assertThat(second).isEqualTo(first)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private fun timer(
        anchorWall: Long,
        anchorNanos: Long?,
        anchorBoot: String?,
        nowWall: Long,
        nowNanos: Long?,
        nowBoot: String?,
    ): AdvisoryImportTimer {
        val anchor = ImportAnchor(
            wallClock = WallClockInstant(anchorWall),
            monotonic = anchorNanos?.let(::MonotonicInstant),
            bootSessionReference = anchorBoot?.let(::BootSessionReference),
            clockConfidence = if (anchorNanos != null) {
                ImportClockConfidence.MONOTONIC_ACTIVE
            } else {
                ImportClockConfidence.WALL_CLOCK_RESTORED
            },
        )
        return AdvisoryImportTimer(
            anchor = anchor,
            wallClock = WallClockSource { WallClockInstant(nowWall) },
            monotonicClock = nowNanos?.let { value -> MonotonicClockSource { MonotonicInstant(value) } },
            bootSessionReferenceSource = BootSessionReferenceSource {
                nowBoot?.let(::BootSessionReference)
            },
        )
    }

    private class MutableWallClock(var value: Long) : WallClockSource {
        override fun now(): WallClockInstant = WallClockInstant(value)
    }
}
