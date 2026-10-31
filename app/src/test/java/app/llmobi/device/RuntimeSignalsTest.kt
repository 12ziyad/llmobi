package app.llmobi.device

import android.app.ActivityManager
import android.app.Application
import android.os.PowerManager
import app.llmobi.safety.RuntimeSafety
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RuntimeSignalsTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before fun resetProcessState() {
        // Robolectric reuses Kotlin singletons across otherwise isolated app instances.
        RuntimeSafety::class.java.getDeclaredField("memoryPressureUntil").apply { isAccessible = true }.setLong(null, 0)
        shadowOf(ctx.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo())
    }

    @Test fun readsThermalServiceAndAllowsRetryAfterCooling() {
        val power = shadowOf(ctx.getSystemService(PowerManager::class.java))
        power.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)
        assertTrue(RuntimeSafety.readStopReason(ctx)!!.contains("too hot"))
        power.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_MODERATE)
        assertNull(RuntimeSafety.readStopReason(ctx))
    }

    @Test fun onlyOsLowMemoryFlagStopsWorkNotTheAvailableByteEstimate() {
        val memory = shadowOf(ctx.getSystemService(ActivityManager::class.java))
        memory.setMemoryInfo(ActivityManager.MemoryInfo().apply { availMem = 0; lowMemory = false })
        assertNull(RuntimeSafety.readStopReason(ctx))
        memory.setMemoryInfo(ActivityManager.MemoryInfo().apply { lowMemory = true })
        assertTrue(RuntimeSafety.readStopReason(ctx)!!.contains("memory pressure"))
    }

    @Test fun lowMemoryCallbackPreventsImmediateRetryLoopThenExpires() {
        RuntimeSafety.noteMemoryPressure()
        assertNotNull(RuntimeSafety.readStopReason(ctx))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
        assertNull(RuntimeSafety.readStopReason(ctx))
    }

    @Test @Config(sdk = [28]) fun olderAndroidDoesNotRequireThermalApi() {
        assertNull(RuntimeSafety.readStopReason(ctx))
        RuntimeSafety.noteMemoryPressure()
        assertNotNull(RuntimeSafety.readStopReason(ctx))
    }
}
