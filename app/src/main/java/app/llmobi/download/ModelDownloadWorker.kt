package app.llmobi.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
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
import kotlinx.coroutines.withContext
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

        fun tagFor(modelId: String) = "download_$modelId"

        fun start(ctx: Context, modelId: String, wifiOnly: Boolean) {
            val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(KEY_MODEL_ID to modelId))
                .addTag(tagFor(modelId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(tagFor(modelId), ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(ctx: Context, modelId: String) {
            WorkManager.getInstance(ctx).cancelUniqueWork(tagFor(modelId))
        }

        fun observe(ctx: Context, modelId: String) =
            WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkLiveData(tagFor(modelId))

        fun modelsDir(ctx: Context): File =
            File(ctx.filesDir, "models").apply { mkdirs() }

        fun fileFor(ctx: Context, modelId: String): File =
            File(modelsDir(ctx), "$modelId.gguf")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@withContext Result.failure()
        val model = Catalog.byId(modelId) ?: return@withContext Result.failure()

        val target = fileFor(applicationContext, modelId)
        val part = File(target.absolutePath + ".part")

        // Refuse early rather than filling the phone and failing at 97%.
        val free = applicationContext.filesDir.usableSpace
        if (free < model.fileBytes - part.length() + 256L * 1024 * 1024) {
            return@withContext Result.failure(
                workDataOf(KEY_ERROR to "Not enough space. Free up about ${model.sizeLabel} and try again.")
            )
        }

        setForeground(notification(model.name, 0))

        try {
            var have = if (part.exists()) part.length() else 0L

            val req = Request.Builder()
                .url(model.url)
                .apply { if (have > 0) header("Range", "bytes=$have-") }
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.code == 416) {
                    // Server says we already have the whole thing.
                    have = part.length()
                } else if (!resp.isSuccessful) {
                    return@withContext Result.retry()
                }

                // A 200 to a ranged request means the server ignored it - start over.
                if (have > 0 && resp.code == 200) {
                    part.delete()
                    have = 0
                }

                val body = resp.body ?: return@withContext Result.retry()
                val total = if (have > 0) have + body.contentLength() else body.contentLength()
                val totalBytes = if (total > 0) total else model.fileBytes

                body.byteStream().use { input ->
                    java.io.RandomAccessFile(part, "rw").use { out ->
                        out.seek(have)
                        val buf = ByteArray(256 * 1024)
                        var lastReport = 0L
                        while (true) {
                            if (isStopped) return@withContext Result.failure()
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            have += n

                            val now = System.currentTimeMillis()
                            if (now - lastReport > 400) {
                                lastReport = now
                                val pct = ((have * 100) / totalBytes.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                                setProgress(
                                    Data.Builder()
                                        .putInt(KEY_PROGRESS, pct)
                                        .putLong(KEY_DONE_BYTES, have)
                                        .putLong(KEY_TOTAL_BYTES, totalBytes)
                                        .build()
                                )
                                setForeground(notification(model.name, pct))
                            }
                        }
                    }
                }
            }

            // Verify only when the catalog gave us a hash. An empty hash means the
            // ingest job has not filled it in yet - do not block the user on that.
            if (model.sha256.isNotBlank()) {
                val actual = sha256(part)
                if (!actual.equals(model.sha256, ignoreCase = true)) {
                    part.delete()
                    return@withContext Result.failure(
                        workDataOf(KEY_ERROR to "The downloaded file was damaged. Please try again.")
                    )
                }
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                return@withContext Result.failure(workDataOf(KEY_ERROR to "Could not save the model file."))
            }

            Store(applicationContext).markInstalled(modelId, target.absolutePath, target.length())
            Result.success(workDataOf(KEY_PROGRESS to 100))
        } catch (t: Throwable) {
            // The .part file stays on disk on purpose: next attempt resumes from it.
            Result.retry()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = s.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
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
