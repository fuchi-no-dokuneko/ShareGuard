package app.shareguard.core.session

import app.shareguard.core.model.SessionId
import app.shareguard.core.model.SourceHandle
import app.shareguard.core.model.WallClockInstant
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileSessionWorkspaceManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `start purges stale incomplete workspace before reusing its session ID`() = runTest {
        val root = temporaryFolder.newFolder("sessions")
        val stale = File(root, "session-session-fixed").apply { mkdir() }
        File(stale, "partial.tmp").writeText("CANARY_TRANSIENT")
        stale.setLastModified(1_000)
        val manager = manager(
            root = root,
            now = 10_000,
            staleAfter = 5_000,
            sessionIds = listOf("session-fixed"),
        )

        val started = manager.startSession()

        assertThat(started.staleCleanup).isEqualTo(CleanupReport(1, 1, 0))
        assertThat(started.session.sessionId).isEqualTo(SessionId("session-fixed"))
        assertThat(root.listFiles()!!.map { it.name }).containsExactly("session-session-fixed")
        assertThat(root.listFiles()!!.single().listFiles()).isEmpty()
    }

    @Test
    fun `process death abandonment is purged by the next process without touching committed storage`() = runTest {
        val root = temporaryFolder.newFolder("process-death-sessions")
        val committedRoot = temporaryFolder.newFolder("process-death-committed")
        val committed = File(committedRoot, "saved-result.enc").apply { writeText("COMMITTED_BYTES") }
        val firstProcess = manager(
            root = root,
            now = 1_000,
            staleAfter = 5_000,
            sessionIds = listOf("abandoned-process"),
        )
        val abandoned = firstProcess.startSession().session
        abandoned.snapshots.sealAcceptedDirectText("PROCESS_DEATH_SOURCE_CANARY")
        val abandonedDirectory = root.listFiles()!!.single()
        assertThat(abandonedDirectory.walkTopDown().filter(File::isFile).toList()).isNotEmpty()
        abandonedDirectory.walkTopDown().forEach { it.setLastModified(1_000) }

        // Deliberately do not call complete/discard/failFatal: this is the process-death boundary.
        val nextProcess = manager(
            root = root,
            now = 10_000,
            staleAfter = 5_000,
            sessionIds = listOf("replacement-process"),
        )
        val restarted = nextProcess.startSession()

        assertThat(restarted.staleCleanup).isEqualTo(CleanupReport(1, 1, 0))
        assertThat(abandonedDirectory.exists()).isFalse()
        assertThat(root.listFiles()!!.map { it.name }).containsExactly("session-replacement-process")
        assertThat(committed.readText()).isEqualTo("COMMITTED_BYTES")
        restarted.session.lifecycle.discard()
    }

    @Test
    fun `wall-clock rollback does not age or purge a future-dated workspace`() = runTest {
        val root = temporaryFolder.newFolder("rollback-sessions")
        val future = File(root, "session-future").apply { mkdir() }
        future.setLastModified(20_000)
        val manager = manager(root, now = 10_000, staleAfter = 1, sessionIds = listOf("new-session"))

        val report = manager.purgeStaleSessions()

        assertThat(report.attempted).isEqualTo(0)
        assertThat(future.exists()).isTrue()
    }

    @Test
    fun `normal discard deletes session tree but never committed result outside root`() = runTest {
        val root = temporaryFolder.newFolder("discard-sessions")
        val committedRoot = temporaryFolder.newFolder("committed-results")
        val committed = File(committedRoot, "saved-result.bin").apply { writeText("keep") }
        val session = manager(root).startSession().session
        session.snapshots.sealAcceptedDirectText("temporary source")
        assertThat(root.listFiles()!!.single().listFiles()).isNotEmpty()

        val report = session.lifecycle.discard()

        assertThat(report.fullySuccessful).isTrue()
        assertThat(root.listFiles()).isEmpty()
        assertThat(committed.readText()).isEqualTo("keep")
    }

    @Test
    fun `fatal failure removes nested transient files with path-neutral report`() = runTest {
        val root = temporaryFolder.newFolder("fatal-sessions")
        val session = manager(root).startSession().session
        val sessionDirectory = root.listFiles()!!.single()
        val nested = File(sessionDirectory, "ocr/render").apply { mkdirs() }
        File(nested, "temporary.bin").writeText("sensitive")

        val report = session.lifecycle.failFatal()

        assertThat(report).isEqualTo(CleanupReport(1, 1, 0))
        assertThat(report.toString()).doesNotContain("ocr")
        assertThat(report.toString()).doesNotContain(root.absolutePath)
        assertThat(sessionDirectory.exists()).isFalse()
    }

    @Test
    fun `non-session directories are outside stale purge candidate set`() = runTest {
        val root = temporaryFolder.newFolder("scoped-sessions")
        val unrelated = File(root, "saved-results").apply { mkdir() }
        unrelated.setLastModified(1)
        val stale = File(root, "session-old").apply { mkdir() }
        stale.setLastModified(1)

        val report = manager(root, now = 100_000, staleAfter = 1).purgeStaleSessions()

        assertThat(report).isEqualTo(CleanupReport(1, 1, 0))
        assertThat(stale.exists()).isFalse()
        assertThat(unrelated.exists()).isTrue()
    }

    @Test
    fun `production sessions default to disabled diagnostics and debug sessions are in-memory`() = runTest {
        val productionRoot = temporaryFolder.newFolder("production-sessions")
        val debugRoot = temporaryFolder.newFolder("debug-sessions")

        val production = manager(productionRoot, debug = false).startSession().session
        val debug = manager(debugRoot, debug = true).startSession().session

        assertThat(production.lifecycle.diagnosticTrace)
            .isSameInstanceAs(DisabledSessionDiagnosticTrace)
        assertThat(debug.lifecycle.diagnosticTrace)
            .isInstanceOf(InMemorySessionDiagnosticTrace::class.java)
        production.lifecycle.discard()
        debug.lifecycle.discard()
    }

    @Test
    fun `workspace creation failure is generic and leaves no child workspace`() = runTest {
        val rootFile = temporaryFolder.newFile("not-a-directory")
        val manager = manager(rootFile)

        val failure = expectFailure(SessionWorkspaceCreationException::class.java) {
            manager.startSession()
        }

        assertThat(failure.message).doesNotContain(rootFile.absolutePath)
        assertThat(rootFile.isFile).isTrue()
    }

    @Test
    fun `secure random session IDs are opaque fresh values`() {
        val generator = SecureRandomSessionIdGenerator()

        val values = List(64) { generator.next().value }

        assertThat(values.toSet()).hasSize(64)
        assertThat(values.all { it.matches(Regex("s-[0-9a-f]{32}")) }).isTrue()
    }

    private fun manager(
        root: File,
        now: Long = 10_000,
        staleAfter: Long = 60_000,
        sessionIds: List<String> = listOf("session-test"),
        debug: Boolean = false,
    ): FileSessionWorkspaceManager {
        val idIterator = sessionIds.iterator()
        var sourceIndex = 0
        return FileSessionWorkspaceManager(
            workspaceRoot = root,
            importAnchorRecorder = ImportAnchorRecorder(
                wallClock = WallClockSource { WallClockInstant(now) },
            ),
            snapshotLimits = SnapshotLimits(1_024),
            staleAfterMillis = staleAfter,
            wallClock = WallClockSource { WallClockInstant(now) },
            sessionIdGenerator = SessionIdGenerator {
                check(idIterator.hasNext())
                SessionId(idIterator.next())
            },
            sourceHandleGenerator = SourceHandleGenerator {
                SourceHandle("source-${sourceIndex++}")
            },
            recheckDelay = NoSnapshotRecheckDelay,
            debugTraceEnabled = debug,
            ioDispatcher = Dispatchers.IO,
        )
    }

    private suspend fun <T : Throwable> expectFailure(
        type: Class<T>,
        block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (type.isInstance(failure)) return type.cast(failure)!!
            throw failure
        }
        throw AssertionError("Expected ${type.simpleName}")
    }
}
