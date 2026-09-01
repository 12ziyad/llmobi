package app.llmobi.safety

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The things that protect a phone we have never seen.
 *
 * Only one model has ever been loaded on real hardware here. Nineteen others are
 * listed with verified sizes and nothing more, and they will first run on
 * somebody else's phone, with less free memory than we assumed, while other apps
 * fight for it. Everything below assumes that goes wrong and tries to make the
 * failure survivable rather than fatal.
 */
object Safety {

    private const val TAG = "Safety"
    private const val PREFS = "llmobi"
    private const val KEY_LOADING = "loading_model_id"
    private const val KEY_FAILS = "load_fail_"
    private const val CRASH_FILE = "last_crash.txt"

    /** Give up on a model after this many consecutive failed loads. */
    private const val MAX_FAILS = 2

    // ------------------------------------------------------------ crash loop

    /**
     * Marks a load as in progress.
     *
     * If the process dies inside llama.cpp - and on a phone short of memory the
     * kernel will kill it outright, with no exception to catch - this flag is
     * still set on the next launch. That is how we know the last attempt was
     * fatal rather than merely slow, and it is the difference between a bad
     * model and an app that crashes every single time it opens.
     */
    fun beginLoad(ctx: Context, modelId: String) {
        prefs(ctx).edit().putString(KEY_LOADING, modelId).apply()
    }

    fun endLoad(ctx: Context, modelId: String, ok: Boolean) {
        val p = prefs(ctx)
        val e = p.edit().remove(KEY_LOADING)
        if (ok) e.remove(KEY_FAILS + modelId)
        else e.putInt(KEY_FAILS + modelId, failures(ctx, modelId) + 1)
        e.apply()
    }

    /** Model id that was mid-load when the process last died, if any. */
    fun crashedOn(ctx: Context): String? = prefs(ctx).getString(KEY_LOADING, null)

    /** Called once at startup: converts an interrupted load into a counted failure. */
    fun recordCrashIfInterrupted(ctx: Context) {
        val id = crashedOn(ctx) ?: return
        val n = failures(ctx, id) + 1
        prefs(ctx).edit()
            .remove(KEY_LOADING)
            .putInt(KEY_FAILS + id, n)
            .apply()
        Log.w(TAG, "previous launch died while loading $id (failure $n)")
    }

    fun failures(ctx: Context, modelId: String): Int = prefs(ctx).getInt(KEY_FAILS + modelId, 0)

    fun isBlocked(ctx: Context, modelId: String): Boolean = failures(ctx, modelId) >= MAX_FAILS

    /** Lets someone override the block, because our guess about their phone can be wrong. */
    fun clearFailures(ctx: Context, modelId: String) {
        prefs(ctx).edit().remove(KEY_FAILS + modelId).apply()
    }

    // ------------------------------------------------------------ memory

    data class Plan(val ok: Boolean, val contextSize: Int, val reason: String?)

    /**
     * Decides how - or whether - to load, using memory as it is right now rather
     * than as it was when the store was drawn.
     *
     * A smaller context is the cheapest lever available: the KV cache scales with
     * it, so halving the window can be the difference between loading and being
     * killed. Better a model that remembers less than one that takes the app down.
     */
    fun planLoad(availableMb: Int, needMb: Int, wantContext: Int): Plan {
        val headroom = availableMb - needMb

        if (headroom >= 0) return Plan(true, wantContext, null)

        // Short by a little: shrink the context and try anyway.
        if (headroom > -400 && wantContext > 1024) {
            return Plan(true, 1024, "Running with a shorter memory to fit the space left.")
        }

        return Plan(
            false,
            wantContext,
            "This model needs about ${needMb / 1024} GB of free memory and there is " +
                "roughly ${(availableMb / 1024.0 * 10).toInt() / 10.0} GB right now. " +
                "Close some apps and try again, or pick a smaller AI.",
        )
    }

    // ------------------------------------------------------------ crash log

    /**
     * Writes the last crash to a file inside the app.
     *
     * Deliberately local. Sending crashes to a server would be the easy way to
     * find out what breaks, and it would also be the first thing this app does
     * that contradicts its own promise. The report is shown in Settings with a
     * copy button instead, so a person can choose to paste it into a bug report.
     */
    fun installCrashHandler(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            runCatching {
                val sw = StringWriter()
                err.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(app.filesDir, CRASH_FILE).writeText(
                    buildString {
                        appendLine("LLMobi crash report")
                        appendLine(stamp)
                        appendLine("thread: ${thread.name}")
                        appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                        appendLine("android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                        appendLine("abi: ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
                        appendLine("model loading: ${crashedOn(app) ?: "none"}")
                        appendLine()
                        append(sw.toString())
                    }
                )
            }
            previous?.uncaughtException(thread, err)
        }
    }

    fun lastCrash(ctx: Context): String? {
        val f = File(ctx.filesDir, CRASH_FILE)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    fun clearCrash(ctx: Context) {
        File(ctx.filesDir, CRASH_FILE).delete()
    }

    // ------------------------------------------------------------ files

    /**
     * A file can be present and still be rubbish - a download killed at 90%, or
     * storage that filled up mid-write. Cheap to check the magic bytes before
     * handing it to llama.cpp, which is far less forgiving.
     */
    fun looksLikeGguf(f: File): Boolean = runCatching {
        if (!f.exists() || f.length() < 1_000_000L) return false
        f.inputStream().use { s ->
            val magic = ByteArray(4)
            if (s.read(magic) != 4) return false
            magic[0] == 'G'.code.toByte() && magic[1] == 'G'.code.toByte() &&
                magic[2] == 'U'.code.toByte() && magic[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
