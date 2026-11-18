package org.yaqmc.android

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** A host-only cookie handoff. Neither the page nor a Worker receives the cookie values. */
internal data class OAuthBrowserSession(val url: String, val names: Set<String>, val required: Set<String>, val format: String) {
    fun capture(header: String?): JSONArray? {
        val values = linkedMapOf<String, String>()
        for (pair in header.orEmpty().split(';')) {
            val key = pair.substringBefore('=').trim()
            if (key !in names) continue
            val value = pair.substringAfter('=', "").trim()
            if (key in values || value.isEmpty() || value.length > 16_384 || value.any { it.code !in 0x21..0x7e || it in ";," }) return null
            values[key] = value
        }
        if (!values.keys.containsAll(required)) return null
        return JSONArray(values.map { (name, value) -> JSONObject().put("name", name).put("value", value) })
    }

    companion object {
        fun parse(raw: String?): OAuthBrowserSession? = runCatching {
            val value = JSONObject(raw ?: return null)
            val url = value.getString("url")
            val uri = URI(url)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.port == -1 && uri.userInfo == null && uri.path == "/" && uri.rawQuery == null && uri.rawFragment == null)
            fun names(key: String): List<String> = value.getJSONArray(key).let { array -> (0 until array.length()).map { array.getString(it) } }
            val names = names("cookieNames")
            val required = names("requiredCookieNames")
            require(names.size in 1..8 && names.distinct().size == names.size && names.all { Regex("[A-Za-z0-9_-]{1,64}").matches(it) })
            require(required.isNotEmpty() && names.containsAll(required))
            val format = value.getString("format")
            require(format in setOf("cookie", "kugou-web"))
            OAuthBrowserSession(url, names.toSet(), required.toSet(), format)
        }.getOrNull()
    }
}
