package app.llmobi.engine

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Everything the app needs from an inference engine.
 *
 * There are two implementations: the real llama.cpp bridge, and a stub that
 * streams canned text. The stub exists so every screen can be built and tested
 * before the native library is compiled - the UI never knows the difference.
 */
/** One turn of a conversation. Role is "system", "user" or "assistant". */
data class Turn(val role: String, val content: String)

interface Engine {
    val name: String
    suspend fun load(path: String, contextSize: Int): Boolean
    fun unload()
    val loaded: Boolean
    /**
     * Turns are passed separately rather than pre-joined so the native side can
     * apply the model's own chat template. Without that the model treats the text
     * as something to continue instead of a question to answer.
     */
    fun generate(turns: List<Turn>, maxTokens: Int, temperature: Float): Flow<String>
    fun stop()
}

/**
 * One dedicated thread for every native call, for two separate reasons.
 *
 * Correctness: a llama_context is not thread-safe, so loading, decoding and
 * freeing must all happen on the same thread.
 *
 * Responsiveness: model loading takes seconds and each token is a heavy matrix
 * pass. Run either on the main thread and the whole app locks up until the reply
 * finishes - not slow, actually frozen.
 */
internal val EngineDispatcher: CoroutineDispatcher =
    Executors.newSingleThreadExecutor { r ->
        Thread({
            // Priority has to be set from inside the thread, and it has to be
            // exactly this.
            //
            // Java thread priorities map onto Linux nice values, and the mapping is
            // brutal: NORM_PRIORITY - 1 lands on nice 10. ggml spawns its worker
            // threads from whichever thread calls decode, so they inherit that nice
            // value too - which measured as 734 ms per prompt token against 20 ms
            // for the same work in a standalone binary. THREAD_PRIORITY_DEFAULT is
            // nice 0; the UI thread sits at nice -10 in the top-app group and still
            // comfortably wins, so drawing stays smooth.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
            r.run()
        }, "llmobi-engine").apply { isDaemon = true }
    }.asCoroutineDispatcher()

// ------------------------------------------------------------------ native

/**
 * Thin JNI surface. Method names and signatures must match llama-jni.cpp exactly.
 * Keep this class in proguard rules - native code looks it up by name.
 */
object LlamaBridge {

    @Volatile
    var available: Boolean = false
        private set

    init {
        available = try {
            System.loadLibrary("llmobi")
            true
        } catch (t: Throwable) {
            Log.w("LlamaBridge", "native library not present yet: ${t.message}")
            false
        }
    }

    external fun nativeInit(): Boolean
    external fun nativeLoadModel(path: String, contextSize: Int, threads: Int): Long
    external fun nativeFreeModel(handle: Long)
    external fun nativeWarmUp(handle: Long)
    external fun nativeStartChat(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temperature: Float,
    ): Boolean
    /** Returns the next token's text, or null when the sequence is finished. */
    external fun nativeNextToken(handle: Long): String?
    external fun nativeStop(handle: Long)
    external fun nativeSystemInfo(): String
}

class LlamaEngine : Engine {

    override val name = "llama.cpp"
    private var handle: Long = 0L
    @Volatile private var cancelled = false

    override val loaded: Boolean get() = handle != 0L

    override suspend fun load(path: String, contextSize: Int): Boolean =
        withContext(EngineDispatcher) {
            if (!LlamaBridge.available) return@withContext false
            unloadOnEngineThread()
            val cores = Runtime.getRuntime().availableProcessors()
            // Leave a core or two for the UI and the OS; more threads than the
            // big cluster has just makes it thrash.
            val threads = (cores - 2).coerceIn(2, 6)
            // Pull the file through the page cache first. llama.cpp mmaps it, so
            // without this the weights arrive as millions of random page faults
            // during the first decode - measured at 28 s on a Galaxy F15. One big
            // sequential read is an order of magnitude faster for the same bytes.
            warmPageCache(path)

            handle = try {
                LlamaBridge.nativeLoadModel(path, contextSize, threads)
            } catch (t: Throwable) {
                Log.e("LlamaEngine", "load failed", t)
                0L
            }
            if (handle != 0L) {
                runCatching { LlamaBridge.nativeWarmUp(handle) }
                    .onFailure { Log.w("LlamaEngine", "warm-up skipped: ${it.message}") }
            }
            Log.i("LlamaEngine", "load ${if (handle != 0L) "ok" else "FAILED"} (ctx=$contextSize threads=$threads)")
            handle != 0L
        }

    /**
     * Reads the whole model file sequentially and throws the bytes away. The
     * point is the side effect: the kernel keeps them in the page cache, so the
     * mmap that follows is served from memory rather than flash.
     */
    private fun warmPageCache(path: String) {
        val t0 = System.currentTimeMillis()
        val bytes = runCatching {
            java.io.File(path).inputStream().use { input ->
                val buf = ByteArray(4 shl 20)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                }
                total
            }
        }.getOrDefault(0L)
        val ms = System.currentTimeMillis() - t0
        Log.i(
            "LlamaEngine",
            "page cache warmed: ${bytes / 1_048_576} MB in ${ms} ms" +
                if (ms > 0) " (${bytes / 1024 / ms} MB/s)" else "",
        )
    }

    /** Must only be called from [EngineDispatcher]. */
    private fun unloadOnEngineThread() {
        if (handle != 0L) {
            try {
                LlamaBridge.nativeFreeModel(handle)
            } catch (_: Throwable) {
            }
            handle = 0L
        }
    }

    override fun unload() {
        // Fire-and-forget onto the engine thread so a caller on the main thread
        // never blocks waiting for a free.
        val h = handle
        handle = 0L
        if (h != 0L) {
            EngineDispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext) {
                try {
                    LlamaBridge.nativeFreeModel(h)
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun generate(turns: List<Turn>, maxTokens: Int, temperature: Float): Flow<String> = flow {
        if (handle == 0L || turns.isEmpty()) return@flow
        cancelled = false
        val roles = Array(turns.size) { turns[it].role }
        val contents = Array(turns.size) { turns[it].content }
        if (!LlamaBridge.nativeStartChat(handle, roles, contents, maxTokens, temperature)) return@flow
        while (!cancelled) {
            val tok = LlamaBridge.nativeNextToken(handle) ?: break
            emit(tok)
        }
    }.flowOn(EngineDispatcher)

    override fun stop() {
        // Deliberately not on the engine thread: that thread is busy inside
        // nativeNextToken, and this flag is what lets it notice and return.
        cancelled = true
        val h = handle
        if (h != 0L) {
            try {
                LlamaBridge.nativeStop(h)
            } catch (_: Throwable) {
            }
        }
    }
}

// ------------------------------------------------------------------ stub

/**
 * Streams a canned answer word by word at a believable speed.
 *
 * This is not a fake product feature - it is a development seam. It makes the
 * chat screen, the stop button, history saving and shortcuts all testable on any
 * machine, including before llama.cpp is compiled for the device.
 */
class StubEngine : Engine {

    override val name = "preview"
    private var isLoaded = false
    @Volatile private var cancelled = false

    override val loaded: Boolean get() = isLoaded

    override suspend fun load(path: String, contextSize: Int): Boolean {
        delay(400)
        isLoaded = true
        return true
    }

    override fun unload() {
        isLoaded = false
    }

    override fun generate(turns: List<Turn>, maxTokens: Int, temperature: Float): Flow<String> = flow {
        cancelled = false
        val last = turns.lastOrNull { it.role == "user" }?.content ?: ""
        for (word in canned(last).split(" ")) {
            if (cancelled) break
            emit("$word ")
            delay(55)
        }
    }

    override fun stop() {
        cancelled = true
    }

    private fun canned(prompt: String): String {
        val p = prompt.lowercase()
        return when {
            "ice" in p || "frozen" in p ->
                "1 kg of ice weighs exactly 1 kg, the same as 1 kg of water. The difference is space, not weight: ice takes up about 9% more room, so that kilogram fills roughly 1.09 litres."

            "photosynth" in p || "plant" in p ->
                "Plants make their own food out of sunlight. They pull water up through their roots and take carbon dioxide in through their leaves, then use light as the energy to turn those into sugar. Oxygen is the leftover, and that leftover is what we breathe."

            "python" in p || "loop" in p || "code" in p ->
                "A loop that never ends usually means the value being checked never changes. Make sure the counter is updated inside the loop body, or use a for loop over a range so the counting is handled for you."

            else ->
                "I am running on your phone rather than a server, so I work best with short, direct questions."
        }
    }
}

// ------------------------------------------------------------------ holder

/**
 * One engine for the whole process. Two models must never be resident at once -
 * on a 4 GB phone that is an instant out-of-memory kill.
 */
object Engines {

    @Volatile private var current: Engine? = null
    @Volatile var currentModelId: String? = null
        private set

    val usingRealEngine: Boolean get() = LlamaBridge.available

    fun engine(): Engine =
        current ?: synchronized(this) {
            current ?: (if (LlamaBridge.available) LlamaEngine() else StubEngine()).also { current = it }
        }

    /** Already-loaded models return immediately; a switch unloads the old one first. */
    suspend fun ensureLoaded(modelId: String, path: String, contextSize: Int): Boolean {
        val e = engine()
        if (currentModelId == modelId && e.loaded) return true
        e.unload()
        currentModelId = null
        val ok = e.load(path, contextSize)
        if (ok) currentModelId = modelId
        return ok
    }

    fun release() {
        current?.unload()
        currentModelId = null
    }
}
