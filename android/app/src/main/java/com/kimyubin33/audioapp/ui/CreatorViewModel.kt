package com.kimyubin33.audioapp.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kimyubin33.audioapp.data.AccountActions
import com.kimyubin33.audioapp.data.AudioApi
import com.kimyubin33.audioapp.data.creator.CreatorDraft
import com.kimyubin33.audioapp.data.creator.CreatorDraftStore
import com.kimyubin33.audioapp.data.creator.CreatorFileImporter
import com.kimyubin33.audioapp.data.creator.CreatorFileRules
import com.kimyubin33.audioapp.data.creator.PostPhotoImporter
import com.kimyubin33.audioapp.data.memory.PlaceMemory
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.audio_core.CreatorContent
import uniffi.audio_core.CreatorProfile
import uniffi.audio_core.NetException

data class CreatorUiState(
    val drafts: List<CreatorDraft> = emptyList(),
    val selectedId: String? = null,
    val restored: Boolean = false,
    val profile: CreatorProfile? = null,
    val contents: List<CreatorContent> = emptyList(),
    val nextCursor: String? = null,
    val refreshing: Boolean = false,
    val busy: Boolean = false,
    val stage: String = "",
    val message: String? = null,
    val importNotices: List<String> = emptyList(),
    val audioChoices: List<HomeItem> = emptyList(),
    val audioLoading: Boolean = false,
)

class CreatorViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CreatorDraftStore(application)
    private val saveLock = Mutex()
    private val _state = MutableStateFlow(CreatorUiState())
    val state = _state.asStateFlow()
    private var saving: Job? = null
    private var operation: Job? = null

    init {
        viewModelScope.launch {
            try {
                val drafts = withContext(Dispatchers.IO) { store.load().also(store::clearOrphans) }
                _state.update { it.copy(drafts = drafts, restored = true) }
            } catch (_: Exception) {
                // 손상된 초안 파일을 자동 덮어쓰지 않는다.
                _state.update { it.copy(message = "저장된 초안을 읽지 못했습니다. 앱을 다시 열어 주세요.") }
            }
        }
    }

    fun refresh(more: Boolean = false) {
        if (_state.value.refreshing) return
        val cursor = if (more) _state.value.nextCursor ?: return else null
        _state.update { it.copy(refreshing = true, message = null) }
        viewModelScope.launch {
            try {
                val profile = AccountActions.withLogin(getApplication()) { it.creatorProfile() }
                val page = AccountActions.withLogin(getApplication()) { it.myCreatorContents(cursor) }
                _state.update { it.copy(profile = profile, contents = if (more) (it.contents + page.items).distinctBy { c -> c.id } else page.items,
                    nextCursor = page.nextCursor) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { if (e is NetException.Unauthorized) it.copy(profile = null, contents = emptyList(), nextCursor = null, message = creatorMessage(e))
                    else it.copy(message = creatorMessage(e)) }
            }
            finally { _state.update { it.copy(refreshing = false) } }
        }
    }

    private var audioSearch: Job? = null
    fun findAudio(query:String="") {
        audioSearch?.cancel()
        audioSearch=viewModelScope.launch {
            _state.update{it.copy(audioLoading=true)}
            try {
                val api=withContext(Dispatchers.IO){AudioApi.get(getApplication())}
                val page=api.browseContents(50u,null,uniffi.audio_core.ContentKind.AUDIO,null,query.trim().ifBlank{null},false)
                _state.update{it.copy(audioChoices=page.items.map{item->item.toHomeItem()})}
            } catch(e:CancellationException){throw e} catch(_:Exception){_state.update{it.copy(audioChoices=emptyList(),message="오디오를 불러오지 못했습니다.")}}
            finally{_state.update{it.copy(audioLoading=false)}}
        }
    }
    fun newPost(memory:PlaceMemory?=null,photo:File?=null,onReady:()->Unit={}) {
        if(_state.value.busy||!_state.value.restored) return
        if(_state.value.drafts.size>=CreatorFileRules.MAX_DRAFTS){_state.update{it.copy(message="초안은 최대 20개입니다.")};return}
        if(_state.value.drafts.sumOf{it.bytes}+(photo?.length()?:0)>CreatorFileRules.MAX_IMPORT_BYTES){_state.update{it.copy(message="초안 파일의 합계는 360MiB 이하입니다.")};return}
        _state.update{it.copy(busy=true,stage="게시글 초안 준비 중",message=null)}
        operation=viewModelScope.launch {
            try {
                val draft=withContext(Dispatchers.IO) {
                    val base=photo?.let{PostPhotoImporter(getApplication(),store).copy(it)}?:CreatorDraft(isPost=true,aiGenerated=false)
                    base.copy(title=memory?.title.orEmpty(),description=memory?.body.orEmpty(),placeName=memory?.place.orEmpty())
                }
                _state.update{it.copy(drafts=it.drafts+draft,selectedId=draft.id)}
                persist();onReady()
            } catch(e:CancellationException){throw e} catch(_:Exception){_state.update{it.copy(message="게시글 초안을 만들지 못했습니다.")}}
            finally{_state.update{it.copy(busy=false,stage="")}}
        }
    }
    fun addPostPhoto(id:String,uri:Uri) {
        val old=_state.value.drafts.find{it.id==id}?:return
        if(_state.value.busy||!old.isPost||old.remoteId!=null||old.uploadOwner!=null||old.submittedId!=null) return
        _state.update{it.copy(busy=true,stage="사진 확인 중",message=null)}
        operation=viewModelScope.launch {
            var added:CreatorDraft?=null
            var installed=false
            try {
                withContext(Dispatchers.IO){added=PostPhotoImporter(getApplication(),store).import(uri)}
                val photo=checkNotNull(added)
                require(_state.value.drafts.sumOf{it.bytes}-old.bytes+photo.bytes<=CreatorFileRules.MAX_IMPORT_BYTES)
                updateInternal(id){it.copy(fileId=photo.fileId,name=photo.name,source=photo.source,bytes=photo.bytes,sha256=photo.sha256,
                    width=photo.width,height=photo.height,previewConfirmed=false)}
                installed=true
                persist()
                withContext(Dispatchers.IO){store.delete(old)}
            } catch(e:CancellationException){throw e}
            catch(_:Exception){_state.update{it.copy(message="사진은 JPG·PNG·WebP, 10MB·4천만 화소 이하로 선택해 주세요.")}}
            finally{
                if(!installed)withContext(NonCancellable+Dispatchers.IO){added?.let{store.delete(it)}}
                _state.update{it.copy(busy=false,stage="")}
            }
        }
    }
    fun removePostPhoto(id:String) {
        val old=_state.value.drafts.find{it.id==id}?:return
        if(_state.value.busy||!old.isPost||old.remoteId!=null||old.uploadOwner!=null) return
        change(id){it.copy(fileId=null,name="",source="",bytes=0,sha256="",width=0,height=0,previewConfirmed=false)}
        viewModelScope.launch{
            try{persist();withContext(Dispatchers.IO){store.delete(old)}}
            catch(e:CancellationException){throw e}
            catch(_:Exception){_state.update{it.copy(message="사진 변경을 저장하지 못했습니다. 저장 공간을 확인해 주세요.")}}
        }
    }

    fun select(id: String?) { _state.update { it.copy(selectedId = id, message = null) } }

    fun change(id: String, transform: (CreatorDraft) -> CreatorDraft) {
        if (_state.value.busy || !_state.value.restored) return
        _state.update { s -> s.copy(drafts = s.drafts.map {
            if (it.id == id && it.uploadId == null && !(it.textPost && it.uploadOwner!=null) && it.submittedId == null) transform(it) else it
        }, message = null) }
        saveSoon()
    }

    private fun saveSoon() {
        saving?.cancel()
        saving = viewModelScope.launch {
            delay(300)
            try { persist() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(message = "초안을 저장하지 못했습니다. 저장 공간을 확인해 주세요.") } }
        }
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        saveLock.withLock { store.save(_state.value.drafts) }
    }

    fun remove(id: String) {
        if (_state.value.busy || !_state.value.restored) return
        val draft = _state.value.drafts.find { it.id == id } ?: return
        _state.update { it.copy(drafts = it.drafts.filterNot { d -> d.id == id }, selectedId = null) }
        viewModelScope.launch {
            try { persist(); withContext(Dispatchers.IO) { store.delete(draft) } }
            catch (_: Exception) { _state.update { it.copy(message = "초안을 정리하지 못했습니다.") } }
        }
    }

    fun import(uris: List<Uri>) = importFiles(uris,null,{})
    fun importMomentVideo(uri:Uri,memory:PlaceMemory,onReady:()->Unit) = importFiles(listOf(uri),memory,onReady)
    private fun importFiles(uris: List<Uri>,memory:PlaceMemory?,onReady:()->Unit) {
        if (_state.value.busy || !_state.value.restored || uris.isEmpty()) return
        _state.update { it.copy(busy = true, message = null, importNotices = emptyList(), stage = "파일 확인 중") }
        operation = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    CreatorFileImporter(getApplication(), store).import(uris, _state.value.drafts) { stage -> _state.update { it.copy(stage = stage) } }
                }
                val created=if(memory==null) result.drafts else result.drafts.filter{it.isVideo}.map {
                    it.copy(title=memory.title,description=memory.body,placeName=memory.place,shortStyle="moment",aiGenerated=false)
                }
                _state.update { it.copy(drafts = it.drafts + created,
                    selectedId = created.firstOrNull()?.id, importNotices = result.notices,
                    message = if (created.isEmpty()) "추가할 수 있는 미디어가 없습니다. 검사 결과를 확인해 주세요." else "${created.size}개 파일을 초안에 추가했습니다.") }
                persist()
                if(created.isNotEmpty()) onReady()
            } catch (e: CancellationException) { _state.update { it.copy(message = "파일 가져오기를 중지했습니다.") }; throw e }
            catch (_: Exception) { _state.update { it.copy(message = "파일을 가져오지 못했습니다. 저장 공간과 파일을 확인해 주세요.") } }
            finally {
                withContext(NonCancellable) {
                    try { persist() } catch (_: Exception) { _state.update { it.copy(message = "초안을 저장하지 못했습니다. 저장 공간을 확인해 주세요.") } }
                    withContext(Dispatchers.IO) { store.clearOrphans(_state.value.drafts) }
                }
                _state.update { it.copy(busy = false, stage = "") }
            }
        }
    }

    fun editRemote(content: CreatorContent) {
        if (_state.value.busy || !_state.value.restored) return
        if (_state.value.drafts.size >= CreatorFileRules.MAX_DRAFTS) { _state.update { it.copy(message = "초안은 최대 20개입니다.") }; return }
        val existing = _state.value.drafts.find { it.remoteId == content.id && it.submittedId == null }
        if (existing != null) { select(existing.id); return }
        val draft = CreatorDraft.fromRemote(content) ?: return
        _state.update { it.copy(drafts = it.drafts + draft, selectedId = draft.id, message = null) }
        saveSoon()
    }

    fun file(draft: CreatorDraft) = draft.fileId?.let(store::file)

    fun submit(id: String) {
        if (_state.value.busy || !_state.value.restored) return
        val draft = _state.value.drafts.find { it.id == id } ?: return
        if (draft.isVideo || draft.isPost) { _state.update { it.copy(message = "현재 버전에서는 오디오만 제출할 수 있습니다.") }; return }
        val invalid = draft.validationMessage()
        if (invalid != null) { _state.update { it.copy(message = invalid) }; return }
        _state.update { it.copy(busy = true, message = null, stage = "권한과 제출 상태 확인 중") }
        operation = viewModelScope.launch {
            try {
                val profile = AccountActions.withLogin(getApplication()) { it.creatorProfile() }
                _state.update { it.copy(profile = profile) }
                if (!profile.canUpload) throw NetException.Forbidden()
                if (draft.remoteId != null) {
                    AccountActions.withLogin(getApplication()) { it.editCreatorContent(draft.remoteId, draft.revision, draft.metadata()) }
                    markSubmitted(id, draft.remoteId)
                } else if(draft.textPost) {
                    if(draft.uploadOwner!=null&&draft.uploadOwner!=profile.userId) throw NetException.Forbidden()
                    updateInternal(id){it.copy(uploadOwner=profile.userId)}
                    persist()
                    val contentId=AccountActions.withLogin(getApplication()){it.createCreatorPost(draft.id,draft.metadata())}
                    markSubmitted(id,contentId)
                } else {
                    val api = withContext(Dispatchers.IO) { AudioApi.get(getApplication()) }
                    var completedId: String? = null
                    if (draft.uploadId != null) {
                        if (draft.uploadOwner != profile.userId) throw NetException.Forbidden()
                        val old = try { AccountActions.withLogin(getApplication()) { it.creatorUploadState(draft.uploadId) } }
                            catch (_: NetException.NotFound) { null }
                        when (old?.state) {
                            "completed" -> completedId = old.contentId
                            "uploaded" -> {
                                AccountActions.withLogin(getApplication()) { it.completeCreatorUpload(draft.uploadId) }
                                completedId = old.contentId
                            }
                            "created", "uploading" -> AccountActions.withLogin(getApplication()) { it.cancelCreatorUpload(draft.uploadId) }
                        }
                    }
                    if (completedId == null) {
                        _state.update { it.copy(stage = "원본 크기와 SHA-256 대조 중") }
                        val file = file(draft) ?: throw NetException.InvalidRequest()
                        val coroutine = currentCoroutineContext()
                        val valid = withContext(Dispatchers.IO) {
                            CreatorFileRules.verify(file, draft.bytes, draft.sha256) { coroutine.ensureActive() }
                        }
                        if (!valid) throw NetException.InvalidRequest()
                        val ticket = AccountActions.withLogin(getApplication()) { it.createCreatorUpload(draft.metadata()) }
                        updateInternal(id) { it.copy(uploadId = ticket.uploadId, uploadOwner = profile.userId) }
                        persist() // 재시작 후 완료 여부를 먼저 조회하도록 파일 전송 전에 id를 보존한다.
                        _state.update { it.copy(stage = "파일 전송 중 · ${draft.name}") }
                        api.uploadCreatorFile(ticket, file.absolutePath, draft.contentType, draft.bytes.toULong())
                        _state.update { it.copy(stage = "서버에서 원본 검증·검수 등록 중") }
                        AccountActions.withLogin(getApplication()) { it.completeCreatorUpload(ticket.uploadId) }
                        completedId = AccountActions.withLogin(getApplication()) { it.creatorUploadState(ticket.uploadId) }.contentId
                    }
                    markSubmitted(id, completedId)
                }
                persist()
                _state.update { it.copy(message = "검수를 요청했습니다. 승인 후 피드에 공개됩니다.") }
                refresh()
            } catch (e: CancellationException) {
                _state.update { it.copy(message = "전송을 중지했습니다. 다시 시도하면 서버 상태부터 확인합니다.") }
                throw e
            } catch (e: Exception) {
                _state.update { if (e is NetException.Unauthorized) it.copy(profile = null, contents = emptyList(), nextCursor = null, message = creatorMessage(e))
                    else it.copy(message = creatorMessage(e)) }
            } finally {
                withContext(NonCancellable) {
                    try { persist() } catch (_: Exception) { _state.update { it.copy(message = "제출 상태 저장에 실패했습니다. 제출 목록을 새로고침해 확인해 주세요.") } }
                }
                _state.update { it.copy(busy = false, stage = "") }
            }
        }
    }

    private fun markSubmitted(id: String, contentId: String) = updateInternal(id) { it.copy(submittedId = contentId) }
    private fun updateInternal(id: String, transform: (CreatorDraft) -> CreatorDraft) {
        _state.update { it.copy(drafts = it.drafts.map { d -> if (d.id == id) transform(d) else d }) }
    }
    suspend fun removeDeletedAccountDrafts(userId: String) {
        operation?.cancel(); operation?.join()
        saving?.cancel(); saving?.join()
        val remoteIds = if (_state.value.profile?.userId == userId) _state.value.contents.map { it.id }.toSet() else emptySet()
        withContext(Dispatchers.IO) {
            saveLock.withLock {
                // Never erase guest/another account's unsent drafts.
                val drafts = store.load()
                val removed = drafts.filter { it.uploadOwner == userId || it.remoteId in remoteIds }
                val remaining = drafts - removed.toSet()
                store.save(remaining)
                removed.forEach(store::delete)
                store.clearOrphans(remaining)
                _state.update { it.copy(drafts=remaining,selectedId=null,profile=null,contents=emptyList(),nextCursor=null) }
            }
        }
    }

    fun cancel() { operation?.cancel() }
}

private fun creatorMessage(e: Exception): String = when (e) {
    is NetException.Forbidden -> "승인된 크리에이터 계정이 필요합니다. 다른 계정의 초안 제출 결과는 해당 계정으로 확인해 주세요."
    is NetException.Conflict -> "서버 상태 또는 검수 버전이 바뀌었습니다. 제출 목록을 새로고침한 뒤 다시 확인해 주세요."
    is NetException.UploadDailyCountExceeded -> "최근 24시간의 업로드 건수 한도에 도달했습니다. 초안은 보관됩니다. 최대 24시간 뒤 다시 시도해 주세요."
    is NetException.UploadDailyBytesExceeded -> "최근 24시간의 업로드 용량 한도를 넘습니다. 초안은 보관됩니다. 파일 크기를 확인하고 최대 24시간 뒤 다시 시도해 주세요."
    is NetException.StorageQuotaExceeded -> "보관·예약 용량 한도를 넘습니다. 취소한 전송의 정리가 끝난 뒤 확인하거나 운영자에게 문의해 주세요. 초안은 보관됩니다."
    is NetException.UploadExpired -> "업로드가 만료되었습니다. 다시 시도하면 새 전송을 시작합니다."
    is NetException.InvalidRequest -> "입력 또는 파일 검증에 실패했습니다. 원본·권리 정보와 90MiB 상한을 확인해 주세요."
    else -> userMessage(e)
}
