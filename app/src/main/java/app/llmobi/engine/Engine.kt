package app.llmobi.engine

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
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

    /** Why the real engine is not available, in words a person can read. Null when it is. */
    @Volatile
    var unavailableReason: String? = null
        private set

    init {
        // The library is built for armv8.2-a+dotprod+fp16 (see CMakeLists). On
        // a chip without those instructions the first matrix multiply would be
        // an illegal-instruction crash, which no try/catch can see. Check first.
        val missing = missingCpuFeatures()
        available = if (missing.isNotEmpty()) {
            unavailableReason =
                "This phone's processor is missing an instruction the AI engine needs " +
                    "(${missing.joinToString()}). This version of the app " +
                    "cannot run on it. Sorry."
            Log.w("LlamaBridge", "cpu lacks $missing; not loading native library")
            false
        } else try {
            System.loadLibrary("llmobi")
            true
        } catch (t: Throwable) {
            Log.w("LlamaBridge", "native library not present yet: ${t.message}")
            unavailableReason = "The AI engine failed to load: ${t.message}"
            false
        }
    }

    /**
     * Features the library was compiled to assume that this CPU does not report.
     * Reads the kernel's own list, so it is right even on chips we have never seen.
     */
    private fun missingCpuFeatures(): List<String> = app.llmobi.device.CpuCompatibility.missingOnDevice()

    external fun nativeInit(): Boolean
    external fun nativePrepareLoad()
    external fun nativeCancelLoad()
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
    external fun nativeNextToken(handle: Long): ByteArray?
    external fun nativeStop(handle: Long)
    external fun nativeSystemInfo(): String
}

class LlamaEngine : Engine {

    override val name = "llama.cpp"
    private val handleLock = Any()
    private val releases = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var handle: Long = 0L
    @Volatile private var cancelled = false

    override val loaded: Boolean get() = handle != 0L

    override suspend fun load(path: String, contextSize: Int): Boolean =
        withContext(EngineDispatcher) {
            if (!LlamaBridge.available) return@withContext false
            unloadOnEngineThread()
            val releaseAtStart = releases.get()
            val lowMemory = contextSize <= 512
            val threads = if (lowMemory) minOf(pickThreads(), 2) else pickThreads()
            // Pull the file through the page cache first. llama.cpp mmaps it, so
            // without this the weights arrive as millions of random page faults
            // during the first decode - measured at 28 s on a Galaxy F15. One big
            // sequential read is an order of magnitude faster for the same bytes.
            if (!lowMemory) warmPageCache(path)
            val loadContext = currentCoroutineContext()
            synchronized(handleLock) {
                loadContext.ensureActive()
                if (releases.get() != releaseAtStart) return@withContext false
                LlamaBridge.nativePrepareLoad()
            }

            val loadedHandle = try {
                LlamaBridge.nativeLoadModel(path, contextSize, threads)
            } catch (t: Throwable) {
                Log.e("LlamaEngine", "load failed", t)
                0L
            }
            synchronized(handleLock) {
                if (releases.get() != releaseAtStart) {
                    if (loadedHandle != 0L) LlamaBridge.nativeFreeModel(loadedHandle)
                } else handle = loadedHandle
            }
            try { currentCoroutineContext().ensureActive() }
            catch (e: kotlinx.coroutines.CancellationException) { unloadOnEngineThread(); throw e }
            if (handle != 0L && !lowMemory) {
                runCatching { LlamaBridge.nativeWarmUp(handle) }
                    .onFailure { Log.w("LlamaEngine", "warm-up skipped: ${it.message}") }
            }
            Log.i("LlamaEngine", "load ${if (handle != 0L) "ok" else "FAILED"} (ctx=$contextSize threads=$threads)")
            handle != 0L
        }

    /**
     * How many threads to hand ggml. The answer is "the fast cores", and the
     * kernel tells us which those are.
     *
     * ggml splits every layer across its threads and then waits for the slowest
     * one, so a thread on a little core does not add a little - it drags the
     * whole layer down to little-core speed. Cores whose maximum clock is within
     * 20% of the fastest are treated as the fast set. On a 4+4 or 1+3+4 chip that
     * is the big cluster; on a 2+6 chip the littles clock close enough to count,
     * and there cores-minus-two measured best on a Dimensity 6100+.
     */
    private fun pickThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val fallback = (cores - 2).coerceIn(2, 6)
        val freqs = (0 until cores).map { i ->
            runCatching {
                java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toLong()
            }.getOrDefault(0L)
        }
        val top = freqs.maxOrNull() ?: 0L
        if (top <= 0L) return fallback
        val fast = freqs.count { it >= top * 4 / 5 }
        val chosen = if (fast in 3 until cores) fast.coerceAtMost(8) else fallback
        Log.i("LlamaEngine", "cpu clusters (kHz): $freqs -> $chosen threads")
        return chosen
    }

    /**
     * Reads the whole model file sequentially and throws the bytes away. The
     * point is the side effect: the kernel keeps them in the page cache, so the
     * mmap that follows is served from memory rather than flash.
     */
    private suspend fun warmPageCache(path: String) {
        val t0 = System.currentTimeMillis()
        val bytes = runCatching {
            java.io.File(path).inputStream().use { input ->
                val buf = ByteArray(4 shl 20)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                }
                total
            }
        }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else 0L }
        val ms = System.currentTimeMillis() - t0
        Log.i(
            "LlamaEngine",
            "page cache warmed: ${bytes / 1_048_576} MB in ${ms} ms" +
                if (ms > 0) " (${bytes / 1024 / ms} MB/s)" else "",
        )
    }

    /** Must only be called from [EngineDispatcher]. */
    private fun unloadOnEngineThread() = synchronized(handleLock) {
        if (handle != 0L) {
            try {
                LlamaBridge.nativeFreeModel(handle)
            } catch (_: Throwable) {
            }
            handle = 0L
        }
    }

    override fun unload() {
        stop()
        // Fire-and-forget onto the engine thread so a caller on the main thread
        // never blocks waiting for a free.
        releases.incrementAndGet()
        val h = synchronized(handleLock) { handle.also { handle = 0L } }
        if (h != 0L) {
            EngineDispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext) {
                try {
                    synchronized(handleLock) { LlamaBridge.nativeFreeModel(h) }
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun generate(turns: List<Turn>, maxTokens: Int, temperature: Float): Flow<String> = flow {
        val h = handle
        if (h == 0L || turns.isEmpty()) return@flow
        currentCoroutineContext().ensureActive()
        cancelled = false
        val roles = Array(turns.size) { turns[it].role }
        val contents = Array(turns.size) { turns[it].content }
        if (!LlamaBridge.nativeStartChat(h, roles, contents, maxTokens, temperature)) return@flow
        val decoder = Utf8TokenDecoder()
        while (!cancelled && handle == h) {
            currentCoroutineContext().ensureActive()
            val bytes = LlamaBridge.nativeNextToken(h) ?: break
            val text = decoder.append(bytes)
            if (text.isNotEmpty()) emit(text)
        }
        if (!cancelled) decoder.append(byteArrayOf(), end = true).takeIf { it.isNotEmpty() }?.let { emit(it) }
    }.flowOn(EngineDispatcher)

    override fun stop() {
        cancelled = true
        synchronized(handleLock) {
            LlamaBridge.nativeCancelLoad()
            val h = handle
            if (h != 0L) LlamaBridge.nativeStop(h)
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
    private val loadLock = kotlinx.coroutines.sync.Mutex()
    private var loadedContext = 0
    @Volatile var currentModelId: String? = null
        private set

    val usingRealEngine: Boolean get() = LlamaBridge.available

    fun contextFor(modelId: String): Int? =
        if (currentModelId == modelId && current?.loaded == true) loadedContext else null

    fun engine(): Engine =
        current ?: synchronized(this) {
            current ?: (if (LlamaBridge.available) LlamaEngine() else StubEngine()).also { current = it }
        }

    /** Already-loaded models return immediately; a switch unloads the old one first. */
    suspend fun ensureLoaded(modelId: String, path: String, contextSize: Int): Boolean = loadLock.withLock {
        val e = engine()
        if (currentModelId == modelId && e.loaded && loadedContext == contextSize) return@withLock true
        e.unload()
        currentModelId = null
        val ok = e.load(path, contextSize)
        if (ok) { currentModelId = modelId; loadedContext = contextSize }
        ok
    }

    fun release() {
        current?.stop()
        current?.unload()
        currentModelId = null
    }
}
