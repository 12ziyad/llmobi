package app.llmobi.update

import android.content.Context
import androidx.work.*
import app.llmobi.data.CatalogSync
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val NOW = "refresh_online"
        fun schedule(ctx: Context) {
            val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val wm = WorkManager.getInstance(ctx)
            wm.enqueueUniquePeriodicWork("refresh_periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RefreshWorker>(6, TimeUnit.HOURS).setConstraints(connected).build())
        }
        fun request(ctx: Context, force: Boolean = false) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RefreshWorker>()
                    .setInputData(workDataOf("force" to force))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
    override suspend fun doWork(): Result {
        return try {
            val force = inputData.getBoolean("force", false)
            CatalogSync.refresh(applicationContext, force)
            AppUpdates.check(applicationContext, force)
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}
