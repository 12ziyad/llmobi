package app.llmobi.device

import app.llmobi.safety.Safety
import org.junit.Assert.*
import org.junit.Test

class LoadSafetyTest {
    @Test fun evenVeryLargeShortfallsOfferAnExplicitAttempt() {
        for (available in listOf(0, 100, 1000, 1500)) {
            val plan = Safety.planLoad(available, 20000, 4096)
            assertTrue(plan.needsConfirmation)
            assertEquals(512, plan.contextSize)
            assertEquals(128, plan.maxReplyTokens)
        }
    }
    @Test fun smallShortfallAlsoUsesLowerMemorySettings() {
        val plan = Safety.planLoad(1800, 2000, 4096)
        assertTrue(plan.needsConfirmation)
        assertEquals(512, plan.contextSize)
    }
    @Test fun sufficientMemoryPreservesRequestedContext() {
        val plan = Safety.planLoad(5000, 2000, 4096)
        assertFalse(plan.needsConfirmation)
        assertEquals(4096, plan.contextSize)
        assertEquals(400, plan.maxReplyTokens)
    }
    @Test fun previousFailuresRequireConsentEvenWhenRamLooksSufficient() {
        val plan = Safety.planLoad(5000, 2000, 4096, previousFailures = true)
        assertTrue(plan.needsConfirmation)
        assertTrue(plan.lowMemory)
    }
    @Test fun smallModelIsNotRejectedBecauseItsEstimateExceedsFreeRam() {
        val tiny = app.llmobi.data.Catalog.models.first()
        val plan = Safety.planLoad(tiny.minRamMb / 2, tiny.minRamMb, tiny.ctxDefault)
        assertTrue(plan.needsConfirmation)
        assertTrue(plan.maxReplyTokens + 8 < plan.contextSize)
    }
}
