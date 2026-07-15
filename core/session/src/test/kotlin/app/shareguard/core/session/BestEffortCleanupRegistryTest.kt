package app.shareguard.core.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BestEffortCleanupRegistryTest {
    @Test
    fun `cleanup runs matching logical deletions in reverse registration order`() {
        val order = mutableListOf<Int>()
        val registry = BestEffortCleanupRegistry()
        registry.register(CleanupScope.PARTIAL_WORK) { order += 1; LogicalCleanupOutcome.DELETED }
        registry.register(CleanupScope.SESSION_TRANSIENT) { order += 2; LogicalCleanupOutcome.DELETED }
        registry.register(CleanupScope.PARTIAL_WORK) { order += 3; LogicalCleanupOutcome.ALREADY_ABSENT }

        val report = registry.cleanup(CleanupScope.PARTIAL_WORK)

        assertThat(order).containsExactly(3, 1).inOrder()
        assertThat(report).isEqualTo(CleanupReport(attempted = 2, completed = 2, failed = 0))
        assertThat(registry.registrationCountForTest()).isEqualTo(1)
    }

    @Test
    fun `exception is content-free failed count and registration remains retryable`() {
        val registry = BestEffortCleanupRegistry()
        var attempts = 0
        registry.register(CleanupScope.SESSION_TRANSIENT) {
            attempts += 1
            if (attempts == 1) throw IllegalStateException("/private/CANARY-name.png")
            LogicalCleanupOutcome.DELETED
        }

        val first = registry.cleanup(CleanupScope.SESSION_TRANSIENT)
        val second = registry.cleanup(CleanupScope.SESSION_TRANSIENT)

        assertThat(first).isEqualTo(CleanupReport(1, 0, 1))
        assertThat(first.toString()).doesNotContain("CANARY")
        assertThat(second).isEqualTo(CleanupReport(1, 1, 0))
        assertThat(registry.registrationCountForTest()).isEqualTo(0)
    }

    @Test
    fun `registration can be promoted or released without deletion`() {
        val registry = BestEffortCleanupRegistry()
        var deleted = false
        val promoted = registry.register(CleanupScope.PARTIAL_WORK) {
            deleted = true
            LogicalCleanupOutcome.DELETED
        }
        val released = registry.register(CleanupScope.PARTIAL_WORK) {
            deleted = true
            LogicalCleanupOutcome.DELETED
        }

        assertThat(promoted.reclassify(CleanupScope.SESSION_TRANSIENT)).isTrue()
        assertThat(released.releaseWithoutCleanup()).isTrue()
        assertThat(registry.cleanup(CleanupScope.PARTIAL_WORK).attempted).isEqualTo(0)
        assertThat(deleted).isFalse()
        assertThat(registry.cleanup(CleanupScope.SESSION_TRANSIENT).completed).isEqualTo(1)
        assertThat(deleted).isTrue()
    }
}
