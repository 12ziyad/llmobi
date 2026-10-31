package app.llmobi.update

import android.content.Context
import androidx.work.*
import app.llmobi.download.DownloadSpec
import app.llmobi.download.DownloadFailure
import app.llmobi.download.ResumableDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val NAME = "app_update_download"
        private val starts = Mutex()
        fun currentInfo(ctx: Context, infos: List<WorkInfo>): WorkInfo? {
            val current = ctx.getSharedPreferences("llmobi_updates", Context.MODE_PRIVATE).getString("work_id", null)
            return infos.firstOrNull { it.id.toString() == current }
                ?: if (current == null) infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull() else null
        }
        suspend fun start(ctx: Context, info: UpdateInfo) = withContext(Dispatchers.IO) { starts.withLock {
            val manager = WorkManager.getInstance(ctx)
            if (manager.getWorkInfosForUniqueWork(NAME).get().any { !it.state.isFinished }) return@withLock
            val work = OneTimeWorkRequestBuilder<AppUpdateWorker>()
                .setInputData(workDataOf("release" to info.toJson()))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            ctx.getSharedPreferences("llmobi_updates", Context.MODE_PRIVATE).edit().putString("work_id", work.id.toString()).commit()
            manager.enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, work).result.get()
        } }
    }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val info = UpdateInfo.parse(inputData.getString("release") ?: error("No update selected"))
            java.io.File(AppUpdates.file(applicationContext, info).path + ".verified").delete()
            val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
            val target = ResumableDownload(client).download(DownloadSpec(info.url, info.bytes, info.sha256, gguf = false), AppUpdates.file(applicationContext, info)) { have, total ->
                setProgress(workDataOf("percent" to (100 * have / total).toInt()))
            }
            AppUpdates.verifyPackage(applicationContext, info, target)
            java.io.File(target.path + ".verified").writeText(info.sha256)
            Result.success(workDataOf("versionCode" to info.versionCode))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val retryable = if (e is DownloadFailure) e.retryable else e is java.io.IOException
            if (retryable && runAttemptCount < 4) Result.retry()
            else Result.failure(workDataOf("error" to (e.message?.take(300) ?: "Update failed. Try again when connected.")))
        }
    }
}
