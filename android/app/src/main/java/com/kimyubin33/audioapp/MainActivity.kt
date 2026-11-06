package com.kimyubin33.audioapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.kimyubin33.audioapp.ui.PlayerScreen
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import uniffi.audio_core.ContentKind
import uniffi.audio_core.createContent

class MainActivity : ComponentActivity() {

    private val player: ExoPlayer by lazy {
        ExoPlayer.Builder(this)
            // 다른 앱이 소리를 내면 양보하고, 이어폰이 빠지면 자동으로 멈춘다
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(SAMPLE_AUDIO_URL))
                prepare()
            }
    }

    // 임시 샘플: 표시할 정보는 Rust의 createContent로 검증해서 만든다
    private val sample by lazy {
        createContent(
            title = "샘플 오디오",
            creatorId = "creator_01",
            kind = ContentKind.AUDIO,
            durationSeconds = 225u,
            aiGenerated = true,
            aiTool = null,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AudioAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PlayerScreen(
                        player = player,
                        title = sample.title,
                        creator = sample.creatorId,
                        aiGenerated = sample.aiGenerated,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 백그라운드 재생(서비스)은 아직 없으므로 화면이 가려지면 멈춘다
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }

    private companion object {
        // 개발용 임시 샘플 (Media3 데모에서 쓰는 공개 파일). 실제 서버가 생기면 교체한다.
        const val SAMPLE_AUDIO_URL = "https://storage.googleapis.com/exoplayer-test-media-0/play.mp3"
    }
}
