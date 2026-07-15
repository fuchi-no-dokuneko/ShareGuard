package app.shareguard.core.session

import app.shareguard.core.model.BoundedDelayPolicy
import app.shareguard.core.model.BoundedDelayPurpose
import app.shareguard.core.model.DurationMillis
import app.shareguard.core.model.SafeSummary
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class SnapshotRecheckDelayTest {
    @Test
    fun `enabled policy sleeps selected bounded duration without returning it`() = runTest {
        val selectedBounds = mutableListOf<Pair<Long, Long>>()
        val slept = mutableListOf<DurationMillis>()
        val delay = PolicyBoundedSnapshotRecheckDelay(
            selector = BoundedDurationSelector { minimum, maximum ->
                selectedBounds += minimum to maximum
                7
            },
            sleeper = DelaySleeper { slept += it },
        )

        delay.await(policy(minimum = 5, maximum = 10), NeverCancelled)

        assertThat(selectedBounds).containsExactly(5L to 10L)
        assertThat(slept).containsExactly(DurationMillis(7))
    }

    @Test
    fun `disabled policy neither selects nor sleeps`() = runTest {
        var selected = false
        var slept = false
        val delay = PolicyBoundedSnapshotRecheckDelay(
            selector = BoundedDurationSelector { _, _ -> selected = true; 0 },
            sleeper = DelaySleeper { slept = true },
        )

        delay.await(policy(minimum = 0, maximum = 10, enabled = false), NeverCancelled)

        assertThat(selected).isFalse()
        assertThat(slept).isFalse()
    }

    @Test
    fun `cancellation is checked before sleeping`() {
        var slept = false
        val delay = PolicyBoundedSnapshotRecheckDelay(
            selector = BoundedDurationSelector { _, _ -> 1 },
            sleeper = DelaySleeper { slept = true },
        )

        assertThrows(SessionCancellationException::class.java) {
            runTest {
                delay.await(policy(1, 1), FixedCancellationSignal(true))
            }
        }
        assertThat(slept).isFalse()
    }

    @Test
    fun `wrong purpose and excessive budget are rejected`() {
        val delay = PolicyBoundedSnapshotRecheckDelay(
            selector = BoundedDurationSelector { minimum, _ -> minimum },
            sleeper = DelaySleeper { },
            maximumSupportedDelay = DurationMillis(100),
        )
        val wrongPurpose = BoundedDelayPolicy(
            enabled = true,
            purpose = BoundedDelayPurpose.OPTIONAL_PRE_SHARE_JITTER,
            minimum = DurationMillis(0),
            maximum = DurationMillis(1),
            validationReference = SafeSummary("test-policy"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runTest { delay.await(wrongPurpose, NeverCancelled) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runTest { delay.await(policy(0, 101), NeverCancelled) }
        }
    }

    private fun policy(minimum: Long, maximum: Long, enabled: Boolean = true) = BoundedDelayPolicy(
        enabled = enabled,
        purpose = BoundedDelayPurpose.PROVIDER_SNAPSHOT_RECHECK,
        minimum = DurationMillis(minimum),
        maximum = DurationMillis(maximum),
        validationReference = SafeSummary("deterministic-test-policy"),
    )

    private class FixedCancellationSignal(
        override val isCancellationRequested: Boolean,
    ) : CancellationSignal
}
