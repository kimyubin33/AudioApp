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
import androidx.media3.exoplayer.ExoPlayer
import com.kimyubin33.audioapp.ui.AppNavigation
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AudioAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavigation(
                        player = player,
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
}
