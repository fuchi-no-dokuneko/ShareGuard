package app.shareguard.canonical

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Best-effort logical cleanup/revalidation. It never requests network access. */
class MaintenanceWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? ShareGuardApplication ?: return Result.failure()
        return runCatching {
            application.container.sessionWorkspaceManager.purgeStaleSessions()
            application.container.integritySweep.run(clearUntrackedShareCache = false)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { if (runAttemptCount < MAXIMUM_RETRIES) Result.retry() else Result.failure() },
        )
    }

    private companion object {
        const val MAXIMUM_RETRIES = 3
    }
}
