package com.kimyubin33.audioapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kimyubin33.audioapp.data.AccountActions
import com.kimyubin33.audioapp.data.AudioApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.audio_core.ContentDetail
import uniffi.audio_core.NetException
import uniffi.audio_core.ReportReason

/** 홈 화면이 그리는 상태. */
data class HomeUiState(
    val items: List<HomeItem> = emptyList(),
    /** 다음 페이지가 있으면 그 커서 */
    val nextCursor: String? = null,
    val loading: Boolean = false,
    /** 사용자에게 보여줄 오류 문구 (없으면 null) */
    val error: String? = null,
    /** 첫 페이지를 한 번이라도 성공적으로 받았는가 */
    val loaded: Boolean = false,
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

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** 아직 한 번도 불러오지 않았을 때만 첫 페이지를 불러온다. (화면 회전 때 다시 부르지 않기 위함) */
    fun loadIfNeeded() {
        val s = _state.value
        if (!s.loaded && !s.loading) load(reset = true)
    }

    /** 오류 뒤 다시 시도: 목록이 비어 있으면 처음부터, 있으면 이어서 불러온다. */
    fun retry() {
        if (_state.value.loading) return
        load(reset = _state.value.items.isEmpty())
    }

    /** 목록을 처음부터 다시 불러온다. (차단·차단 해제 결과를 반영) */
    fun refresh() {
        if (_state.value.loading) return
        load(reset = true)
    }

    /** 콘텐츠를 신고한다. 실패하면 예외를 던진다. */
    suspend fun report(contentId: String, reason: ReportReason, detail: String?) =
        AccountActions.report(getApplication(), contentId, reason, detail)

    /** 크리에이터를 차단한다. 실패하면 예외를 던진다. */
    suspend fun block(creatorId: String) = AccountActions.block(getApplication(), creatorId)

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.nextCursor == null) return
        load(reset = false)
    }

    /** 곡 하나의 상세(재생 주소 포함)를 가져온다. 실패하면 [NetException] 등을 던진다. */
    suspend fun loadDetail(id: String): ContentDetail {
        val api = withContext(Dispatchers.IO) { AudioApi.get(getApplication()) }
        return api.contentDetail(id)
    }

    private fun load(reset: Boolean) {
        val cursor = if (reset) null else _state.value.nextCursor
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                // Keystore·파일 읽기가 들어 있어서 메인 스레드 밖에서 만든다
                val api = withContext(Dispatchers.IO) { AudioApi.get(getApplication()) }
                val page = api.listContents(PAGE_SIZE, cursor)
                val received = page.items.map {
                    HomeItem(
                        id = it.id,
                        title = it.content.title,
                        creator = it.content.creatorId,
                        aiGenerated = it.content.aiGenerated,
                    )
                }
                _state.update {
                    it.copy(
                        items = if (reset) received else (it.items + received).distinctBy { item -> item.id },
                        nextCursor = page.nextCursor,
                        loading = false,
                        loaded = true,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = userMessage(e)) }
            }
        }
    }

    private companion object {
        val PAGE_SIZE = 20u
    }
}
