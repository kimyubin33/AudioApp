package com.kimyubin33.audioapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.media3.common.Player

/** 지금 어떤 곡이 로드돼 있고 재생 중인지. 홈의 미니 플레이어가 이걸 보고 그려진다. */
data class PlaybackSnapshot(
    /** 로드된 곡이 없으면 null */
    val mediaId: String?,
    val title: String,
    val creatorId: String,
    val aiGenerated: Boolean,
    val isPlaying: Boolean,
)

@Composable
fun rememberPlaybackSnapshot(player: Player): State<PlaybackSnapshot> {
    val state = remember(player) { mutableStateOf(player.snapshot()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                state.value = player.snapshot()
            }
        }
        player.addListener(listener)
        state.value = player.snapshot()
        onDispose { player.removeListener(listener) }
    }
    return state
}

private fun Player.snapshot(): PlaybackSnapshot {
    val item = currentMediaItem
    val metadata = item?.mediaMetadata
    return PlaybackSnapshot(
        mediaId = item?.mediaId?.takeIf { it.isNotEmpty() },
        title = metadata?.title?.toString().orEmpty(),
        creatorId = metadata?.extras?.getString(EXTRA_CREATOR_ID).orEmpty(),
        aiGenerated = metadata?.extras?.getBoolean(EXTRA_AI_GENERATED, false) ?: false,
        // 버퍼링 중에도 "재생 중"으로 보여서 버튼이 깜빡이지 않게 한다
        isPlaying = playWhenReady &&
            playbackState != Player.STATE_IDLE &&
            playbackState != Player.STATE_ENDED,
    )
}
