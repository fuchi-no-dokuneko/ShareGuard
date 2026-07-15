package app.shareguard.core.session

import app.shareguard.core.model.SessionId
import java.util.concurrent.atomic.AtomicBoolean

interface CancellationSignal {
    val isCancellationRequested: Boolean

    fun throwIfCancellationRequested() {
        if (isCancellationRequested) throw SessionCancellationException()
    }
}

object NeverCancelled : CancellationSignal {
    override val isCancellationRequested: Boolean = false
}

class SessionCancellationException internal constructor() :
    IllegalStateException("Session work was cancelled")

private class MutableCancellationSignal : CancellationSignal {
    private val cancelled = AtomicBoolean(false)

    override val isCancellationRequested: Boolean
        get() = cancelled.get()

    fun request() {
        cancelled.set(true)
    }
}

enum class CleanupScope {
    PARTIAL_WORK,
    SESSION_TRANSIENT,
}

enum class LogicalCleanupOutcome {
    DELETED,
    ALREADY_ABSENT,
    FAILED_BEST_EFFORT,
}

fun interface LogicalCleanupAction {
    fun deleteLogical(): LogicalCleanupOutcome
}

data class CleanupReport(
    val attempted: Int,
    val completed: Int,
    val failed: Int,
    val deferredUntilNativeReturn: Boolean = false,
) {
    init {
        require(attempted >= 0 && completed >= 0 && failed >= 0)
        require(completed + failed == attempted)
    }

    val fullySuccessful: Boolean
        get() = failed == 0 && !deferredUntilNativeReturn

    companion object {
        fun empty(deferred: Boolean = false): CleanupReport =
            CleanupReport(0, 0, 0, deferredUntilNativeReturn = deferred)
    }
}

class CleanupRegistration internal constructor(
    private val registry: BestEffortCleanupRegistry,
    internal val id: Long,
) {
    fun releaseWithoutCleanup(): Boolean = registry.release(id)
    fun reclassify(scope: CleanupScope): Boolean = registry.reclassify(id, scope)
}

/**
 * Holds only logical deletion actions. Exceptions are converted to a path- and content-free count, and
 * failed actions remain registered so stale-session cleanup can retry later.
 */
class BestEffortCleanupRegistry {
    private val lock = Any()
    private val registrations = LinkedHashMap<Long, RegisteredCleanup>()
    private var nextId = 1L

    fun register(scope: CleanupScope, action: LogicalCleanupAction): CleanupRegistration = synchronized(lock) {
        val id = nextId++
        registrations[id] = RegisteredCleanup(scope, action)
        CleanupRegistration(this, id)
    }

    fun cleanup(vararg scopes: CleanupScope): CleanupReport {
        val requestedScopes = scopes.toSet()
        val selected = synchronized(lock) {
            registrations.entries
                .filter { it.value.scope in requestedScopes }
                .map { it.key to it.value.action }
                .asReversed()
        }
        var completed = 0
        var failed = 0
        selected.forEach { (id, action) ->
            val outcome = try {
                action.deleteLogical()
            } catch (_: Exception) {
                LogicalCleanupOutcome.FAILED_BEST_EFFORT
            }
            if (outcome == LogicalCleanupOutcome.FAILED_BEST_EFFORT) {
                failed += 1
            } else {
                completed += 1
                release(id)
            }
        }
        return CleanupReport(selected.size, completed, failed)
    }

    internal fun release(id: Long): Boolean = synchronized(lock) { registrations.remove(id) != null }

    internal fun reclassify(id: Long, scope: CleanupScope): Boolean = synchronized(lock) {
        val existing = registrations[id] ?: return@synchronized false
        registrations[id] = existing.copy(scope = scope)
        true
    }

    internal fun registrationCountForTest(): Int = synchronized(lock) { registrations.size }

    private data class RegisteredCleanup(
        val scope: CleanupScope,
        val action: LogicalCleanupAction,
    )
}

enum class SessionRuntimeState {
    CREATED,
    ACTIVE,
    CANCELLED,
    FAILED,
    COMPLETED,
    DISCARDED,
}

/**
 * Coordinates cancellation and cleanup without interrupting an in-flight native decoder. Cancellation
 * prevents new work, waits for native return, discards that partial result, and keeps sealed session state
 * available for explicit resume or discard.
 */
class SessionLifecycle(
    val sessionId: SessionId,
    private val cleanupRegistry: BestEffortCleanupRegistry = BestEffortCleanupRegistry(),
    val diagnosticTrace: SessionDiagnosticTrace = DisabledSessionDiagnosticTrace,
) {
    private val lock = Any()
    private var cancellation = MutableCancellationSignal()
    private var activeNativeCalls = 0
    private var deferredCleanup: DeferredCleanup = DeferredCleanup.NONE

    @Volatile
    var state: SessionRuntimeState = SessionRuntimeState.CREATED
        private set

    val cancellationSignal: CancellationSignal
        get() = synchronized(lock) { cancellation }

    fun activate() {
        synchronized(lock) {
            check(state == SessionRuntimeState.CREATED) { "Only a created session can be activated" }
            state = SessionRuntimeState.ACTIVE
        }
    }

    fun resume() {
        synchronized(lock) {
            check(state == SessionRuntimeState.CANCELLED) { "Only a cancelled session can be resumed" }
            check(activeNativeCalls == 0) { "Native work has not returned" }
            cancellation = MutableCancellationSignal()
            deferredCleanup = DeferredCleanup.NONE
            state = SessionRuntimeState.ACTIVE
        }
    }

    fun registerPartial(action: LogicalCleanupAction): CleanupRegistration =
        register(CleanupScope.PARTIAL_WORK, action)

    fun registerSessionTransient(action: LogicalCleanupAction): CleanupRegistration =
        register(CleanupScope.SESSION_TRANSIENT, action)

    fun ensureNewWorkMayStart() {
        synchronized(lock) {
            check(state == SessionRuntimeState.ACTIVE) { "Session is not active" }
            cancellation.throwIfCancellationRequested()
        }
    }

    fun requestCancellation(): CleanupReport {
        val shouldCleanup = synchronized(lock) {
            if (state == SessionRuntimeState.CANCELLED) return CleanupReport.empty(activeNativeCalls > 0)
            check(state == SessionRuntimeState.ACTIVE || state == SessionRuntimeState.CREATED) {
                "Session cannot be cancelled from its current state"
            }
            cancellation.request()
            state = SessionRuntimeState.CANCELLED
            if (activeNativeCalls > 0) {
                deferredCleanup = deferredCleanup.max(DeferredCleanup.PARTIAL)
                false
            } else {
                true
            }
        }
        return if (shouldCleanup) {
            cleanupRegistry.cleanup(CleanupScope.PARTIAL_WORK)
        } else {
            CleanupReport.empty(deferred = true)
        }
    }

    /** Runs a native call to completion; it is not thread-interrupted when cancellation is requested. */
    fun <T> runNativeBoundary(
        discardInvalidPartial: (T) -> Unit,
        operation: () -> T,
    ): T {
        val callCancellation = beginNativeCall()
        var result: T? = null
        var operationFailure: Throwable? = null
        try {
            result = operation()
        } catch (failure: Throwable) {
            operationFailure = failure
        }

        val finish = finishNativeCall(callCancellation)
        if (finish.cancelled) {
            if (operationFailure == null) {
                @Suppress("UNCHECKED_CAST")
                runCatching { discardInvalidPartial(result as T) }
            }
            if (finish.cleanup != DeferredCleanup.NONE) performDeferredCleanup(finish.cleanup)
            throw SessionCancellationException()
        }
        if (finish.cleanup != DeferredCleanup.NONE) performDeferredCleanup(finish.cleanup)
        if (operationFailure != null) throw operationFailure
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    fun failFatal(): CleanupReport = terminate(
        target = SessionRuntimeState.FAILED,
        allowedStates = setOf(
            SessionRuntimeState.CREATED,
            SessionRuntimeState.ACTIVE,
            SessionRuntimeState.CANCELLED,
            SessionRuntimeState.FAILED,
        ),
        requireNativeIdle = false,
    )

    fun complete(): CleanupReport = terminate(
        target = SessionRuntimeState.COMPLETED,
        allowedStates = setOf(SessionRuntimeState.ACTIVE),
        requireNativeIdle = true,
    )

    fun discard(): CleanupReport = terminate(
        target = SessionRuntimeState.DISCARDED,
        allowedStates = setOf(
            SessionRuntimeState.CREATED,
            SessionRuntimeState.ACTIVE,
            SessionRuntimeState.CANCELLED,
            SessionRuntimeState.FAILED,
        ),
        requireNativeIdle = false,
    )

    private fun terminate(
        target: SessionRuntimeState,
        allowedStates: Set<SessionRuntimeState>,
        requireNativeIdle: Boolean,
    ): CleanupReport {
        val shouldCleanup = synchronized(lock) {
            check(state in allowedStates) { "Session cannot enter the requested terminal state" }
            if (requireNativeIdle) check(activeNativeCalls == 0) { "Native work has not returned" }
            state = target
            cancellation.request()
            diagnosticTrace.clear()
            if (activeNativeCalls > 0) {
                deferredCleanup = DeferredCleanup.ALL
                false
            } else {
                true
            }
        }
        return if (shouldCleanup) {
            cleanupRegistry.cleanup(CleanupScope.PARTIAL_WORK, CleanupScope.SESSION_TRANSIENT)
        } else {
            CleanupReport.empty(deferred = true)
        }
    }

    private fun beginNativeCall(): MutableCancellationSignal = synchronized(lock) {
        check(state == SessionRuntimeState.ACTIVE) { "Session is not active" }
        cancellation.throwIfCancellationRequested()
        activeNativeCalls += 1
        cancellation
    }

    private fun register(scope: CleanupScope, action: LogicalCleanupAction): CleanupRegistration =
        synchronized(lock) {
            check(state == SessionRuntimeState.CREATED || state == SessionRuntimeState.ACTIVE) {
                "Cannot register transient work for an inactive session"
            }
            cleanupRegistry.register(scope, action)
        }

    private fun finishNativeCall(callCancellation: MutableCancellationSignal): NativeFinish = synchronized(lock) {
        check(activeNativeCalls > 0)
        activeNativeCalls -= 1
        val cleanup = if (activeNativeCalls == 0) deferredCleanup else DeferredCleanup.NONE
        if (activeNativeCalls == 0) deferredCleanup = DeferredCleanup.NONE
        NativeFinish(callCancellation.isCancellationRequested, cleanup)
    }

    private fun performDeferredCleanup(cleanup: DeferredCleanup) {
        when (cleanup) {
            DeferredCleanup.NONE -> Unit
            DeferredCleanup.PARTIAL -> cleanupRegistry.cleanup(CleanupScope.PARTIAL_WORK)
            DeferredCleanup.ALL -> cleanupRegistry.cleanup(
                CleanupScope.PARTIAL_WORK,
                CleanupScope.SESSION_TRANSIENT,
            )
        }
    }

    private data class NativeFinish(
        val cancelled: Boolean,
        val cleanup: DeferredCleanup,
    )

    private enum class DeferredCleanup {
        NONE,
        PARTIAL,
        ALL;

        fun max(other: DeferredCleanup): DeferredCleanup = if (ordinal >= other.ordinal) this else other
    }
}
