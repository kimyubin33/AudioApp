package com.kimyubin33.audioapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kimyubin33.audioapp.data.AccountActions
import com.kimyubin33.audioapp.data.AudioApi
import com.kimyubin33.audioapp.data.LibraryEntry
import com.kimyubin33.audioapp.data.LocalLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.audio_core.ContentDetail
import uniffi.audio_core.ContentKind
import uniffi.audio_core.ContentPage
import uniffi.audio_core.NetException
import uniffi.audio_core.ReportReason

/** 목록(피드) 종류. 하단 탭 중 오디오·숏츠·구독이 각각 하나씩 가진다. */
enum class FeedKind { Audio, Shorts, Subscribed }

/** 목록 하나가 그리는 상태. */
data class HomeUiState(
    val items: List<HomeItem> = emptyList(),
    /** 다음 페이지가 있으면 그 커서 */
    val nextCursor: String? = null,
    val loading: Boolean = false,
    /** 사용자에게 보여줄 오류 문구 (없으면 null) */
    val error: String? = null,
    /** 첫 페이지를 한 번이라도 성공적으로 받았는가 */
    val loaded: Boolean = false,
    /** 차단·구독이 바뀌어서 다시 불러와야 하는가. 화면에 보이는 목록이 다시 불러온다 */
    val stale: Boolean = false,
)

/** 서버 오류를 사용자에게 보여줄 한국어 문구로 바꾼다. 내부 정보는 담지 않는다. */
fun userMessage(e: Throwable): String = when (e) {
    is NetException.Network -> "서버에 연결할 수 없습니다. 연결을 확인해 주세요."
    is NetException.RateLimited -> "요청이 많습니다. 잠시 후 다시 시도해 주세요."
    is NetException.ServerException -> "서버에 문제가 있습니다. 잠시 후 다시 시도해 주세요."
    is NetException.NotFound -> "찾을 수 없는 항목입니다."
    is NetException.Unauthorized -> "로그인이 필요합니다."
    is NetException.InvalidRequest -> "입력을 확인해 주세요."
    else -> "불러오지 못했습니다. 다시 시도해 주세요."
}

fun ContentDetail.toLibraryEntry() = LibraryEntry(
    id = item.id,
    title = item.content.title,
    creator = item.content.creatorId,
    aiGenerated = item.content.aiGenerated,
    isVideo = item.content.kind == ContentKind.SHORT_VIDEO,
    atMillis = System.currentTimeMillis(),
)

fun HomeItem.toLibraryEntry() = LibraryEntry(
    id = id, title = title, creator = creator, aiGenerated = aiGenerated, isVideo = isVideo, atMillis = System.currentTimeMillis(),
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val feeds: Map<FeedKind, MutableStateFlow<HomeUiState>> =
        FeedKind.entries.associateWith { MutableStateFlow(HomeUiState()) }

    fun feed(kind: FeedKind): StateFlow<HomeUiState> = feeds.getValue(kind).asStateFlow()

    // ── 보관함 (이 기기 안에만 저장) ─────────────────────────────
    private val library = LocalLibrary.get(application)
    val history: StateFlow<List<LibraryEntry>> = library.history
    val saved: StateFlow<List<LibraryEntry>> = library.saved

    fun recordPlay(detail: ContentDetail) = library.recordPlay(detail.toLibraryEntry())
    fun removeFromHistory(id: String) = library.removeFromHistory(id)
    fun clearHistory() = library.clearHistory()

    /** 저장 상태를 뒤집는다. 저장됐으면 true. */
    fun toggleSaved(entry: LibraryEntry): Boolean = library.toggleSaved(entry)

    // ── 구독 ─────────────────────────────────────────────────────
    private val _subscribed = MutableStateFlow<Set<String>>(emptySet())

    /** 내가 구독 중인 크리에이터. 로그인하지 않았으면 비어 있다. */
    val subscribed: StateFlow<Set<String>> = _subscribed.asStateFlow()

    /** 구독 목록을 서버에서 다시 읽는다. 로그인돼 있지 않으면 조용히 넘어간다. */
    fun refreshSubscriptions() {
        viewModelScope.launch {
            try {
                _subscribed.value = AccountActions.subscribedCreatorsIfLoggedIn(getApplication()).toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 구독 표시만 못 갱신될 뿐이라 화면에 오류를 띄우지 않는다
            }
        }
    }

    /** 구독 상태를 뒤집는다. 구독했으면 true. 실패하면 예외를 던진다. */
    suspend fun toggleSubscription(creatorId: String): Boolean {
        val now = creatorId !in _subscribed.value
        if (now) AccountActions.subscribe(getApplication(), creatorId) else AccountActions.unsubscribe(getApplication(), creatorId)
        _subscribed.update { if (now) it + creatorId else it - creatorId }
        feeds.getValue(FeedKind.Subscribed).update { it.copy(stale = true) } // 구독 목록이 바뀌었으니 다시 불러온다
        return now
    }

    // ── 목록 불러오기 ────────────────────────────────────────────
    /** 아직 불러오지 않았거나(또는 다시 불러와야 할 때) 첫 페이지를 불러온다. 화면 회전 때 다시 부르지 않기 위함. */
    fun loadIfNeeded(kind: FeedKind) {
        val s = feeds.getValue(kind).value
        if ((!s.loaded || s.stale) && !s.loading) load(kind, reset = true)
    }

    /** 오류 뒤 다시 시도: 목록이 비어 있으면 처음부터, 있으면 이어서 불러온다. */
    fun retry(kind: FeedKind) {
        val s = feeds.getValue(kind).value
        if (s.loading) return
        load(kind, reset = s.items.isEmpty())
    }

    fun loadMore(kind: FeedKind) {
        val s = feeds.getValue(kind).value
        if (s.loading || s.nextCursor == null) return
        load(kind, reset = false)
    }

    /** 모든 목록을 "다시 불러올 것"으로 표시한다. (차단·차단 해제 결과를 반영) 보이는 목록이 곧 다시 불러온다. */
    fun markAllStale() {
        feeds.values.forEach { flow -> flow.update { it.copy(stale = true) } }
    }

    // ── 신고·차단 ────────────────────────────────────────────────
    /** 콘텐츠를 신고한다. 실패하면 예외를 던진다. */
    suspend fun report(contentId: String, reason: ReportReason, detail: String?) =
        AccountActions.report(getApplication(), contentId, reason, detail)

    /** 크리에이터를 차단한다. 실패하면 예외를 던진다. */
    suspend fun block(creatorId: String) = AccountActions.block(getApplication(), creatorId)

    /** 차단을 푼다. 실패하면 예외를 던진다. */
    suspend fun unblock(creatorId: String) = AccountActions.unblock(getApplication(), creatorId)

    /** 내가 차단한 크리에이터 목록. 실패하면 예외를 던진다. */
    suspend fun blockedCreators(): List<String> = AccountActions.blockedCreators(getApplication())

    /** 곡 하나의 상세(재생 주소 포함)를 가져온다. 실패하면 [NetException] 등을 던진다. */
    suspend fun loadDetail(id: String): ContentDetail {
        val api = withContext(Dispatchers.IO) { AudioApi.get(getApplication()) }
        return api.contentDetail(id)
    }

    private suspend fun fetch(kind: FeedKind, cursor: String?): ContentPage {
        val context = getApplication<Application>()
        return when (kind) {
            FeedKind.Audio -> api().listContents(PAGE_SIZE, cursor, ContentKind.AUDIO, false)
            FeedKind.Shorts -> api().listContents(PAGE_SIZE, cursor, ContentKind.SHORT_VIDEO, false)
            // 구독 탭은 로그인이 필요하다 (debug에서는 개발 로그인을 자동으로 한다)
            FeedKind.Subscribed -> AccountActions.withLogin(context) { it.listContents(PAGE_SIZE, cursor, null, true) }
        }
    }

    // Keystore·파일 읽기가 들어 있어서 메인 스레드 밖에서 만든다
    private suspend fun api() = withContext(Dispatchers.IO) { AudioApi.get(getApplication()) }

    private fun load(kind: FeedKind, reset: Boolean) {
        val flow = feeds.getValue(kind)
        val cursor = if (reset) null else flow.value.nextCursor
        flow.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val page = fetch(kind, cursor)
                val received = page.items.map {
                    HomeItem(
                        id = it.id,
                        title = it.content.title,
                        creator = it.content.creatorId,
                        aiGenerated = it.content.aiGenerated,
                        isVideo = it.content.kind == ContentKind.SHORT_VIDEO,
                    )
                }
                flow.update {
                    it.copy(
                        items = if (reset) received else (it.items + received).distinctBy { item -> item.id },
                        nextCursor = page.nextCursor,
                        loading = false,
                        loaded = true,
                        stale = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 구독 탭: 로그인이 안 된 것은 오류가 아니라 안내 문구로 보여 준다 (userMessage가 "로그인이 필요합니다.")
                flow.update { it.copy(loading = false, error = userMessage(e), stale = false) }
            }
        }
    }

    private companion object {
        val PAGE_SIZE = 20u
    }
}
