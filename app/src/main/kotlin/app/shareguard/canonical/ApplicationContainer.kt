package app.shareguard.canonical

import android.content.Context
import app.shareguard.core.model.DurationMillis
import app.shareguard.core.security.AndroidKeystoreAesGcmKeyProvider
import app.shareguard.core.session.AndroidMonotonicClockSource
import app.shareguard.core.session.FileSessionWorkspaceManager
import app.shareguard.core.session.ImportAnchorRecorder
import app.shareguard.core.session.SnapshotLimits
import app.shareguard.core.session.SystemWallClockSource
import app.shareguard.core.storage.AppPrivateStorageLayout
import app.shareguard.core.storage.ManagedShareCache
import app.shareguard.core.storage.PersistentStoreIntegritySweep
import app.shareguard.core.storage.SavedResultDatabase
import app.shareguard.core.storage.SavedResultDeletionService
import app.shareguard.core.storage.SavedResultRepository
import app.shareguard.core.storage.SavedResultRevalidator
import app.shareguard.core.storage.ShareCachePolicy
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-owned services. None retain source content, provider URIs, or destination identities. */
class ApplicationContainer(context: Context) {
    private val appContext = context.applicationContext
    private val layout = AppPrivateStorageLayout.from(appContext)
    private val database = SavedResultDatabase.open(appContext)
    private val keyProvider = AndroidKeystoreAesGcmKeyProvider()

    val repository = SavedResultRepository(database, layout, keyProvider)
    val revalidator = SavedResultRevalidator(repository)
    val managedShareCache = ManagedShareCache(
        repository = repository,
        revalidator = revalidator,
        policy = ShareCachePolicy(DurationMillis(MANAGED_SHARE_LIFETIME_MILLIS)),
    )
    val deletionService = SavedResultDeletionService(repository, managedShareCache)
    val integritySweep = PersistentStoreIntegritySweep(
        repository = repository,
        revalidator = revalidator,
        deletionService = deletionService,
        managedShareCache = managedShareCache,
    )

    private val bootReferenceSource = EstimatedBootSessionReferenceSource()
    private val importAnchorRecorder = ImportAnchorRecorder(
        wallClock = SystemWallClockSource,
        monotonicClock = AndroidMonotonicClockSource,
        bootSessionReferenceSource = bootReferenceSource,
    )
    val sessionWorkspaceManager = FileSessionWorkspaceManager(
        workspaceRoot = File(appContext.cacheDir, "transient-sessions-v1"),
        importAnchorRecorder = importAnchorRecorder,
        snapshotLimits = SnapshotLimits(MAXIMUM_SOURCE_BYTES),
        staleAfterMillis = STALE_SESSION_MILLIS,
        wallClock = SystemWallClockSource,
        debugTraceEnabled = BuildConfig.DEBUG,
    )

    private val startupCleanup = CompletableDeferred<Boolean>()
    private val startupMaintenanceMutex = Mutex()

    suspend fun runStartupMaintenance(): Boolean = startupMaintenanceMutex.withLock {
        if (startupCleanup.isCompleted) return@withLock startupCleanup.await()
        val completed = runCatching {
            val sessionCleanup = sessionWorkspaceManager.purgeStaleSessions()
            val sweep = integritySweep.run()
            sessionCleanup.failed == 0 && sweep.unresolvedReferenceGroups == 0
        }.getOrDefault(false)
        startupCleanup.complete(completed)
        completed
    }

    suspend fun awaitStartupMaintenance(): Boolean = startupCleanup.await()

    private companion object {
        const val MAXIMUM_SOURCE_BYTES = 32L * 1024L * 1024L
        const val STALE_SESSION_MILLIS = 24L * 60L * 60L * 1_000L
        const val MANAGED_SHARE_LIFETIME_MILLIS = 10L * 60L * 1_000L
    }
}
