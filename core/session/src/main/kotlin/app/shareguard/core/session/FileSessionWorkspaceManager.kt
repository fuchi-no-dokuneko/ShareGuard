package app.shareguard.core.session

import app.shareguard.core.model.SessionId
import app.shareguard.core.security.ContentDigester
import app.shareguard.core.security.Sha256ContentDigester
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface SessionIdGenerator {
    /** Must be fresh and ephemeral, never derived from an account/device/install ID or long-lived seed. */
    fun next(): SessionId
}

class SecureRandomSessionIdGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
) : SessionIdGenerator {
    override fun next(): SessionId {
        val randomBytes = ByteArray(16).also(secureRandom::nextBytes)
        return try {
            SessionId("s-${randomBytes.toLowerHex()}")
        } finally {
            randomBytes.fill(0)
        }
    }

    private fun ByteArray.toLowerHex(): String {
        val output = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = HEX_CHARACTERS[value ushr 4]
            output[index * 2 + 1] = HEX_CHARACTERS[value and 0x0f]
        }
        return String(output)
    }

    private companion object {
        const val HEX_CHARACTERS = "0123456789abcdef"
    }
}

data class SessionStartResult(
    val session: ManagedSession,
    val staleCleanup: CleanupReport,
)

class ManagedSession internal constructor(
    val lifecycle: SessionLifecycle,
    val snapshots: FileSourceSnapshotStore,
) {
    val sessionId: SessionId
        get() = lifecycle.sessionId
}

class SessionWorkspaceCreationException : IllegalStateException("Private session workspace creation failed")

/**
 * Owns only transient session workspaces beneath a caller-provided app-private root. Committed Saved Results
 * cannot be registered here, so normal/fatal/stale purges cannot intentionally delete them.
 */
class FileSessionWorkspaceManager(
    workspaceRoot: File,
    private val importAnchorRecorder: ImportAnchorRecorder,
    private val snapshotLimits: SnapshotLimits,
    private val staleAfterMillis: Long,
    private val wallClock: WallClockSource,
    private val sessionIdGenerator: SessionIdGenerator = SecureRandomSessionIdGenerator(),
    private val sourceHandleGenerator: SourceHandleGenerator = SecureRandomSourceHandleGenerator(),
    private val digester: ContentDigester = Sha256ContentDigester(),
    private val recheckDelay: SnapshotRecheckDelay = PolicyBoundedSnapshotRecheckDelay(),
    private val debugTraceEnabled: Boolean = false,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val root = runCatching { workspaceRoot.canonicalFile }
        .getOrElse { throw SessionWorkspaceCreationException() }

    init {
        require(staleAfterMillis >= 0) { "Stale-session age cannot be negative" }
    }

    suspend fun startSession(): SessionStartResult = withContext(ioDispatcher) {
        ensureRoot()
        val staleCleanup = purgeStaleSessionsInternal()
        val allocation = allocateSessionDirectory()
        try {
            val trace: SessionDiagnosticTrace = if (debugTraceEnabled) {
                InMemorySessionDiagnosticTrace(allocation.sessionId)
            } else {
                DisabledSessionDiagnosticTrace
            }
            val cleanupRegistry = BestEffortCleanupRegistry()
            val lifecycle = SessionLifecycle(allocation.sessionId, cleanupRegistry, trace)
            lifecycle.registerSessionTransient(
                LogicalCleanupAction {
                    val removed = runCatching {
                        deleteTreeLogical(allocation.directory) && !allocation.directory.exists()
                    }.getOrDefault(false)
                    if (removed) {
                        LogicalCleanupOutcome.DELETED
                    } else {
                        LogicalCleanupOutcome.FAILED_BEST_EFFORT
                    }
                },
            )
            val snapshots = FileSourceSnapshotStore(
                workspaceDirectory = allocation.directory,
                importAnchorRecorder = importAnchorRecorder,
                limits = snapshotLimits,
                digester = digester,
                sourceHandleGenerator = sourceHandleGenerator,
                recheckDelay = recheckDelay,
                ioDispatcher = ioDispatcher,
            )
            lifecycle.activate()
            SessionStartResult(ManagedSession(lifecycle, snapshots), staleCleanup)
        } catch (failure: Throwable) {
            deleteTreeLogical(allocation.directory)
            throw failure
        }
    }

    suspend fun purgeStaleSessions(): CleanupReport = withContext(ioDispatcher) {
        ensureRoot()
        purgeStaleSessionsInternal()
    }

    private fun ensureRoot() {
        if (!root.exists() && !root.mkdirs()) throw SessionWorkspaceCreationException()
        if (!root.isDirectory) throw SessionWorkspaceCreationException()
    }

    private fun allocateSessionDirectory(): SessionDirectoryAllocation {
        repeat(MAX_ALLOCATION_ATTEMPTS) {
            val sessionId = sessionIdGenerator.next()
            val directory = safeRootChild("session-${sessionId.value}")
            if (directory.mkdir()) {
                directory.setLastModified(wallClock.now().epochMillis)
                return SessionDirectoryAllocation(sessionId, directory)
            }
            if (!directory.exists()) throw SessionWorkspaceCreationException()
        }
        throw SessionWorkspaceCreationException()
    }

    private fun purgeStaleSessionsInternal(): CleanupReport {
        val now = wallClock.now().epochMillis
        val candidates = root.listFiles()
            ?.filter { candidate ->
                candidate.name.startsWith(SESSION_PREFIX) &&
                    candidate.lastModified() > 0 &&
                    now >= candidate.lastModified() &&
                    now - candidate.lastModified() >= staleAfterMillis
            }
            .orEmpty()
        var completed = 0
        var failed = 0
        candidates.forEach { candidate ->
            val removed = runCatching { deleteTreeLogical(candidate) && !candidate.exists() }
                .getOrDefault(false)
            if (removed) completed += 1 else failed += 1
        }
        return CleanupReport(candidates.size, completed, failed)
    }

    private fun safeRootChild(name: String): File {
        val child = File(root, name).canonicalFile
        if (child.parentFile != root) throw SessionWorkspaceCreationException()
        return child
    }

    /** Boundary-checked logical deletion. A symlink escaping [root] is deleted, never traversed. */
    private fun deleteTreeLogical(node: File): Boolean {
        if (!node.exists()) return true
        val canonical = runCatching { node.canonicalFile }.getOrNull() ?: return false
        if (!canonical.isStrictlyWithin(root)) {
            return runCatching { node.delete() }.getOrDefault(false)
        }
        var childrenDeleted = true
        if (node.isDirectory) {
            val children = node.listFiles() ?: return false
            children.forEach { child ->
                childrenDeleted = deleteTreeLogical(child) && childrenDeleted
            }
        }
        val nodeDeleted = runCatching { node.delete() }.getOrDefault(false)
        return childrenDeleted && nodeDeleted
    }

    private fun File.isStrictlyWithin(parent: File): Boolean =
        path.startsWith(parent.path + File.separator)

    private data class SessionDirectoryAllocation(
        val sessionId: SessionId,
        val directory: File,
    )

    private companion object {
        const val SESSION_PREFIX = "session-"
        const val MAX_ALLOCATION_ATTEMPTS = 16
    }
}
