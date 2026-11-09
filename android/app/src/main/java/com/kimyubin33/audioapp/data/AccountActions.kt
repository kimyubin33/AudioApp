package com.kimyubin33.audioapp.data

import android.content.Context
import com.kimyubin33.audioapp.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.audio_core.ApiClient
import uniffi.audio_core.NetException
import uniffi.audio_core.ReportReason

/**
 * 로그인이 필요한 동작(신고·차단·구독). 토큰이 없거나 만료돼 `Unauthorized`가 나오면:
 * - debug 빌드: 목업 서버용 개발 로그인을 한 번 하고 다시 시도한다.
 * - release 빌드: 아직 실제(Google) 로그인 화면이 없으므로 그대로 오류를 알린다.
 */
object AccountActions {

    // 목업 서버(dev-server/mock_server.py)만 받아들이는 개발용 값이다. 실제 서버에서는 통하지 않는다.
    private const val DEV_ID_TOKEN = "dev-id-token"

    suspend fun report(context: Context, contentId: String, reason: ReportReason, detail: String?) =
        withLogin(context) { it.reportContent(contentId, reason, detail) }

    suspend fun block(context: Context, creatorId: String) =
        withLogin(context) { it.blockCreator(creatorId) }

    suspend fun unblock(context: Context, creatorId: String) =
        withLogin(context) { it.unblockCreator(creatorId) }

    /** 내가 차단한 크리에이터 id 목록 */
    suspend fun blockedCreators(context: Context): List<String> =
        withLogin(context) { it.listBlockedCreators() }

    suspend fun subscribe(context: Context, creatorId: String) =
        withLogin(context) { it.subscribeCreator(creatorId) }

    suspend fun unsubscribe(context: Context, creatorId: String) =
        withLogin(context) { it.unsubscribeCreator(creatorId) }

    /** 내가 구독한 크리에이터 id 목록. 로그인돼 있지 않으면 (로그인을 시도하지 않고) 빈 목록. */
    suspend fun subscribedCreatorsIfLoggedIn(context: Context): List<String> {
        val api = withContext(Dispatchers.IO) { AudioApi.get(context) }
        return try {
            api.listSubscribedCreators()
        } catch (e: NetException.Unauthorized) {
            emptyList()
        }
    }

    /** 로그인이 필요한 요청을 실행한다. 로그인이 안 돼 있으면 debug에서만 개발 로그인을 하고 다시 시도한다. */
    suspend fun <T> withLogin(context: Context, action: suspend (ApiClient) -> T): T {
        val api = withContext(Dispatchers.IO) { AudioApi.get(context) }
        return try {
            action(api)
        } catch (e: NetException.Unauthorized) {
            if (!BuildConfig.DEBUG) throw e
            api.signInWithGoogle(DEV_ID_TOKEN)
            action(api)
        }
    }
}
