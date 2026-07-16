package app.shareguard.core.session

import app.shareguard.core.model.BoundedDelayPolicy
import app.shareguard.core.model.BoundedDelayPurpose
import app.shareguard.core.model.DurationMillis
import kotlin.random.Random
import kotlinx.coroutines.delay

fun interface BoundedDurationSelector {
    fun select(minimumInclusive: Long, maximumInclusive: Long): Long
}

class RandomBoundedDurationSelector(
    private val random: Random = Random.Default,
) : BoundedDurationSelector {
    override fun select(minimumInclusive: Long, maximumInclusive: Long): Long {
        require(minimumInclusive >= 0 && maximumInclusive >= minimumInclusive)
        if (minimumInclusive == maximumInclusive) return minimumInclusive
        require(maximumInclusive < Long.MAX_VALUE) { "Delay bound is too large" }
        return random.nextLong(minimumInclusive, maximumInclusive + 1)
    }
}

fun interface DelaySleeper {
    suspend fun sleep(duration: DurationMillis)
}

object CoroutineDelaySleeper : DelaySleeper {
    override suspend fun sleep(duration: DurationMillis) {
        delay(duration.value)
    }
}

/** General optional jitter. The selected duration is ephemeral and cancellation follows coroutine cancellation. */
class PolicyBoundedRandomDelay(
    private val selector: BoundedDurationSelector = RandomBoundedDurationSelector(),
    private val sleeper: DelaySleeper = CoroutineDelaySleeper,
    private val maximumSupportedDelay: DurationMillis = DurationMillis(60_000),
) {
    suspend fun await(policy: BoundedDelayPolicy) {
        if (!policy.enabled) return
        require(policy.maximum.value <= maximumSupportedDelay.value) {
            "Bounded delay exceeds the local resource budget"
        }
        val selected = selector.select(policy.minimum.value, policy.maximum.value)
        require(selected in policy.minimum.value..policy.maximum.value) {
            "Delay selector returned an out-of-bounds duration"
        }
        sleeper.sleep(DurationMillis(selected))
    }
}

fun interface SnapshotRecheckDelay {
    suspend fun await(policy: BoundedDelayPolicy, cancellationSignal: CancellationSignal)
}

/**
 * Optional bounded jitter before a provider snapshot is sealed. It is cancellable, never persisted, never
 * uses the network, and is not represented as a security or exact-timing control.
 */
class PolicyBoundedSnapshotRecheckDelay(
    private val selector: BoundedDurationSelector = RandomBoundedDurationSelector(),
    private val sleeper: DelaySleeper = CoroutineDelaySleeper,
    private val maximumSupportedDelay: DurationMillis = DurationMillis(60_000),
) : SnapshotRecheckDelay {
    override suspend fun await(policy: BoundedDelayPolicy, cancellationSignal: CancellationSignal) {
        if (!policy.enabled) return
        require(policy.purpose == BoundedDelayPurpose.PROVIDER_SNAPSHOT_RECHECK) {
            "Snapshot delay has the wrong purpose"
        }
        require(policy.maximum.value <= maximumSupportedDelay.value) {
            "Snapshot delay exceeds the local resource budget"
        }
        cancellationSignal.throwIfCancellationRequested()
        val selected = selector.select(policy.minimum.value, policy.maximum.value)
        require(selected in policy.minimum.value..policy.maximum.value) {
            "Delay selector returned an out-of-bounds duration"
        }
        sleeper.sleep(DurationMillis(selected))
        cancellationSignal.throwIfCancellationRequested()
    }
}

object NoSnapshotRecheckDelay : SnapshotRecheckDelay {
    override suspend fun await(policy: BoundedDelayPolicy, cancellationSignal: CancellationSignal) {
        cancellationSignal.throwIfCancellationRequested()
    }
}
