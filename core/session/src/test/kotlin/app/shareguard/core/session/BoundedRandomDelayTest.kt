package app.shareguard.core.session

import app.shareguard.core.model.BoundedDelayPolicy
import app.shareguard.core.model.BoundedDelayPurpose
import app.shareguard.core.model.DurationMillis
import app.shareguard.core.model.SafeSummary
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BoundedRandomDelayTest {
    @Test
    fun optionalShareJitterUsesSelectedBoundWithoutPersistingItInPolicy() = runTest {
        var slept: DurationMillis? = null
        val policy = BoundedDelayPolicy(
            enabled = true,
            purpose = BoundedDelayPurpose.OPTIONAL_PRE_SHARE_JITTER,
            minimum = DurationMillis(100),
            maximum = DurationMillis(500),
            validationReference = SafeSummary("ui-share-jitter-envelope-v1"),
        )
        val delay = PolicyBoundedRandomDelay(
            selector = BoundedDurationSelector { minimum, maximum ->
                assertThat(minimum).isEqualTo(100)
                assertThat(maximum).isEqualTo(500)
                313
            },
            sleeper = DelaySleeper { slept = it },
        )

        delay.await(policy)

        assertThat(slept).isEqualTo(DurationMillis(313))
        assertThat(policy.toString()).doesNotContain("313")
    }

    @Test
    fun disabledShareJitterDoesNotInvokeSelectorOrSleeper() = runTest {
        var invoked = false
        val delay = PolicyBoundedRandomDelay(
            selector = BoundedDurationSelector { _, _ -> invoked = true; 0 },
            sleeper = DelaySleeper { invoked = true },
        )

        delay.await(
            BoundedDelayPolicy(
                enabled = false,
                purpose = BoundedDelayPurpose.OPTIONAL_PRE_SHARE_JITTER,
                minimum = DurationMillis(0),
                maximum = DurationMillis(0),
                validationReference = SafeSummary("disabled"),
            ),
        )

        assertThat(invoked).isFalse()
    }
}
