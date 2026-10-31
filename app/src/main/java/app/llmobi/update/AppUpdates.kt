package app.llmobi.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.llmobi.BuildConfig
import app.llmobi.download.ResumableDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object AppUpdates {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("llmobi_updates", Context.MODE_PRIVATE)
    fun cached(ctx: Context): UpdateInfo? = runCatching { UpdateInfo.parse(prefs(ctx).getString("release", null) ?: return null) }
        .getOrNull()?.takeIf { it.versionCode > BuildConfig.VERSION_CODE && it.minSdk <= Build.VERSION.SDK_INT }

    suspend fun check(ctx: Context, force: Boolean = false): Unit = withContext(Dispatchers.IO) {
        val prefs = prefs(ctx)
        val age = System.currentTimeMillis() - prefs.getLong("checked", 0)
        if (!force && age in 0 until 6 * 60 * 60 * 1000L) return@withContext
        client.newCall(Request.Builder().url("https://llmobi-api.gpmai.workers.dev/v1/release").build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Could not check for app updates")
            val body = response.body ?: throw IOException("Empty update response")
            val bytes = body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    require(out.size() + n <= 16384) { "Update response too large" }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            require(root.has("release"))
            val info = root.optJSONObject("release")?.let { UpdateInfo.parse(it.toString()) }
            val oldCode = cached(ctx)?.versionCode
            prefs.edit().putString("release", info?.toJson()).putLong("checked", System.currentTimeMillis()).apply()
            if (info != null && info.versionCode > BuildConfig.VERSION_CODE && info.minSdk <= Build.VERSION.SDK_INT && oldCode != info.versionCode) {
                runCatching { notifyAvailable(ctx, info) }
            }
        }
    }

    private fun notifyAvailable(ctx: Context, info: UpdateInfo) {
        if (!androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.createNotificationChannel(android.app.NotificationChannel("app_updates", "App updates", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val pending = android.app.PendingIntent.getActivity(ctx, 100,
            Intent(ctx, app.llmobi.MainActivity::class.java).putExtra("open_updates", true),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        manager.notify(100, androidx.core.app.NotificationCompat.Builder(ctx, "app_updates")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("LLMobi ${info.versionName} is available")
            .setContentText("Tap to review the update. Your chats and models are kept.")
            .setAutoCancel(true).setContentIntent(pending).build())
    }

    fun file(ctx: Context, info: UpdateInfo) = File(File(ctx.filesDir, "updates"), "llmobi-${info.versionCode}.apk")

    fun isReady(ctx: Context, info: UpdateInfo): Boolean = runCatching {
        file(ctx, info).length() == info.bytes && File(file(ctx, info).path + ".verified").readText() == info.sha256
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    fun verifyPackage(ctx: Context, info: UpdateInfo, file: File) {
        require(file.length() == info.bytes && ResumableDownload.sha256(file).equals(info.sha256, true)) { "The update failed its integrity check." }
        val pm = ctx.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
            ?: error("Android could not read the update.")
        val installed = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
        require(archive.packageName == ctx.packageName && archive.versionCode == info.versionCode && archive.versionCode > installed.versionCode) { "This update is not a newer version of LLMobi." }
        val expected = installed.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        require(expected.isNotEmpty() && archive.signatures?.map { it.toCharsString() }?.toSet() == expected) { "This update is not signed by LLMobi's release key." }
    }

    /** Only called after the person taps Install. Android handles the final confirmation. */
    fun openInstaller(ctx: Context, info: UpdateInfo): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file(ctx, info))
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }
}
