package com.kimyubin33.audioapp.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI

data class PublishedPolicy(
    val ready: Boolean, val version: String?,
    val terms: String?, val privacy: String?, val creatorTerms: String?, val supportEmail: String? = null,
) {
    companion object {
        fun parse(raw: String): PublishedPolicy {
            val json = JSONObject(raw)
            fun link(key: String): String? = json.optString(key).takeIf { value ->
                runCatching { URI(value).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
            }
            val version = json.optString("version").takeIf { Regex("[A-Za-z0-9._-]{1,64}").matches(it) }
            val terms = link("terms_url"); val privacy = link("privacy_url"); val creator = link("creator_terms_url")
            return PublishedPolicy(json.optBoolean("ready") && version != null && terms != null && privacy != null && creator != null,
                version, terms, privacy, creator,
                json.optString("support_email").takeIf { it.length <= 254 && it.contains("@") && it.none(Char::isWhitespace) })
        }
        suspend fun load(context: Context): PublishedPolicy {
            val api = withContext(Dispatchers.IO) { AudioApi.get(context) }
            return parse(api.publicPolicy())
        }
    }
}
