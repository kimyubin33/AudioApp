package com.kimyubin33.audioapp

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.audio_core.ApiClient
import uniffi.audio_core.ContentException
import uniffi.audio_core.ContentKind
import uniffi.audio_core.createContent
import uniffi.audio_core.NetException
import uniffi.audio_core.greet

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AudioAppTheme {
                // Keystore 접근은 메인 스레드 밖에서 한다
                val sessionStatus by produceState(initialValue = "세션 확인 중...") {
                    value = withContext(Dispatchers.IO) { sessionDemo(applicationContext) }
                }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "유빈",
                        sessionStatus = sessionStatus,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, sessionStatus: String = "", modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = greet(name))
        Text(text = sessionStatus)
        Text(text = contentDemo(title = "첫 번째 곡"))
        Text(text = contentDemo(title = "   "))
    }
}

/** 임시 확인용: Keystore 토큰 보관소 ↔ Rust ApiClient 연결을 시험한다. (가짜 토큰만 사용) */
private fun sessionDemo(context: Context): String =
    try {
        val store = KeystoreTokenStore(context)
        val client = ApiClient("https://api.example.com", store)
        store.clearToken()
        val before = client.hasSession()
        store.saveToken("demo-not-a-real-token")
        val saved = client.hasSession()
        client.signOut()
        val after = client.hasSession()
        val http = try {
            ApiClient("http://api.example.com", store)
            "허용됨(문제!)"
        } catch (e: NetException) {
            "거부(${e::class.simpleName})"
        }
        "세션: 처음=$before → 저장 후=$saved → 로그아웃 후=$after / http 주소: $http"
    } catch (e: Exception) {
        "세션 오류: ${e::class.simpleName}"
    }

/** 임시 확인용: Rust의 createContent를 부르고 성공/실패를 문자열로 돌려준다. */
private fun contentDemo(title: String): String =
    try {
        val c = createContent(
            title = title,
            creatorId = "creator_01",
            kind = ContentKind.AUDIO,
            durationSeconds = 180u,
            aiGenerated = true,
            aiTool = "Suno",
        )
        "성공: ${c.title} (AI 생성=${c.aiGenerated}, 도구=${c.aiTool})"
    } catch (e: ContentException) {
        "거부됨: ${e::class.simpleName}"
    }

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    AudioAppTheme {
        Greeting("Android")
    }
}