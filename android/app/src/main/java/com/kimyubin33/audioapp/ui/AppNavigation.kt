package com.kimyubin33.audioapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.kimyubin33.audioapp.data.SampleCatalog
import kotlinx.serialization.Serializable

// 화면 주소. 직렬화가 가능해야 화면 회전·프로세스 종료 뒤에도 이동 상태가 복원된다.
@Serializable
private data object HomeRoute : NavKey

@Serializable
private data class PlayerRoute(val trackId: String) : NavKey

@Composable
fun AppNavigation(player: Player, modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(HomeRoute)

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { backStack.removeLastOrNull() },
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
                            if (backStack.lastOrNull() == HomeRoute) backStack.add(PlayerRoute(id))
                        },
                    )
                }

                is PlayerRoute -> NavEntry(key) {
                    val track = SampleCatalog.find(key.trackId)
                    if (track == null) {
                        // 알 수 없는 곡(잘못된 주소)이면 조용히 뒤로 간다
                        LaunchedEffect(Unit) { backStack.removeLastOrNull() }
                    } else {
                        // 이 화면에 들어오면 곡을 불러와 재생하고, 나가면 멈춘다
                        DisposableEffect(track.id) {
                            if (player.currentMediaItem?.mediaId != track.id) {
                                player.setMediaItem(
                                    MediaItem.Builder()
                                        .setMediaId(track.id)
                                        .setUri(track.url)
                                        .build()
                                )
                                player.prepare()
                            }
                            player.play()
                            onDispose { player.pause() }
                        }
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
