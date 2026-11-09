package com.kimyubin33.audioapp.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 보관함에 담기는 콘텐츠 한 건. 목록에 다시 보여 줄 정보만 가진다 (재생 주소는 열 때마다 서버에서 새로 받는다). */
data class LibraryEntry(
    val id: String,
    val title: String,
    val creator: String,
    val aiGenerated: Boolean,
    val isVideo: Boolean,
    val atMillis: Long,
)

/**
 * 시청 기록과 저장한 항목. **이 기기 안에만** 저장하고 서버로 보내지 않는다.
 * (개인정보를 서버에 쌓지 않고, 로그인 없이도 쓸 수 있다. 백업에서도 제외한다.)
 */
class LocalLibrary private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _history = MutableStateFlow(load(KEY_HISTORY))
    private val _saved = MutableStateFlow(load(KEY_SAVED))

    /** 최근에 본 순서 (가장 최근이 맨 앞) */
    val history: StateFlow<List<LibraryEntry>> = _history.asStateFlow()

    /** 저장한 순서 (가장 최근에 저장한 것이 맨 앞) */
    val saved: StateFlow<List<LibraryEntry>> = _saved.asStateFlow()

    /** 방금 연 콘텐츠를 기록에 남긴다. 같은 콘텐츠는 맨 앞으로 옮기고, 오래된 기록은 [HISTORY_MAX]개까지만 둔다. */
    fun recordPlay(entry: LibraryEntry) {
        val next = (listOf(entry) + _history.value.filter { it.id != entry.id }).take(HISTORY_MAX)
        _history.value = next
        persist(KEY_HISTORY, next)
    }

    fun removeFromHistory(id: String) {
        val next = _history.value.filter { it.id != id }
        _history.value = next
        persist(KEY_HISTORY, next)
    }

    fun clearHistory() {
        _history.value = emptyList()
        persist(KEY_HISTORY, emptyList())
    }

    fun isSaved(id: String): Boolean = _saved.value.any { it.id == id }

    /** 저장 상태를 뒤집는다. 저장됐으면 true. 저장 목록이 가득 차면 저장하지 않고 false. */
    fun toggleSaved(entry: LibraryEntry): Boolean {
        val current = _saved.value
        if (current.any { it.id == entry.id }) {
            val next = current.filter { it.id != entry.id }
            _saved.value = next
            persist(KEY_SAVED, next)
            return false
        }
        if (current.size >= SAVED_MAX) return false
        val next = listOf(entry) + current
        _saved.value = next
        persist(KEY_SAVED, next)
        return true
    }

    private fun persist(key: String, entries: List<LibraryEntry>) {
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("creator", it.creator)
                    .put("ai", it.aiGenerated)
                    .put("video", it.isVideo)
                    .put("at", it.atMillis)
            )
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    /** 저장된 값을 읽는다. 깨졌거나 규칙에 맞지 않는 항목은 조용히 버린다 (저장소 내용도 그대로 믿지 않는다). */
    private fun load(key: String): List<LibraryEntry> {
        val text = prefs.getString(key, null) ?: return emptyList()
        return try {
            val array = JSONArray(text)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val title = o.optString("title").trim()
                    val creator = o.optString("creator")
                    if (!ID_RE.matches(id) || !ID_RE.matches(creator) || title.isEmpty() || title.length > TITLE_MAX) continue
                    add(LibraryEntry(id, title, creator, o.optBoolean("ai", true), o.optBoolean("video", false), o.optLong("at", 0L)))
                }
            }.distinctBy { it.id }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        const val HISTORY_MAX = 200
        const val SAVED_MAX = 500
        private const val TITLE_MAX = 100
        private const val PREFS_NAME = "library" // 백업 제외 규칙의 파일명(library.xml)과 같아야 한다
        private const val KEY_HISTORY = "history"
        private const val KEY_SAVED = "saved"
        private val ID_RE = Regex("^[A-Za-z0-9_-]{1,64}$")

        @Volatile
        private var instance: LocalLibrary? = null

        fun get(context: Context): LocalLibrary =
            instance ?: synchronized(this) { instance ?: LocalLibrary(context).also { instance = it } }
    }
}
