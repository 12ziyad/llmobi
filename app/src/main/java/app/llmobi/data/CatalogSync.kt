package app.llmobi.data

import android.content.Context
import android.util.Log
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Keeps the on-device catalog in step with the Worker.
 *
 * The app ships a bundled list so the store works on a first launch with no
 * signal at all. This replaces it when the network allows, which is what lets a
 * model be added, a recommended quantisation changed, or a dead link pulled
 * without shipping an app update.
 *
 * Every failure path ends at the bundled list. A store that shows slightly stale
 * models is fine; one that shows nothing is not.
 */
object CatalogSync {

    private val refreshLock = Mutex()
    private const val TAG = "CatalogSync"
    private const val ENDPOINT = "https://llmobi-api.gpmai.workers.dev/v1/catalog"
    private const val CACHE_FILE = "catalog.json"
    private const val ETAG_KEY = "catalog_etag"
    private const val CHECKED_KEY = "catalog_checked"
    private const val MIN_INTERVAL_MS = 6L * 60 * 60 * 1000  // six hours

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Cached list if we have one, otherwise the list compiled into the app. */
    fun load(ctx: Context): List<ModelEntry> {
        val f = File(ctx.filesDir, CACHE_FILE)
        if (!f.exists() && !File(f.path + ".bak").exists()) return Catalog.models
        return runCatching { parse(AtomicFile(f).openRead().bufferedReader().use { it.readText() }) }
            .onFailure { Log.w(TAG, "cached catalog unreadable, using bundled: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: Catalog.models
    }

    /**
     * Refreshes in the background. Returns true only when the list actually
     * changed, so callers know whether it is worth redrawing.
     */
    suspend fun refresh(ctx: Context, force: Boolean = false): Boolean = refreshLock.withLock { withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("llmobi", Context.MODE_PRIVATE)
        val since = System.currentTimeMillis() - prefs.getLong(CHECKED_KEY, 0)
        if (!force && since in 0 until MIN_INTERVAL_MS) return@withContext false

        try {
            val cached = runCatching { parse(AtomicFile(File(ctx.filesDir, CACHE_FILE)).openRead().bufferedReader().use { it.readText() }) }.getOrNull()
            val etag = if (!cached.isNullOrEmpty()) prefs.getString(ETAG_KEY, null) else null
            val req = Request.Builder().url(ENDPOINT)
                .apply { if (etag != null) header("If-None-Match", etag) }
                .build()

            client.newCall(req).execute().use { resp ->

                if (resp.code == 304) {
                    prefs.edit().putLong(CHECKED_KEY, System.currentTimeMillis()).apply()
                    Log.i(TAG, "catalog unchanged")
                    return@withContext false
                }
                if (!resp.isSuccessful) {
                    Log.w(TAG, "catalog fetch failed: ${resp.code}")
                    return@withContext false
                }

                val input = resp.body?.byteStream() ?: return@withContext false
                val bytes = input.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        require(out.size() + n <= 1_048_576) { "Catalog too large" }
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
                val body = bytes.toString(Charsets.UTF_8)
                // Parse before writing: a half-valid file cached to disk would
                // break the store on every launch until the next refresh.
                val parsed = parse(body)
                if (parsed.isEmpty()) {
                    Log.w(TAG, "catalog parsed to nothing, keeping previous")
                    return@withContext false
                }

                writeAtomic(File(ctx.filesDir, CACHE_FILE), body)
                prefs.edit().putLong(CHECKED_KEY, System.currentTimeMillis())
                    .putString(ETAG_KEY, resp.header("ETag")).apply()
                Log.i(TAG, "catalog updated: ${parsed.size} models")
                return@withContext true
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Exception) {
            Log.w(TAG, "catalog refresh skipped: ${t.message}")
            return@withContext false
        }
    } }

    private fun parse(json: String) = CatalogCodec.parse(json).map { model ->
        val bundled = Catalog.byId(model.id)
        if (model.sha256.isBlank() && bundled != null && bundled.url == model.url && bundled.fileBytes == model.fileBytes)
            model.copy(sha256 = bundled.sha256) else model
    }

    @Synchronized
    fun remembered(ctx: Context): List<ModelEntry> = runCatching {
        parse(AtomicFile(File(ctx.filesDir, "installed-catalog.json")).openRead().bufferedReader().use { it.readText() })
    }.getOrDefault(emptyList())

    fun find(ctx: Context, id: String): ModelEntry? =
        remembered(ctx).firstOrNull { it.id == id } ?: load(ctx).firstOrNull { it.id == id } ?: Catalog.byId(id)

    @Synchronized
    fun remember(ctx: Context, model: ModelEntry) {
        writeAtomic(File(ctx.filesDir, "installed-catalog.json"),
            CatalogCodec.encode(remembered(ctx).filterNot { it.id == model.id } + model))
    }

    private fun writeAtomic(file: File, body: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(body.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (t: Throwable) { atomic.failWrite(stream); throw t }
    }
}
