package com.kimyubin33.audioapp

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import uniffi.audio_core.ContentException
import uniffi.audio_core.ContentKind
import uniffi.audio_core.createContent
import uniffi.audio_core.greet

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AudioAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "유빈",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = greet(name))
        Text(text = contentDemo(title = "첫 번째 곡"))
        Text(text = contentDemo(title = "   "))
    }
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