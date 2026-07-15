package app.shareguard.core.session

import app.shareguard.core.model.BootSessionReference
import app.shareguard.core.model.BoundedDelayPolicy
import app.shareguard.core.model.BoundedDelayPurpose
import app.shareguard.core.model.DurationMillis
import app.shareguard.core.model.MonotonicInstant
import app.shareguard.core.model.SafeSummary
import app.shareguard.core.model.SourceHandle
import app.shareguard.core.model.WallClockInstant
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileSourceSnapshotStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `provider snapshot is copied delayed reopened sealed then anchored`() = runTest {
        val workspace = temporaryFolder.newFolder("session")
        val sequence = mutableListOf<String>()
        var wallCalls = 0
        val store = store(
            workspace = workspace,
            wallClock = WallClockSource {
                wallCalls += 1
                sequence += "anchor"
                WallClockInstant(10_000)
            },
            recheckDelay = SnapshotRecheckDelay { _, cancellation ->
                cancellation.throwIfCancellationRequested()
                assertThat(workspace.listFiles()!!.single().name).endsWith(".partial")
                sequence += "delay"
            },
        )
        val input = TrackingInputStream("authoritative bytes".encodeToByteArray())

        val snapshot = store.sealAcceptedProviderSource(input, enabledPolicy())

        assertThat(input.closed).isTrue()
        assertThat(wallCalls).isEqualTo(1)
        assertThat(sequence).containsExactly("delay", "anchor").inOrder()
        assertThat(workspace.listFiles()!!.single().name).endsWith(".sealed")
        assertThat(snapshot.verify().outcome).isEqualTo(SnapshotIntegrityOutcome.VALID)
        assertThat(snapshot.readVerified().decodeToString()).isEqualTo("authoritative bytes")
        assertThat(snapshot.descriptor.byteCount.value).isEqualTo(19)
        assertThat(snapshot.descriptor.importAnchor.monotonic).isEqualTo(MonotonicInstant(20_000_000))
    }

    @Test
    fun `direct text submission anchors only after internal copy is sealed`() = runTest {
        val workspace = temporaryFolder.newFolder("text-session")
        var anchorCalls = 0
        val store = store(
            workspace,
            WallClockSource { anchorCalls += 1; WallClockInstant(5_000) },
        )

        val snapshot = store.sealAcceptedDirectText("submitted text")

        assertThat(anchorCalls).isEqualTo(1)
        assertThat(snapshot.readVerified().decodeToString()).isEqualTo("submitted text")
        assertThat(snapshot.verify().isValid).isTrue()
    }

    @Test
    fun `accepted Android shared text uses the same immutable copy boundary`() = runTest {
        val workspace = temporaryFolder.newFolder("shared-text-session")
        val snapshot = store(workspace).sealAcceptedSharedText("shared text")

        assertThat(snapshot.readVerified().decodeToString()).isEqualTo("shared text")
        assertThat(snapshot.verify().isValid).isTrue()
    }

    @Test
    fun `one session accepts exactly one source and creates exactly one anchor`() = runTest {
        val workspace = temporaryFolder.newFolder("single-source-session")
        var anchorCalls = 0
        val store = store(
            workspace,
            WallClockSource { anchorCalls += 1; WallClockInstant(5_000) },
        )
        store.sealAcceptedDirectText("first")
        val rejectedInput = TrackingInputStream("second".encodeToByteArray())

        expectFailure(SnapshotAlreadyAcceptedException::class.java) {
            store.sealAcceptedProviderSource(rejectedInput, enabledPolicy())
        }

        assertThat(rejectedInput.closed).isTrue()
        assertThat(anchorCalls).isEqualTo(1)
        assertThat(workspace.listFiles()).hasLength(1)
    }

    @Test
    fun `rejected source leaves session able to retry acceptance`() = runTest {
        val workspace = temporaryFolder.newFolder("retry-source-session")
        var anchorCalls = 0
        val store = store(
            workspace = workspace,
            wallClock = WallClockSource { anchorCalls += 1; WallClockInstant(5_000) },
            maximumBytes = 4,
        )
        expectFailure(SnapshotResourceLimitException::class.java) {
            store.sealAcceptedDirectText("too long")
        }

        val accepted = store.sealAcceptedDirectText("okay")

        assertThat(accepted.readVerified().decodeToString()).isEqualTo("okay")
        assertThat(anchorCalls).isEqualTo(1)
    }

    @Test
    fun `same-length post-seal mutation is detected before decoder bytes are returned`() = runTest {
        val workspace = temporaryFolder.newFolder("mutated-session")
        val snapshot = store(workspace).sealAcceptedDirectText("safe text")
        val sealedFile = workspace.listFiles()!!.single()
        sealedFile.writeBytes("evil text".encodeToByteArray())

        assertThat(snapshot.verify().outcome).isEqualTo(SnapshotIntegrityOutcome.DIGEST_MISMATCH)
        val failure = expectFailure(SnapshotIntegrityException::class.java) { snapshot.readVerified() }
        assertThat(failure.outcome).isEqualTo(SnapshotIntegrityOutcome.DIGEST_MISMATCH)
        assertThat(failure.message).doesNotContain("evil text")
        assertThat(failure.message).doesNotContain(sealedFile.absolutePath)
    }

    @Test
    fun `length mutation and missing file have distinct content-free outcomes`() = runTest {
        val workspace = temporaryFolder.newFolder("length-session")
        val snapshot = store(workspace).sealAcceptedDirectText("1234")
        val sealedFile = workspace.listFiles()!!.single()
        sealedFile.appendText("5")
        assertThat(snapshot.verify().outcome).isEqualTo(SnapshotIntegrityOutcome.LENGTH_MISMATCH)

        sealedFile.delete()

        assertThat(snapshot.verify().outcome).isEqualTo(SnapshotIntegrityOutcome.MISSING)
    }

    @Test
    fun `oversized input aborts deletes partial file and never creates anchor`() = runTest {
        val workspace = temporaryFolder.newFolder("oversized-session")
        var anchorCalls = 0
        val input = TrackingInputStream(ByteArray(9) { 1 })
        val store = store(
            workspace = workspace,
            wallClock = WallClockSource { anchorCalls += 1; WallClockInstant(1) },
            maximumBytes = 8,
        )

        expectFailure(SnapshotResourceLimitException::class.java) {
            store.sealAcceptedProviderSource(input, enabledPolicy())
        }

        assertThat(input.closed).isTrue()
        assertThat(anchorCalls).isEqualTo(0)
        assertThat(workspace.listFiles()).isEmpty()
    }

    @Test
    fun `cancellation aborts seal cleans files and never anchors`() = runTest {
        val workspace = temporaryFolder.newFolder("cancelled-session")
        var anchorCalls = 0
        val store = store(
            workspace = workspace,
            wallClock = WallClockSource { anchorCalls += 1; WallClockInstant(1) },
        )

        expectFailure(SessionCancellationException::class.java) {
            store.sealAcceptedProviderSource(
                ByteArrayInputStream("private".encodeToByteArray()),
                enabledPolicy(),
                FixedCancellationSignal(true),
            )
        }

        assertThat(anchorCalls).isEqualTo(0)
        assertThat(workspace.listFiles()).isEmpty()
    }

    @Test
    fun `provider read failure is replaced by generic creation failure and cleaned`() = runTest {
        val workspace = temporaryFolder.newFolder("failed-session")
        val sensitiveMessage = "/private/provider/content-name.png"
        val failingInput = object : InputStream() {
            override fun read(): Int = throw IOException(sensitiveMessage)
        }

        val failure = expectFailure(SnapshotCreationException::class.java) {
            store(workspace).sealAcceptedProviderSource(failingInput, enabledPolicy())
        }

        assertThat(failure.message).doesNotContain(sensitiveMessage)
        assertThat(workspace.listFiles()).isEmpty()
    }

    @Test
    fun `snapshot metadata and string forms do not reveal handle digest timestamp or content`() = runTest {
        val workspace = temporaryFolder.newFolder("redacted-session")
        val snapshot = store(workspace).sealAcceptedDirectText("CANARY_PRIVATE_CONTENT")
        val descriptorText = snapshot.descriptor.toString()
        val snapshotText = snapshot.toString()

        assertThat(descriptorText).isEqualTo("SourceSnapshotDescriptor(metadata=redacted)")
        assertThat(descriptorText).doesNotContain(snapshot.descriptor.sourceHandle.value)
        assertThat(descriptorText).doesNotContain(snapshot.descriptor.digest.sha256)
        assertThat(descriptorText).doesNotContain("10000")
        assertThat(snapshotText).doesNotContain("CANARY_PRIVATE_CONTENT")
        assertThat(snapshotText).doesNotContain(workspace.absolutePath)
    }

    @Test
    fun `logical deletion is idempotent and makes snapshot unavailable`() = runTest {
        val workspace = temporaryFolder.newFolder("deletion-session")
        val snapshot = store(workspace).sealAcceptedDirectText("temporary")

        assertThat(snapshot.deleteLogical()).isEqualTo(LogicalSnapshotDeletionResult.DELETED)
        assertThat(snapshot.deleteLogical()).isEqualTo(LogicalSnapshotDeletionResult.ALREADY_ABSENT)
        assertThat(snapshot.verify().outcome).isEqualTo(SnapshotIntegrityOutcome.MISSING)
    }

    private fun store(
        workspace: File,
        wallClock: WallClockSource = WallClockSource { WallClockInstant(10_000) },
        maximumBytes: Long = 1_024,
        recheckDelay: SnapshotRecheckDelay = NoSnapshotRecheckDelay,
    ) = FileSourceSnapshotStore(
        workspaceDirectory = workspace,
        importAnchorRecorder = ImportAnchorRecorder(
            wallClock = wallClock,
            monotonicClock = MonotonicClockSource { MonotonicInstant(20_000_000) },
            bootSessionReferenceSource = BootSessionReferenceSource { BootSessionReference("boot-test") },
        ),
        limits = SnapshotLimits(maximumBytes = maximumBytes, copyBufferBytes = 3),
        sourceHandleGenerator = SourceHandleGenerator { SourceHandle("source-test") },
        recheckDelay = recheckDelay,
        ioDispatcher = Dispatchers.IO,
    )

    private fun enabledPolicy() = BoundedDelayPolicy(
        enabled = true,
        purpose = BoundedDelayPurpose.PROVIDER_SNAPSHOT_RECHECK,
        minimum = DurationMillis(0),
        maximum = DurationMillis(0),
        validationReference = SafeSummary("test-policy"),
    )

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class FixedCancellationSignal(
        override val isCancellationRequested: Boolean,
    ) : CancellationSignal

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
