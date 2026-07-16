package app.shareguard.canonical

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttp

/**
 * Process root. Long-lived objects are added through an explicit application container so source
 * content is never placed in a global singleton or diagnostic logger.
 */
class ShareGuardApplication : Application() {
    lateinit var container: ApplicationContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Loads OkHttp's packaged public-suffix database for standards URL analysis; no client is built.
        OkHttp.initialize(this)
        container = ApplicationContainer(this)
        applicationScope.launch {
            container.runStartupMaintenance()
        }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            MAINTENANCE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(12, TimeUnit.HOURS).build(),
        )
    }

    private companion object {
        const val MAINTENANCE_WORK = "shareguard-private-maintenance-v1"
    }
}
