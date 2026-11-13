package com.kimyubin33.audioapp.ui

import android.app.Application
import android.net.Uri
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kimyubin33.audioapp.BuildConfig
import com.kimyubin33.audioapp.KeystoreTokenStore
import com.kimyubin33.audioapp.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import uniffi.audio_core.NetException

data class AccountIdentity(val userId: String, val creatorId: String? = null)

interface AccountSource {
    val changes: StateFlow<Long>
    suspend fun hasSession(): Boolean
    suspend fun identity(): AccountIdentity
    suspend fun signOut()
    suspend fun developerSignIn()
}

private class AndroidAccountSource(private val application: Application) : AccountSource {
    override val changes = SessionSignals.changes
    private suspend fun api() = withContext(Dispatchers.IO) { AudioApi.get(application) }
    override suspend fun hasSession() = withContext(Dispatchers.IO) { AudioApi.get(application).hasSession() }
    override suspend fun identity(): AccountIdentity {
        try {
            val p = api().creatorProfile()
            return AccountIdentity(p.userId, p.creatorId)
        } catch (e: NetException.Unauthorized) {
            withContext(Dispatchers.IO) { AudioApi.get(application).signOut() }
            throw e
        }
    }
    override suspend fun signOut() {
        withContext(Dispatchers.IO) {
            KeystoreTokenStore(application).setDevAutoLoginAllowed(false)
            AudioApi.get(application).signOut()
        }
        try { CredentialManager.create(application).clearCredentialState(ClearCredentialStateRequest()) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* 앱 세션은 이미 제거했다. 공급자 상태 정리는 최선으로 시도한다. */ }
    }
    override suspend fun developerSignIn() {
        check(BuildConfig.DEBUG && BuildConfig.DEV_LOGIN)
        api().signInWithGoogle("dev-id-token")
        withContext(Dispatchers.IO) { KeystoreTokenStore(application).setDevAutoLoginAllowed(true) }
    }
}

data class AccountUiState(
    val userId: String? = null,
    val profile: LocalProfile? = null,
    val avatar: File? = null,
    val hasSession: Boolean = false,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val signedIn: Boolean get() = userId != null
    val name: String get() = profile?.name?.takeIf { it.isNotBlank() } ?: "Member"
}

class AccountViewModel @JvmOverloads constructor(
    application: Application,
    private val source: AccountSource = AndroidAccountSource(application),
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(AccountUiState())
    val state = _state.asStateFlow()
    private val refreshes = MutableStateFlow(0L)
    private val files = Mutex()
    private var store: LocalProfileStore? = null
    private var mutating = false
    private var authVersion: Long? = null

    init {
        viewModelScope.launch {
            val authChanges=source.changes.onEach { version ->
                if(authVersion!=version) {
                    authVersion=version
                    _state.value=AccountUiState(busy=mutating)
                }
            }
            combine(authChanges, refreshes) { auth, _ -> auth }.collectLatest { version ->
                _state.value=_state.value.copy(loading=true,error=null)
                try {
                    val hasSession = source.hasSession()
                    if (!hasSession) {
                        _state.value = AccountUiState(loading=false,busy=mutating)
                        return@collectLatest
                    }
                    _state.value = _state.value.copy(hasSession=true)
                    val identity = source.identity() // DEV 자동 로그인 없이 서버가 확인한 계정만 표시한다.
                    if(source.changes.value!=version) return@collectLatest
                    val result = files.withLock { withContext(Dispatchers.IO) {
                        val s = store ?: LocalProfileStore(application).also { store=it }
                        val p = try { s.load(identity.userId) } catch (_: Exception) { LocalProfile(identity.userId) }
                        p to s.avatar(p)
                    } }
                    if(source.changes.value==version) _state.value = AccountUiState(identity.userId,result.first,result.second,true,false,mutating)
                } catch (e: CancellationException) { throw e }
                catch (e: NetException.Unauthorized) {
                    _state.value = AccountUiState(loading=false,busy=mutating)
                }
                catch (_: Exception) {
                    _state.value = _state.value.copy(loading=false,busy=mutating,error="계정을 확인하지 못했습니다.")
                }
            }
        }
    }

    /** Only called after the server acknowledges deletion; account confirmation happens remotely. */
    suspend fun finishDeletion(expectedUserId: String) {
        check(_state.value.userId == expectedUserId)
        try {
            files.withLock { withContext(Dispatchers.IO) {
                (store ?: LocalProfileStore(getApplication())).deleteAccount(expectedUserId)
            } }
        } finally {
            source.signOut()
            refresh()
        }
    }

    fun refresh() { refreshes.update { it+1 } }

    fun signOut() = authAction { source.signOut() }
    fun developerSignIn() = authAction { source.developerSignIn() }
    private fun authAction(action: suspend () -> Unit) {
        if (mutating) return
        mutating=true
        _state.value=_state.value.copy(busy=true,error=null)
        viewModelScope.launch {
            try { action(); refresh() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.value=_state.value.copy(error="계정 작업을 완료하지 못했습니다.") }
            finally { mutating=false; _state.value=_state.value.copy(busy=false) }
        }
    }

    fun saveName(name: String, expectedUserId: String, onSaved: () -> Unit) {
        if (!LocalProfileStore.validName(name)) return
        updateProfile(expectedUserId,onSaved) { s,p -> p.copy(name=name.trim()).also(s::save) }
    }
    fun setAvatar(uri: Uri, expectedUserId: String) = updateProfile(expectedUserId) { s,p -> s.updateAvatar(p.userId,uri) }
    fun removeAvatar(expectedUserId: String) = updateProfile(expectedUserId) { s,p -> p.copy(avatarId=null).also(s::save) }

    private fun updateProfile(expectedUserId: String, onSaved: () -> Unit = {}, change: (LocalProfileStore,LocalProfile) -> LocalProfile) {
        val current=_state.value
        val profile=current.profile ?: return
        if (mutating || current.loading || !current.signedIn || current.userId!=expectedUserId) return
        val version=source.changes.value
        mutating=true
        _state.value=current.copy(busy=true,error=null)
        viewModelScope.launch {
            try {
                val result=files.withLock { withContext(Dispatchers.IO) {
                    val s=checkNotNull(store)
                    val p=change(s,profile)
                    p to s.avatar(p)
                } }
                if (source.changes.value==version && _state.value.userId==profile.userId) {
                    _state.value=_state.value.copy(profile=result.first,avatar=result.second)
                    onSaved()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.value=_state.value.copy(error="프로필을 저장하지 못했습니다. 사진은 JPG·PNG·WebP, 10MB 이하로 선택해 주세요.") }
            finally { mutating=false; _state.value=_state.value.copy(busy=false) }
        }
    }
}
