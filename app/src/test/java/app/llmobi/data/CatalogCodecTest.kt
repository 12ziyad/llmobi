package app.llmobi.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class CatalogCodecTest {
    @Test fun bundledCatalogRoundTripsWithoutLosingDownloadMetadata() {
        val parsed = CatalogCodec.parse(CatalogCodec.encode(Catalog.models))
        assertEquals(Catalog.models.size, parsed.size)
        Catalog.models.zip(parsed).forEach { (old, new) ->
            assertEquals(old.id, new.id); assertEquals(old.url, new.url)
            assertEquals(old.fileBytes, new.fileBytes); assertEquals(old.minRamMb, new.minRamMb)
            assertEquals(old.colorStart, new.colorStart)
        }
    }
    @Test fun traversalAndInvalidDownloadMetadataAreRejected() {
        for ((key, value) in listOf("id" to "../outside", "url" to "http://example.com/file", "url" to "https://user:pass@example.com/a", "fileBytes" to 0, "minRamMb" to -1, "sha256" to "junk")) {
            val root = JSONObject(CatalogCodec.encode(listOf(Catalog.models.first())))
            root.getJSONArray("models").getJSONObject(0).put(key, value)
            assertTrue("$key=$value", CatalogCodec.parse(root.toString()).isEmpty())
        }
    }
    @Test fun duplicatesCannotCreateTwoDownloadsForOneId() {
        assertEquals(1, CatalogCodec.parse(CatalogCodec.encode(listOf(Catalog.models.first(), Catalog.models.first()))).size)
    }
}
