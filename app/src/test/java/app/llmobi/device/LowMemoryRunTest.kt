package app.llmobi.device

import android.app.ActivityManager
import android.app.Application
import android.os.Looper
import android.os.PowerManager
import androidx.lifecycle.ViewModelStore
import androidx.work.Configuration
import androidx.work.WorkManager
import app.llmobi.data.Catalog
import app.llmobi.data.CatalogSync
import app.llmobi.data.Store
import app.llmobi.engine.Engine
import app.llmobi.engine.Engines
import app.llmobi.engine.LlamaBridge
import app.llmobi.engine.Turn
import app.llmobi.safety.Safety
import app.llmobi.ui.AppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** Exercises the real send/confirm/cancel flow; inference is explicitly a test double. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LowMemoryRunTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val fake = RecordingEngine()
    private lateinit var state: AppState
    private val models = ViewModelStore()
    private val model = Catalog.models.first().copy(minRamMb = 20000, fileBytes = 1_100_000)
    private var unavailable: String? = null

    @Before fun setUp() {
        // No network or scheduled workers in this test.
        try { WorkManager.getInstance(ctx) }
        catch (_: IllegalStateException) {
            WorkManager.initialize(ctx, Configuration.Builder().setExecutor { }.setTaskExecutor { }.build())
        }
        ctx.getSharedPreferences("llmobi", 0).edit().putLong("catalog_checked", System.currentTimeMillis()).commit()
        ctx.getSharedPreferences("llmobi_updates", 0).edit().putLong("checked", System.currentTimeMillis()).commit()
        shadowOf(ctx.getSystemService(ActivityManager::class.java)).setMemoryInfo(
            ActivityManager.MemoryInfo().apply { totalMem = 4L shl 30; availMem = 400L shl 20; lowMemory = false })
        shadowOf(ctx.getSystemService(PowerManager::class.java)).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE)
        val file = File(ctx.filesDir, "test.gguf")
        file.outputStream().use { it.write("GGUF".toByteArray()); it.write(ByteArray(1_099_996)) }
        CatalogSync.remember(ctx, model)
        Store(ctx).markInstalled(model.id, file.path, file.length())
        unavailable = LlamaBridge.unavailableReason
        LlamaBridge::class.java.getDeclaredField("unavailableReason").apply { isAccessible = true }.set(null, null)
        Engines::class.java.getDeclaredField("current").apply { isAccessible = true }.set(null, fake)
        state = AppState(ctx)
        models.put("state", state)
        state.selectModel(model.id)
    }

    @After fun tearDown() {
        models.clear()
        Engines.release()
        Engines::class.java.getDeclaredField("current").apply { isAccessible = true }.set(null, null)
        LlamaBridge::class.java.getDeclaredField("unavailableReason").apply { isAccessible = true }.set(null, unavailable)
    }

    @Test fun cancelKeepsDraftAndCreatesNoMessagesOrNativeLoad() {
        state.draftText = "Hello"
        state.send(state.draftText)
        assertNotNull(state.pendingRun)
        assertTrue(state.messages.isEmpty())
        assertEquals(0, fake.context)
        state.dismissRun()
        assertEquals("Hello", state.draftText)
        assertFalse(state.generating)
    }

    @Test fun confirmationStartsReducedMemoryLoadEvenAfterTwoFailures() {
        repeat(2) { Safety.endLoad(ctx, model.id, false) }
        state.send("Hello")
        assertNotNull(state.pendingRun)
        state.confirmRun()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(512, fake.context)
        assertTrue(state.messages.any { it.text == "Hello" })
        assertNull(state.pendingRun)
    }

    @Test fun staleConfirmationDoesNotSendIntoAnotherChat() {
        state.send("Hello")
        state.selectModel(Catalog.models[1].id)
        state.confirmRun()
        assertEquals(0, fake.context)
        assertTrue(state.messages.isEmpty())
    }

    @Test fun heatDuringLoadingCancelsAndReleasesEngine() {
        state.send("Hello")
        state.confirmRun()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(state.generating)
        fake.stopped = false
        fake.released = false
        shadowOf(ctx.getSystemService(PowerManager::class.java)).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(state.generating)
        assertTrue(fake.stopped)
        assertTrue(fake.released)
        assertTrue(state.engineNote!!.contains("too hot"))
    }

    private class RecordingEngine : Engine {
        override val name = "test only"
        override val loaded = false
        var context = 0
        var stopped = false
        var released = false
        override suspend fun load(path: String, contextSize: Int): Boolean {
            context = contextSize
            delay(60_000) // Keep the loading stage open for cancellation tests.
            return true
        }
        override fun generate(turns: List<Turn>, maxTokens: Int, temperature: Float) = flow { emit("test") }
        override fun stop() { stopped = true }
        override fun unload() { released = true }
    }
}
