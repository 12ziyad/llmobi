package app.llmobi.device

import app.llmobi.safety.RuntimeSafety
import org.junit.Assert.*
import org.junit.Test

class RuntimeSafetyTest {
    @Test fun normalAndModerateThermalStatesAllowWork() {
        for (status in 0..2) assertNull(RuntimeSafety.stopReason(false, status))
    }
    @Test fun severeHeatAndHigherStopWork() {
        for (status in 3..6) assertTrue(RuntimeSafety.stopReason(false, status)!!.contains("too hot"))
    }
    @Test fun actualOsMemoryPressureStopsWorkEvenWithoutHeat() {
        assertTrue(RuntimeSafety.stopReason(true, 0)!!.contains("memory pressure"))
    }
    @Test fun heatTakesPriorityWhenBothSignalsArrive() {
        assertTrue(RuntimeSafety.stopReason(true, 3)!!.contains("too hot"))
    }
}
