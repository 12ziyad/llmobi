package app.llmobi.perf

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * One generation, measured.
 *
 * These are the numbers that decide whether the product feels usable, so they are
 * measured rather than estimated - and shown to the user, because "your phone is
 * doing this itself" is only convincing if you can see the cost.
 */
data class Run(
    val modelId: String,
    val modelName: String,
    /** Reading the model off flash. Zero when it was already resident. */
    val loadMs: Long,
    /** Send tapped to first word on screen - the wait that actually annoys people. */
    val firstWordMs: Long,
    /** First word to last word. */
    val genMs: Long,
    val tokens: Int,
    val promptTurns: Int,
    val at: Long,
) {
    val tokensPerSec: Double get() = if (genMs > 0) tokens * 1000.0 / genMs else 0.0
    val totalMs: Long get() = firstWordMs + genMs
}

/**
 * Session-only performance history. Deliberately not persisted and never sent
 * anywhere - it is a readout, not telemetry.
 */
object Perf {

    private const val KEEP = 25

    val runs = mutableStateListOf<Run>()

    var live by mutableStateOf<Live?>(null)
        private set

    /** In-flight generation, so the UI can show progress rather than a spinner. */
    data class Live(
        val startedAt: Long,
        val tokens: Int,
        val firstWordMs: Long,
    )

    fun startLive() {
        live = Live(System.currentTimeMillis(), 0, 0)
    }

    fun tickLive(tokens: Int, firstWordMs: Long) {
        val l = live ?: return
        live = l.copy(tokens = tokens, firstWordMs = firstWordMs)
    }

    fun endLive() {
        live = null
    }

    fun record(r: Run) {
        runs.add(0, r)
        while (runs.size > KEEP) runs.removeAt(runs.size - 1)
    }

    fun clear() {
        runs.clear()
        live = null
    }

    val last: Run? get() = runs.firstOrNull()

    val avgTokensPerSec: Double
        get() = runs.filter { it.tokens > 2 }.map { it.tokensPerSec }.average().takeIf { !it.isNaN() } ?: 0.0

    val avgFirstWordMs: Long
        get() = runs.map { it.firstWordMs }.average().takeIf { !it.isNaN() }?.toLong() ?: 0L

    val bestTokensPerSec: Double
        get() = runs.maxOfOrNull { it.tokensPerSec } ?: 0.0

    /** Plain-language verdict, so a normal person gets something out of this screen too. */
    fun verdict(tps: Double): String = when {
        tps <= 0.0 -> "No measurements yet"
        tps >= 25 -> "Very fast for a phone"
        tps >= 12 -> "Normal for this phone"
        tps >= 6 -> "Slow but usable"
        else -> "Too slow - try a smaller model"
    }
}
