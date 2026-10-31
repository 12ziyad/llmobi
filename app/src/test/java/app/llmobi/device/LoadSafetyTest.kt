package app.llmobi.device

import app.llmobi.safety.Safety
import org.junit.Assert.*
import org.junit.Test

class LoadSafetyTest {
    @Test fun obviouslyOversizedModelsAreRefusedBeforeNativeAllocation() {
        assertFalse(Safety.planLoad(1500, 20000, 4096).ok)
    }
    @Test fun smallShortfallUsesShorterContext() {
        val plan = Safety.planLoad(1800, 2000, 4096)
        assertTrue(plan.ok); assertEquals(1024, plan.contextSize)
    }
    @Test fun sufficientMemoryPreservesRequestedContext() {
        assertEquals(4096, Safety.planLoad(5000, 2000, 4096).contextSize)
    }
}
