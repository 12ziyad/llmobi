package app.llmobi.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** Validate network metadata before it can become a filename, allocation or download. */
object CatalogCodec {
    fun validId(id: String) = Regex("[a-z0-9][a-z0-9-]{0,63}").matches(id)
    fun secureUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null && uri.fragment == null
    }.getOrDefault(false)

    fun parse(json: String): List<ModelEntry> {
        val arr = JSONObject(json).getJSONArray("models")
        require(arr.length() <= 500) { "Catalog too large" }
        val out = LinkedHashMap<String, ModelEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val url = o.optString("url")
            val bytes = o.optLong("fileBytes")
            val ram = o.optInt("minRamMb")
            val hash = o.optString("sha256").lowercase(Locale.ROOT)
            if (!validId(id) || !secureUrl(url) || bytes !in 4..(100L * 1024 * 1024 * 1024) ||
                ram !in 256..131072 || (hash.isNotEmpty() && !Regex("[0-9a-f]{64}").matches(hash))) continue
            out.putIfAbsent(id, ModelEntry(
                id, o.optString("name", id).take(120), o.optString("tagline").take(500),
                Tier.entries.firstOrNull { it.name.equals(o.optString("tier"), true) } ?: Tier.FAST,
                o.optString("category", "general"), o.optString("iconLetter", id.take(1).uppercase()).take(2),
                color(o.optString("colorStart")), color(o.optString("colorEnd")),
                String.format(Locale.US, if (bytes < 1073741824L) "%.2f GB" else "%.1f GB", bytes / 1073741824.0),
                o.optString("speedHint", "steady"), bytes, ram,
                o.optInt("ctxDefault", 2048).coerceIn(512, 8192), o.optString("arch"), o.optString("quant"),
                url, hash, o.optString("license"),
            ))
        }
        return out.values.toList()
    }

    fun encode(models: List<ModelEntry>): String = JSONObject().put("version", 1).put("models", JSONArray().apply {
        models.forEach { m -> put(JSONObject().apply {
            put("id", m.id); put("name", m.name); put("tagline", m.tagline); put("tier", m.tier.name.lowercase())
            put("category", m.category); put("iconLetter", m.iconLetter)
            put("colorStart", "%06X".format(m.colorStart and 0xFFFFFF)); put("colorEnd", "%06X".format(m.colorEnd and 0xFFFFFF))
            put("sizeLabel", m.sizeLabel); put("speedHint", m.speedHint); put("fileBytes", m.fileBytes)
            put("minRamMb", m.minRamMb); put("ctxDefault", m.ctxDefault); put("arch", m.arch); put("quant", m.quant)
            put("url", m.url); put("sha256", m.sha256); put("license", m.license)
        }) }
    }).toString()

    private fun color(s: String): Long = runCatching {
        require(Regex("#?[0-9a-fA-F]{6}").matches(s))
        0xFF000000L or s.removePrefix("#").toLong(16)
    }.getOrDefault(0xFF7BD4F5)
}
