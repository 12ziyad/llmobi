package app.llmobi.update

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class UpdateInfoTest {
    private val info = UpdateInfo(2, "1.1.0", "https://llmobi.pages.dev/releases/llmobi-1.1.0.apk", 7482802, "a".repeat(64), "Bug fixes", 26)
    @Test fun releaseManifestRoundTrips() { assertEquals(info, UpdateInfo.parse(info.toJson())) }
    @Test fun untrustedOrMalformedUpdatesAreRejected() {
        for ((key, value) in listOf("url" to "https://attacker.example/a.apk", "url" to "http://llmobi.pages.dev/releases/llmobi-1.1.0.apk", "url" to "https://llmobi.pages.dev/llmobi.apk", "sha256" to "", "bytes" to -1, "versionCode" to 0, "versionName" to "../oops")) {
            val json = JSONObject(info.toJson()).put(key, value).toString()
            assertTrue("$key=$value", runCatching { UpdateInfo.parse(json) }.isFailure)
        }
    }
}
