package com.kimyubin33.audioapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.kimyubin33.audioapp.data.SampleCatalog
import com.kimyubin33.audioapp.data.Track
import kotlinx.serialization.Serializable

// 화면 주소. 직렬화가 가능해야 화면 회전·프로세스 종료 뒤에도 이동 상태가 복원된다.
@Serializable
private data object HomeRoute : NavKey

@Serializable
private data class PlayerRoute(val trackId: String) : NavKey

@Composable
fun AppNavigation(
    player: Player,
    onPlayRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(HomeRoute)

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = {
            // 미니 플레이어가 생기기 전까지는 플레이어 화면을 떠나면 멈춘다.
            // (화면 회전 때는 이 콜백이 불리지 않으므로 재생이 끊기지 않는다)
            if (backStack.lastOrNull() is PlayerRoute) player.pause()
            backStack.removeLastOrNull()
        },
        entryProvider = { key ->
            when (key) {
                is HomeRoute -> NavEntry(key) {
                    val items = SampleCatalog.tracks.map {
                        HomeItem(
                            id = it.id,
                            title = it.content.title,
                            creator = it.content.creatorId,
                            aiGenerated = it.content.aiGenerated,
                        )
                    }
                    HomeScreen(
                        items = items,
                        onOpen = { id ->
                            // 같은 곡을 연타해도 화면이 겹치지 않게 한다
                            val track = SampleCatalog.find(id)
                            if (track != null && backStack.lastOrNull() == HomeRoute) {
                                onPlayRequested()
                                playTrack(player, track)
                                backStack.add(PlayerRoute(id))
                            }
                        },
                    )
                }

                is PlayerRoute -> NavEntry(key) {
                    val track = SampleCatalog.find(key.trackId)
                    if (track == null) {
                        // 알 수 없는 곡(잘못된 주소)이면 조용히 뒤로 간다
                        LaunchedEffect(Unit) { backStack.removeLastOrNull() }
                    } else {
                        PlayerScreen(
                            player = player,
                            title = track.content.title,
                            creator = track.content.creatorId,
                            aiGenerated = track.content.aiGenerated,
                        )
                    }
                }

                else -> error("알 수 없는 화면: $key")
            }
        },
    )
}

/** 곡을 불러와 재생한다. 이미 그 곡이 로드돼 있으면 이어서 재생한다. */
private fun playTrack(player: Player, track: Track) {
    if (player.currentMediaItem?.mediaId != track.id) {
        player.setMediaItem(
            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(track.url)
                // 알림창·잠금화면에 보이는 정보 (AI 생성 표시 포함)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.content.title)
                        .setArtist("${track.content.creatorId} · AI 생성")
                        .build()
                )
                .build()
        )
        player.prepare()
    }
    player.play()
}
