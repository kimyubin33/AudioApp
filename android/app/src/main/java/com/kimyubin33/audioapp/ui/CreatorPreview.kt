@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kimyubin33.audioapp.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface

/** 초안의 로컬 원본만 재생한다. 화면을 떠나거나 앱이 백그라운드로 가면 중지한다. */
@Composable
fun CreatorPreview(uri: Uri, video: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(uri) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare() } }
    var playing by remember(uri) { mutableStateOf(false) }
    var error by remember(uri) { mutableStateOf(false) }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(e: PlaybackException) { error = true }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        player.addListener(listener)
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (video) PlayerSurface(player, modifier = Modifier.fillMaxWidth().height(220.dp).background(Color.Black))
        if (error) Text("이 기기에서 재생할 수 없습니다. 호환되는 MP3·M4A·MP4인지 확인해 주세요.", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                if (playing) player.pause() else player.play()
            }) { Text(if (playing) "일시 정지" else "원본 미리 듣기·보기") }
            TextButton(onClick = { player.seekTo(0); player.pause() }) { Text("처음으로") }
        }
    }
}
