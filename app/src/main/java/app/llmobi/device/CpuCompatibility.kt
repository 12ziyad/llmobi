package app.llmobi.device

import java.io.File

object CpuCompatibility {
    fun missing(cpuInfo: String): List<String> {
        val featureLines = cpuInfo.lineSequence().filter { it.trimStart().startsWith("Features") }.toList()
        if (featureLines.isEmpty()) return listOf("CPU features could not be checked")
        val common = featureLines.map { it.substringAfter(':').trim().split(Regex("\\s+")).toSet() }
            .reduce { a, b -> a intersect b }
        return mapOf("asimddp" to "dot product", "asimdhp" to "half precision")
            .filterKeys { it !in common }.values.toList()
    }

    fun missingOnDevice(): List<String> = runCatching { missing(File("/proc/cpuinfo").readText()) }
        .getOrDefault(listOf("CPU features could not be checked"))
}
