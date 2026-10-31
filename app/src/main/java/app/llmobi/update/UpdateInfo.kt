package app.llmobi.update

import org.json.JSONObject
import java.net.URI

data class UpdateInfo(val versionCode: Int, val versionName: String, val url: String, val bytes: Long, val sha256: String, val notes: String, val minSdk: Int) {
    fun toJson(): String = JSONObject().apply {
        put("versionCode", versionCode); put("versionName", versionName); put("url", url)
        put("bytes", bytes); put("sha256", sha256); put("notes", notes); put("minSdk", minSdk)
    }.toString()

    companion object {
        fun parse(json: String): UpdateInfo {
            val o = JSONObject(json)
            val url = o.getString("url")
            val uri = URI(url)
            require(uri.scheme == "https" && uri.host == "llmobi.pages.dev" && uri.userInfo == null && uri.port == -1 && uri.fragment == null)
            require(Regex("/releases/llmobi-[0-9]+\\.[0-9]+\\.[0-9]+\\.apk").matches(uri.path))
            val code = o.getInt("versionCode")
            val bytes = o.getLong("bytes")
            val hash = o.getString("sha256").lowercase()
            require(code > 0 && bytes in 1..(100L * 1024 * 1024) && Regex("[0-9a-f]{64}").matches(hash))
            val name = o.getString("versionName")
            require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(name))
            require(uri.path == "/releases/llmobi-$name.apk")
            return UpdateInfo(code, name, url, bytes, hash, o.optString("notes").take(2000), o.optInt("minSdk", 26).coerceAtLeast(26))
        }
    }
}
