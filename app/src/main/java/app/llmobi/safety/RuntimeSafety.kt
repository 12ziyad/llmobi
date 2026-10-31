package app.llmobi.safety

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/** Uses actual Android pressure signals, never a model's estimated RAM requirement. */
object RuntimeSafety {
    @Volatile private var memoryPressureUntil = 0L

    fun noteMemoryPressure() {
        memoryPressureUntil = SystemClock.elapsedRealtime() + 10_000
    }

    fun stopReason(lowMemory: Boolean, thermalStatus: Int): String? = when {
        thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ->
            "Android reports that your phone is too hot. The AI has stopped; let your phone cool before trying again."
        lowMemory ->
            "Android reports critical memory pressure. The AI has stopped to release memory. Close other apps, then try again."
        else -> null
    }

    fun readStopReason(ctx: Context): String? {
        val info = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        val thermal = if (Build.VERSION.SDK_INT >= 29)
            ctx.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0 else 0
        return stopReason(info.lowMemory || SystemClock.elapsedRealtime() < memoryPressureUntil, thermal)
    }
}
