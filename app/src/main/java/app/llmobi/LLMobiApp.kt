package app.llmobi

import android.app.Application
import android.util.Log
import app.llmobi.engine.Engines
import app.llmobi.engine.LlamaBridge

class LLMobiApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Touch the bridge once at startup so a missing or broken native library
        // shows up in logcat immediately, rather than as a silent fallback to the
        // preview engine the first time somebody sends a message.
        val real = LlamaBridge.available
        Log.i(TAG, "engine=${if (real) "llama.cpp" else "preview stub"}")
        if (real) {
            runCatching { LlamaBridge.nativeInit() }
                .onSuccess { Log.i(TAG, "native init ok: $it") }
                .onFailure { Log.e(TAG, "native init failed", it) }
            runCatching { LlamaBridge.nativeSystemInfo() }
                .onSuccess { Log.i(TAG, "system: ${it.trim()}") }
                .onFailure { Log.e(TAG, "system info failed", it) }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // A loaded model is by far the biggest thing in this process. Give it back
        // before Android decides to kill us outright.
        Log.w(TAG, "low memory - releasing model")
        Engines.release()
    }

    companion object {
        private const val TAG = "LLMobi"
    }
}
