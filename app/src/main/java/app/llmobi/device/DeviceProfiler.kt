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
    val cpuSupported: Boolean = true,
    val memoryReserveMb: Int = 256,
) {
    val supported: Boolean get() = abi.contains("arm64") && cpuSupported
    val usableRamMb: Int get() = (availableRamMb - memoryReserveMb).coerceAtLeast(0)
}

/** What the badge says. The user sees a word and a colour, never a number. */
enum class Fit(val label: String) {
    EXCELLENT("Excellent"),
    RECOMMENDED("Recommended"),
    HEAVY("Heavy"),
    WONT_RUN("Too heavy right now"),
    NO_SPACE("Not enough space"),
    UNSUPPORTED("Phone not supported"),
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
            cpuSupported = CpuCompatibility.missingOnDevice().isEmpty(),
            memoryReserveMb = (mi.threshold / 1_048_576L).toInt().coerceAtLeast(192),
        )
    }

    /**
     * The whole compatibility rule, in one place.
     *
     * We compare against *available* RAM rather than total, because a phone that
     * advertises 4 GB may only have 1 GB free - and that is the number that decides
     * whether the model loads or the process gets killed.
     */
    fun fit(model: ModelEntry, p: DeviceProfile, installed: Boolean = false): Fit {
        if (!p.supported) return Fit.UNSUPPORTED

        val needMb = model.fileBytes / 1_048_576L
        // Leave a 1 GB cushion so we never fill the phone completely.
        if (!installed && p.freeStorageMb < needMb + 1024) return Fit.NO_SPACE

        // Keep Android's low-memory reserve rather than guessing reclaimable RAM.
        val usable = p.usableRamMb
        val ratio = usable.toDouble() / model.minRamMb.toDouble()

        return when {
            ratio >= 1.80 -> Fit.EXCELLENT
            ratio >= 1.25 -> Fit.RECOMMENDED
            ratio >= 1.00 -> Fit.HEAVY
            else -> Fit.WONT_RUN
        }
    }

    /** Store ordering: best fit first, then smallest, with fit labels describing the current memory estimate. */
    fun rank(models: List<ModelEntry>, p: DeviceProfile, installedIds: Set<String> = emptySet()): List<Pair<ModelEntry, Fit>> =
        models.map { it to fit(it, p, it.id in installedIds) }
            .sortedWith(
                compareBy(
                    { pair -> pair.second.ordinal },
                    { pair -> pair.first.fileBytes },
                )
            )
}
