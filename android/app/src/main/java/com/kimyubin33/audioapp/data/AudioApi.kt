package com.kimyubin33.audioapp.data

import android.content.Context
import com.kimyubin33.audioapp.BuildConfig
import com.kimyubin33.audioapp.KeystoreTokenStore
import uniffi.audio_core.ApiClient

/** 서버와 통신하는 [ApiClient]를 앱 전체에서 하나만 만들어 쓴다. */
object AudioApi {

    @Volatile
    private var client: ApiClient? = null

    fun get(context: Context): ApiClient =
        client ?: synchronized(this) {
            client ?: create(context.applicationContext).also { client = it }
        }

    private fun create(context: Context): ApiClient =
        ApiClient(
            BuildConfig.API_BASE_URL,
            KeystoreTokenStore(context),
            devRootPem(context),
        )

    /**
     * 개발용 목업 서버의 루트 인증서. debug 빌드에만 존재하는 리소스(res/raw/dev_ca.pem)라서
     * release 앱에는 들어가지 않는다. (Rust도 사설망·로컬 주소가 아니면 이 값을 거부한다)
     */
    private fun devRootPem(context: Context): String? {
        if (!BuildConfig.DEBUG) return null
        val id = context.resources.getIdentifier("dev_ca", "raw", context.packageName)
        if (id == 0) return null
        return context.resources.openRawResource(id).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
