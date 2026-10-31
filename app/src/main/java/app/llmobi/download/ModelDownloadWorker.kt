package app.llmobi.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.llmobi.R
import app.llmobi.data.Catalog
import app.llmobi.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads one model file.
 *
 * The parts that matter on a real phone:
 *  - resumes with a Range header instead of restarting gigabytes
 *  - runs as a foreground service so Android does not kill it on screen-off
 *  - writes to a .part file and only renames on success, so a half file can
 *    never look like an installed model
 */
class ModelDownloadWorker(ctx: Context, params: androidx.work.WorkerParameters) :
    CoroutineWorker(ctx, params) {

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_DONE_BYTES = "done_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_ERROR = "error"
        const val CHANNEL = "llmobi_downloads"
        private const val TAG = "ModelDownload"

        fun tagFor(modelId: String) = "download_$modelId"

        private val starts = Mutex()
        private val fileLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        fun fileLock(id: String) = fileLocks.getOrPut(id) { Mutex() }
        fun currentInfo(ctx: Context, id: String, infos: List<WorkInfo>): WorkInfo? {
            val current = ctx.getSharedPreferences("llmobi", Context.MODE_PRIVATE).getString("work_$id", null)
            return infos.firstOrNull { it.id.toString() == current }
                ?: if (current == null) infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull() else null
        }
        suspend fun start(ctx: Context, modelId: String, wifiOnly: Boolean) = withContext(Dispatchers.IO) { starts.withLock {
            require(app.llmobi.data.CatalogCodec.validId(modelId))
            val manager = WorkManager.getInstance(ctx)
            if (manager.getWorkInfosForUniqueWork(tagFor(modelId)).get().any { !it.state.isFinished }) return@withLock
            val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(KEY_MODEL_ID to modelId))
                .addTag(tagFor(modelId))
                .addTag("model_download")
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build()
                )
                .build()
            ctx.getSharedPreferences("llmobi", Context.MODE_PRIVATE).edit().putString("work_$modelId", req.id.toString()).commit()
            manager.enqueueUniqueWork(tagFor(modelId), ExistingWorkPolicy.KEEP, req).result.get()
        } }

        fun cancel(ctx: Context, modelId: String) {
            WorkManager.getInstance(ctx).cancelUniqueWork(tagFor(modelId))
        }

        fun observe(ctx: Context, modelId: String) =
            WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkLiveData(tagFor(modelId))

        fun modelsDir(ctx: Context): File =
            File(ctx.filesDir, "models").apply { mkdirs() }

        fun fileFor(ctx: Context, modelId: String): File =
            File(modelsDir(ctx), "$modelId.gguf").also { require(app.llmobi.data.CatalogCodec.validId(modelId)) }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@withContext Result.failure()
        if (!app.llmobi.data.CatalogCodec.validId(modelId)) return@withContext Result.failure()
        val lock = fileLock(modelId)
        lock.lock()
        try {
        app.llmobi.data.CatalogSync.refresh(applicationContext)
        val model = app.llmobi.data.CatalogSync.load(applicationContext).firstOrNull { it.id == modelId }
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "This model is no longer in the library. Choose another model."))
        try {
            setForeground(notification(model.name, 0))
            val target = ResumableDownload(client).download(
                DownloadSpec(model.url, model.fileBytes, model.sha256), fileFor(applicationContext, modelId),
            ) { have, total ->
                val pct = (have * 100 / total).toInt().coerceIn(0, 100)
                setProgress(workDataOf(KEY_PROGRESS to pct, KEY_DONE_BYTES to have, KEY_TOTAL_BYTES to total))
                setForeground(notification(model.name, pct))
            }
            app.llmobi.data.CatalogSync.remember(applicationContext, model)
            Store(applicationContext).markInstalled(modelId, target.absolutePath, target.length())
            Result.success(workDataOf(KEY_PROGRESS to 100))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            Log.e(TAG, "Download failed for $modelId", failure)
            val retryable = if (failure is DownloadFailure) failure.retryable else failure is java.io.IOException
            val message = if (failure is DownloadFailure) failure.message else
                "The download was interrupted. Check your connection and retry; your progress is saved."
            if (retryable && runAttemptCount < 4) {
                setProgress(workDataOf(KEY_ERROR to (message ?: "Waiting to retry the download.")))
                Result.retry()
            } else Result.failure(workDataOf(KEY_ERROR to message))
        }
        } finally { lock.unlock() }
    }

    private fun notification(modelName: String, pct: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    applicationContext.getString(R.string.download_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = applicationContext.getString(R.string.download_channel_desc) }
            )
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("Installing $modelName")
            .setContentText(if (pct > 0) "$pct%" else "Starting...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(modelName.hashCode(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(modelName.hashCode(), n)
        }
    }
}

/** Snapshot of a download for the UI. */
data class DownloadState(
    val running: Boolean = false,
    val percent: Int = 0,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
) {
    companion object {
        fun from(infos: List<WorkInfo>?): DownloadState {
            val info = infos?.firstOrNull() ?: return DownloadState()
            return when (info.state) {
                WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> DownloadState(
                    running = true,
                    percent = info.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0),
                    doneBytes = info.progress.getLong(ModelDownloadWorker.KEY_DONE_BYTES, 0),
                    totalBytes = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0),
                )
                WorkInfo.State.FAILED -> DownloadState(
                    error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                        ?: "The download stopped. Tap install to try again."
                )
                WorkInfo.State.SUCCEEDED -> DownloadState(percent = 100)
                else -> DownloadState()
            }
        }
    }
}
