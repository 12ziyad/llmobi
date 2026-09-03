package app.llmobi.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import app.llmobi.data.ModelEntry

/**
 * What we actually know about this phone. Read fresh every time the store opens,
 * because free RAM swings wildly depending on what else is running.
 */
data class DeviceProfile(
    val totalRamMb: Int,
    val availableRamMb: Int,
    val freeStorageMb: Long,
    val totalStorageMb: Long,
    val abi: String,
    val soc: String,
    val cores: Int,
    val androidVersion: String,
) {
    val supported: Boolean get() = abi.contains("arm64")
}

/** What the badge says. The user sees a word and a colour, never a number. */
enum class Fit(val label: String) {
    EXCELLENT("Excellent"),
    RECOMMENDED("Recommended"),
    HEAVY("Heavy"),
    WONT_RUN("Not sure"),
    NO_SPACE("Not enough space"),
}

object DeviceProfiler {

    fun read(ctx: Context): DeviceProfile {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        val stat = StatFs(ctx.filesDir.absolutePath)
        val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
        val totalBytes = stat.blockCountLong * stat.blockSizeLong

        return DeviceProfile(
            totalRamMb = (mi.totalMem / 1_048_576L).toInt(),
            availableRamMb = (mi.availMem / 1_048_576L).toInt(),
            freeStorageMb = freeBytes / 1_048_576L,
            totalStorageMb = totalBytes / 1_048_576L,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL ?: Build.HARDWARE
            } else {
                Build.HARDWARE
            },
            cores = Runtime.getRuntime().availableProcessors(),
            androidVersion = Build.VERSION.RELEASE ?: "?",
        )
    }

    /**
     * The whole compatibility rule, in one place.
     *
     * We compare against *available* RAM rather than total, because a phone that
     * advertises 4 GB may only have 1 GB free - and that is the number that decides
     * whether the model loads or the process gets killed.
     */
    fun fit(model: ModelEntry, p: DeviceProfile): Fit {
        if (!p.supported) return Fit.WONT_RUN

        val needMb = model.fileBytes / 1_048_576L
        // Leave a 1 GB cushion so we never fill the phone completely.
        if (p.freeStorageMb < needMb + 1024) return Fit.NO_SPACE

        // Headroom the OS can hand back if pressed: available now, plus a slice of
        // what is currently held as reclaimable cache. Deliberately conservative.
        val usable = p.availableRamMb + (p.totalRamMb - p.availableRamMb) / 5
        val ratio = usable.toDouble() / model.minRamMb.toDouble()

        return when {
            ratio >= 1.80 -> Fit.EXCELLENT
            ratio >= 1.25 -> Fit.RECOMMENDED
            ratio >= 1.00 -> Fit.HEAVY
            else -> Fit.WONT_RUN
        }
    }

    /** Store ordering: best fit first, then smallest, so the top of the list always works. */
    fun rank(models: List<ModelEntry>, p: DeviceProfile): List<Pair<ModelEntry, Fit>> =
        models.map { it to fit(it, p) }
            .sortedWith(
                compareBy(
                    { pair -> pair.second.ordinal },
                    { pair -> pair.first.fileBytes },
                )
            )
}
