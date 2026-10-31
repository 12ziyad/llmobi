package app.llmobi.device

import app.llmobi.data.Catalog
import org.junit.Assert.*
import org.junit.Test

class DeviceProfilerTest {
    private fun phone(freeRam: Int = 2500, storage: Long = 12000, supported: Boolean = true) =
        DeviceProfile(6000, freeRam, storage, 64000, "arm64-v8a", "test", 8, "15", supported, 256)
    private val tiny = Catalog.models.first()
    @Test fun occupiedRamIsNeverCountedAsFreeMemory() {
        assertEquals(Fit.WONT_RUN, DeviceProfiler.fit(tiny, phone(freeRam = 600).copy(totalRamMb = 24000)))
    }
    @Test fun storageIncludesOneGigabyteReserve() {
        assertEquals(Fit.NO_SPACE, DeviceProfiler.fit(tiny, phone(storage = tiny.fileBytes / 1048576 + 100)))
    }
    @Test fun alreadyInstalledModelsDoNotNeedDownloadSpaceAgain() {
        assertEquals(Fit.EXCELLENT, DeviceProfiler.fit(tiny, phone(storage = 100), installed = true))
    }
    @Test fun unsupportedCpuIsNeverRecommended() {
        assertTrue(DeviceProfiler.rank(Catalog.models, phone(supported = false)).all { it.second == Fit.UNSUPPORTED })
    }
    @Test fun smallSuitableModelIsFirst() {
        assertEquals(tiny.id, DeviceProfiler.rank(Catalog.models.reversed(), phone()).first().first.id)
    }
    @Test fun ramPressureChangesRecommendation() {
        assertEquals(Fit.EXCELLENT, DeviceProfiler.fit(tiny, phone()))
        assertEquals(Fit.WONT_RUN, DeviceProfiler.fit(tiny, phone(freeRam = 800)))
    }
    @Test fun cpuFeaturesMustExistOnEveryReportedCore() {
        assertTrue(CpuCompatibility.missing("Features : asimddp asimdhp fp asimd").isEmpty())
        assertTrue(CpuCompatibility.missing("Features : asimddp asimdhp\nFeatures : fp asimd").isNotEmpty())
        assertTrue(CpuCompatibility.missing("").isNotEmpty())
    }
}
