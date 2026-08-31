package app.llmobi.engine

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Everything the app needs from an inference engine.
 *
 * There are two implementations: the real llama.cpp bridge, and a stub that
 * streams canned text. The stub exists so every screen can be built and tested
 * before the native library is compiled - the UI never knows the difference.
 */
interface Engine {
    val name: String
    suspend fun load(path: String, contextSize: Int): Boolean
    fun unload()
    val loaded: Boolean
    fun generate(prompt: String, maxTokens: Int, temperature: Float): Flow<String>
    fun stop()
}

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
    external fun nativeStartCompletion(handle: Long, prompt: String, maxTokens: Int, temperature: Float): Boolean
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

    override suspend fun load(path: String, contextSize: Int): Boolean {
        if (!LlamaBridge.available) return false
        unload()
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
        handle = try {
            LlamaBridge.nativeLoadModel(path, contextSize, threads)
        } catch (t: Throwable) {
            Log.e("LlamaEngine", "load failed", t)
            0L
        }
        return handle != 0L
    }

    override fun unload() {
        if (handle != 0L) {
            try {
                LlamaBridge.nativeFreeModel(handle)
            } catch (_: Throwable) {
            }
            handle = 0L
        }
    }

    override fun generate(prompt: String, maxTokens: Int, temperature: Float): Flow<String> = flow {
        if (handle == 0L) return@flow
        cancelled = false
        if (!LlamaBridge.nativeStartCompletion(handle, prompt, maxTokens, temperature)) return@flow
        while (!cancelled) {
            val tok = LlamaBridge.nativeNextToken(handle) ?: break
            emit(tok)
        }
    }

    override fun stop() {
        cancelled = true
        if (handle != 0L) {
            try {
                LlamaBridge.nativeStop(handle)
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

    override fun generate(prompt: String, maxTokens: Int, temperature: Float): Flow<String> = flow {
        cancelled = false
        val answer = canned(prompt)
        for (word in answer.split(" ")) {
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
                "1 kg of ice weighs exactly 1 kg, the same as 1 kg of water. The difference is space, not weight: ice takes up about 9% more room, so that kilogram fills roughly 1.09 litres. That is also why a full bottle cracks when you freeze it."

            "photosynth" in p || "plant" in p ->
                "Plants make their own food out of sunlight. They pull water up through their roots and take carbon dioxide in through their leaves, then use light as the energy to turn those into sugar. Oxygen is the leftover, and that leftover is what we breathe."

            "python" in p || "loop" in p || "code" in p ->
                "A loop that never ends usually means the value being checked never changes. Make sure the counter is updated inside the loop body, or use a for loop over a range so the counting is handled for you."

            "hello" in p || "hi" == p.trim() || "hey" in p ->
                "Hello. I am running entirely on your phone, so this works with no internet at all. Ask me anything."

            else ->
                "I am running on your phone rather than a server, so I work best with short, direct questions. Give me a little more detail and I will have a proper go at it."
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
