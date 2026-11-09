package com.kimyubin33.audioapp.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.kimyubin33.audioapp.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Google 로그인 결과 */
enum class SignInResult { Success, Cancelled, NotConfigured, Failed }

/**
 * Google 로그인. 기기에서 받은 ID 토큰을 Rust 쪽 로그인 요청으로 넘기면, 서버가 검증한 뒤
 * 우리 서비스의 토큰을 발급하고 Rust가 그것을 Keystore에 저장한다.
 * (ID 토큰은 이 함수 안에서만 쓰고 저장·기록하지 않는다.)
 */
object GoogleSignIn {

    val isConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    /** @param activityContext 로그인 창을 띄울 화면(Activity)의 컨텍스트 */
    suspend fun signIn(activityContext: Context): SignInResult {
        if (!isConfigured) return SignInResult.NotConfigured
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false) // 처음 쓰는 계정도 고를 수 있게
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val result = CredentialManager.create(activityContext).getCredential(activityContext, request)
            val credential = result.credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return SignInResult.Failed
            }
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val api = withContext(Dispatchers.IO) { AudioApi.get(activityContext.applicationContext) }
            api.signInWithGoogle(idToken)
            SignInResult.Success
        } catch (e: GetCredentialCancellationException) {
            SignInResult.Cancelled
        } catch (e: GetCredentialException) {
            SignInResult.Failed
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SignInResult.Failed
        }
    }
}
