package app.llmobi.data

import android.content.Context
import android.util.Log
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
        if (!f.exists()) return Catalog.models
        return runCatching { parse(f.readText()) }
            .onFailure { Log.w(TAG, "cached catalog unreadable, using bundled: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: Catalog.models
    }

    /**
     * Refreshes in the background. Returns true only when the list actually
     * changed, so callers know whether it is worth redrawing.
     */
    suspend fun refresh(ctx: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("llmobi", Context.MODE_PRIVATE)
        val since = System.currentTimeMillis() - prefs.getLong(CHECKED_KEY, 0)
        if (!force && since < MIN_INTERVAL_MS) return@withContext false

        try {
            val etag = prefs.getString(ETAG_KEY, null)
            val req = Request.Builder().url(ENDPOINT)
                .apply { if (etag != null) header("If-None-Match", etag) }
                .build()

            client.newCall(req).execute().use { resp ->
                prefs.edit().putLong(CHECKED_KEY, System.currentTimeMillis()).apply()

                if (resp.code == 304) {
                    Log.i(TAG, "catalog unchanged")
                    return@withContext false
                }
                if (!resp.isSuccessful) {
                    Log.w(TAG, "catalog fetch failed: ${resp.code}")
                    return@withContext false
                }

                val body = resp.body?.string().orEmpty()
                // Parse before writing: a half-valid file cached to disk would
                // break the store on every launch until the next refresh.
                val parsed = parse(body)
                if (parsed.isEmpty()) {
                    Log.w(TAG, "catalog parsed to nothing, keeping previous")
                    return@withContext false
                }

                File(ctx.filesDir, CACHE_FILE).writeText(body)
                resp.header("ETag")?.let { prefs.edit().putString(ETAG_KEY, it).apply() }
                Log.i(TAG, "catalog updated: ${parsed.size} models")
                return@withContext true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "catalog refresh skipped: ${t.message}")
            return@withContext false
        }
    }

    private fun parse(json: String): List<ModelEntry> {
        val root = JSONObject(json)
        val arr: JSONArray = root.optJSONArray("models") ?: return emptyList()
        val out = ArrayList<ModelEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val url = o.optString("url")
            // A record without an id or a download link is unusable; skip it
            // rather than letting one bad row take the whole catalog down.
            if (id.isBlank() || url.isBlank()) continue
            out += ModelEntry(
                id = id,
                name = o.optString("name", id),
                tagline = o.optString("tagline", ""),
                tier = tierOf(o.optString("tier")),
                category = o.optString("category", "general"),
                iconLetter = o.optString("iconLetter", id.take(1).uppercase()),
                colorStart = colorOf(o.optString("colorStart"), 0xFF7BD4F5),
                colorEnd = colorOf(o.optString("colorEnd"), 0xFF3C8FD0),
                sizeLabel = o.optString("sizeLabel", ""),
                speedHint = o.optString("speedHint", "steady"),
                fileBytes = o.optLong("fileBytes", 0L),
                minRamMb = o.optInt("minRamMb", 2000),
                ctxDefault = o.optInt("ctxDefault", 4096),
                arch = o.optString("arch", ""),
                quant = o.optString("quant", ""),
                url = url,
                sha256 = o.optString("sha256", ""),
                license = o.optString("license", ""),
            )
        }
        return out
    }

    private fun tierOf(s: String): Tier = when (s.lowercase()) {
        "tiny" -> Tier.TINY
        "fast" -> Tier.FAST
        "powerful" -> Tier.POWERFUL
        "pro" -> Tier.PRO
        "extreme" -> Tier.EXTREME
        else -> Tier.FAST
    }

    /** "#7BD4F5" -> 0xFF7BD4F5. Falls back rather than throwing on junk. */
    private fun colorOf(s: String, fallback: Long): Long =
        runCatching { 0xFF000000L or s.removePrefix("#").toLong(16) }.getOrDefault(fallback)
}
