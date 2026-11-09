package com.kimyubin33.audioapp.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kimyubin33.audioapp.data.memory.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class MemoryUiState(
    val album: MemoryAlbum = MemoryAlbum(), val loaded: Boolean = false,
    val busy: Boolean = false, val saving: Boolean = false, val error: String? = null,
)

/** 화면 회전·다른 탭 이동에도 같은 초안. 디스크 읽기/쓰기/사진 디코딩은 메인 스레드에서 하지 않는다. */
class MemoryViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(MemoryUiState())
    val state = _state.asStateFlow()
    private var store: MemoryStore? = null
    private val gate = Mutex()
    private var autosave: Job? = null
    init { reload() }

    fun reload() {
        if(_state.value.loaded || _state.value.busy) return
        _state.value = _state.value.copy(busy=true,error=null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { val s=MemoryStore(getApplication()); s to s.load() }
                store=result.first
                _state.value=MemoryUiState(album=result.second,loaded=true)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { _state.value=_state.value.copy(busy=false,error="앨범을 읽지 못했습니다. 기존 파일은 보존했습니다. 다시 시도해 주세요.") }
        }
    }
    fun photo(id: String): File? = store?.photo(id)

    fun edit(change: (MemoryDraft) -> MemoryDraft) {
        val current=_state.value
        if(!current.loaded || current.busy) return
        val draft=change(current.album.draft)
        if(draft.title.length>80 || draft.body.length>2000 || draft.place.length>80 || draft.candidate.length>2000) return
        _state.value=current.copy(album=current.album.copy(draft=draft),saving=true,error=null)
        autosave?.cancel()
        autosave=viewModelScope.launch { delay(300); persist() }
    }
    fun flush() {
        if(!_state.value.loaded) return
        autosave?.cancel()
        autosave=viewModelScope.launch { persist() }
    }
    private suspend fun persist() {
        gate.withLock {
            val album=_state.value.album
            try {
                withContext(Dispatchers.IO) { checkNotNull(store).save(album) }
                if(_state.value.album==album) _state.value=_state.value.copy(saving=false,error=null)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { _state.value=_state.value.copy(saving=false,error="초안을 저장하지 못했습니다. 저장 공간을 확인하고 다시 시도해 주세요.") }
        }
    }
    fun importPhoto(uri: Uri) {
        if(!_state.value.loaded || _state.value.busy) return
        _state.value=_state.value.copy(busy=true,error=null)
        autosave?.cancel()
        viewModelScope.launch {
            gate.withLock {
                try {
                    // 사진 검사가 실패해도 선택기를 열기 전에 쓰던 글은 디스크에 남긴다.
                    val original=_state.value.album
                    withContext(Dispatchers.IO) { checkNotNull(store).save(original) }
                    _state.value=_state.value.copy(saving=false)
                    val id=withContext(Dispatchers.IO) { checkNotNull(store).importPhoto(uri) }
                    val next=_state.value.album.let { it.copy(draft=it.draft.copy(photoId=id)) }
                    withContext(Dispatchers.IO) { checkNotNull(store).save(next) }
                    _state.value=_state.value.copy(album=next,saving=false)
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) {
                    val known = setOf("사진은 10MB 이하로 골라 주세요.", "40MP 이하 JPEG·PNG·WebP 사진을 골라 주세요.",
                        "손상된 사진입니다. 다른 사진을 골라 주세요.", "앨범 용량이 가득 찼습니다. 추억을 정리한 뒤 다시 골라 주세요.")
                    _state.value=_state.value.copy(error=e.message?.takeIf { it in known }
                        ?: "사진을 가져오지 못했습니다. 기존 초안은 유지됩니다. 다른 사진으로 다시 시도해 주세요.")
                }
                finally { _state.value=_state.value.copy(busy=false) }
            }
        }
    }
    fun applyCandidate() = edit { if(it.candidate.isBlank()) it else it.copy(previousBody=it.body,body=it.candidate,candidate="") }
    fun undoCandidate() = edit { if(it.previousBody==null) it else it.copy(body=it.previousBody,previousBody=null,candidate=it.body) }

    suspend fun saveMemory(): String? = withContext(NonCancellable) {
        if(!_state.value.loaded || _state.value.busy || !_state.value.album.draft.canSave) return@withContext null
        autosave?.cancel()
        _state.value=_state.value.copy(busy=true,error=null)
        gate.withLock {
            try {
                val a=_state.value.album
                check(a.memories.size<MemoryStore.MAX_MEMORIES) { "최대 200개의 추억을 보관할 수 있습니다." }
                val d=a.draft
                val memory=PlaceMemory(d.id,d.title.trim(),d.body.trim(),d.place.trim(),d.photoId,System.currentTimeMillis())
                val next=MemoryAlbum(memories=listOf(memory)+a.memories)
                withContext(Dispatchers.IO) { checkNotNull(store).save(next) }
                _state.value=_state.value.copy(album=next,saving=false)
                memory.id
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { _state.value=_state.value.copy(error="추억을 저장하지 못했습니다. 저장 공간과 앨범 개수를 확인해 주세요."); null }
            finally { _state.value=_state.value.copy(busy=false) }
        }
    }
    suspend fun deleteMemory(id: String): Boolean = withContext(NonCancellable) {
        if(!_state.value.loaded || _state.value.busy) return@withContext false
        autosave?.cancel()
        _state.value=_state.value.copy(busy=true,error=null)
        gate.withLock {
            try {
                val a=_state.value.album
                val next=a.copy(memories=a.memories.filterNot { it.id==id })
                withContext(Dispatchers.IO) { checkNotNull(store).save(next) }
                _state.value=_state.value.copy(album=next,saving=false)
                true
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { _state.value=_state.value.copy(error="추억을 삭제하지 못했습니다. 다시 시도해 주세요."); false }
            finally { _state.value=_state.value.copy(busy=false) }
        }
    }
}
