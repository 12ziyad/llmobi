package app.llmobi.data

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class OfflinePersistenceTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val added get() = Catalog.models.first().copy(id = "new-online-model", name = "New online model")

    @Test fun remotelyAddedModelResolvesWithoutBeingBundled() {
        File(ctx.filesDir, "catalog.json").writeText(CatalogCodec.encode(listOf(added)))
        assertNull(Catalog.byId(added.id))
        assertEquals(added.url, CatalogSync.find(ctx, added.id)?.url)
    }
    @Test fun installedMetadataSurvivesRemovalFromOnlineCatalog() {
        CatalogSync.remember(ctx, added)
        File(ctx.filesDir, "catalog.json").writeText(CatalogCodec.encode(Catalog.models))
        assertFalse(CatalogSync.load(ctx).any { it.id == added.id })
        assertEquals(added.name, CatalogSync.find(ctx, added.id)?.name)
        assertEquals(added.fileBytes, CatalogSync.find(ctx, added.id)?.fileBytes)
    }
    @Test fun corruptCacheFallsBackToUsableBundledCatalog() {
        File(ctx.filesDir, "catalog.json").writeText("{partial JSON")
        assertEquals(Catalog.models.size, CatalogSync.load(ctx).size)
    }
    @Test fun interruptedAtomicWriteRecoversBackupCatalog() {
        File(ctx.filesDir, "catalog.json.bak").writeText(CatalogCodec.encode(listOf(added)))
        assertEquals(added.id, CatalogSync.load(ctx).single().id)
    }
    @Test fun installedMetadataKeepsItsVersionAfterCatalogReplacement() {
        CatalogSync.remember(ctx, added)
        File(ctx.filesDir, "catalog.json").writeText(CatalogCodec.encode(listOf(added.copy(fileBytes = added.fileBytes + 42))))
        assertEquals(added.fileBytes, CatalogSync.find(ctx, added.id)?.fileBytes)
    }
    @Test fun oldCacheIsEnrichedWithMatchingBundledChecksums() {
        File(ctx.filesDir, "catalog.json").writeText(CatalogCodec.encode(listOf(Catalog.models.first().copy(sha256 = ""))))
        assertEquals(Catalog.models.first().sha256, CatalogSync.load(ctx).single().sha256)
    }
    @Test fun reopeningDatabasePreservesChatsSettingsAndModelRecords() {
        val store = Store(ctx)
        store.markInstalled(added.id, File(ctx.filesDir, "models/${added.id}.gguf").path, added.fileBytes)
        val chat = store.newChat(added.id, "My offline chat")
        store.addMessage(chat, "me", "മലയാളം 😀")
        val saved = store.settings(added.id).copy(memory = "short", system = "Keep answers brief")
        store.saveSettings(saved)
        val reopened = Store(ctx)
        assertEquals("മലയാളം 😀", reopened.messages(chat).single().text)
        assertEquals(saved, reopened.settings(added.id))
        assertTrue(reopened.isInstalled(added.id))
        reopened.uninstall(added.id, false)
        assertEquals(1, reopened.messages(chat).size)
    }
}
