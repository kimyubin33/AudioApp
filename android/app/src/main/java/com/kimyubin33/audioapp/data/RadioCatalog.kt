package com.kimyubin33.audioapp.data

import android.content.Context
import android.os.SystemClock
import com.kimyubin33.audioapp.ui.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.audio_core.ContentItem
import uniffi.audio_core.ContentKind

/** A bounded, explainable first-release radio. No invented popularity scores or location history. */
object RadioCatalog {
    const val ROOT = "audio:root"
    const val ALL = "audio:all"
    const val FOLLOWING = "audio:following"
    const val SAVED = "audio:saved"
    const val MIX = "radio:mix"
    private data class Nearby(val ids: List<String>, val at: Long)
    @Volatile private var nearby = Nearby(emptyList(), 0)
    fun updateNearby(ids: List<String>) { nearby = Nearby(ids.distinct().take(50), SystemClock.elapsedRealtime()) }
    fun clearNearby() { nearby = Nearby(emptyList(), 0) }

    fun preference(context: Context): String = context.getSharedPreferences("radio", Context.MODE_PRIVATE).getString("topic", "").orEmpty()
    fun setPreference(context: Context, topic: String) {
        require(topic.length <= 40)
        context.getSharedPreferences("radio", Context.MODE_PRIVATE).edit().putString("topic", topic.trim()).apply()
    }
    suspend fun browse(context: Context, following: Boolean = false, query: String? = null): List<ContentItem> {
        val api = withContext(Dispatchers.IO) { AudioApi.get(context) }
        return api.browseContents(50u, null, ContentKind.AUDIO, null, query?.trim()?.takeIf { it.isNotEmpty() }, following)
            .items.filter { it.content.kind == ContentKind.AUDIO }
    }
    suspend fun resolve(context: Context, id: String): androidx.media3.common.MediaItem {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val api = withContext(Dispatchers.IO) { AudioApi.get(context) }
        val detail = api.contentDetail(id)
        require(detail.item.content.kind == ContentKind.AUDIO)
        return detail.toMediaItem()
    }
    suspend fun mix(context: Context, excluded: Set<String>): List<String> {
        val topic = preference(context)
        val catalog = browse(context, query = topic)
        val eligible = catalog.map { it.id }.toSet()
        val snapshot = nearby
        val local = if (SystemClock.elapsedRealtime() - snapshot.at in 0..60_000) snapshot.ids else emptyList()
        // With a topic selected, every candidate must match the server's audio search.
        val regional = if (topic.isEmpty()) local else local.filter { it in eligible }
        return (regional.filterNot { it in excluded }.shuffled() +
            catalog.map { it.id }.filterNot { it in excluded }.shuffled()).distinct().take(12)
    }
}
