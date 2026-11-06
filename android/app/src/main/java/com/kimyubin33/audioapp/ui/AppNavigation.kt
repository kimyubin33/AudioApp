package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import uniffi.audio_core.ContentDetail

// 화면 주소. 직렬화가 가능해야 화면 회전·프로세스 종료 뒤에도 이동 상태가 복원된다.
// 플레이어 화면은 목록 없이도 그려질 수 있도록 표시에 필요한 값을 함께 가진다.
@Serializable
private data object HomeRoute : NavKey

@Serializable
private data class PlayerRoute(
    val trackId: String,
    val title: String,
    val creator: String,
    val aiGenerated: Boolean,
) : NavKey

@Composable
fun AppNavigation(
    player: Player,
    // 값이 아니라 흐름으로 받는다: 홈 화면 내용 안에서 읽어야 상태가 바뀔 때 화면이 갱신된다
    homeState: StateFlow<HomeUiState>,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    /** 곡 상세(재생 주소 포함)를 서버에서 가져온다. 실패하면 예외를 던진다. */
    loadDetail: suspend (String) -> ContentDetail,
    onPlayRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(HomeRoute)
    val playback by rememberPlaybackSnapshot(player)
    val scope = rememberCoroutineScope()

    // 곡을 여는 중에 또 누르는 것을 막고, 실패하면 홈 위쪽에 짧게 알려준다
    var opening by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun openContent(id: String) {
        if (opening || backStack.lastOrNull() != HomeRoute) return
        opening = true
        notice = null
        scope.launch {
            try {
                val detail = loadDetail(id)
                onPlayRequested()
                playContent(player, detail)
                val content = detail.item.content
                backStack.add(
                    PlayerRoute(
                        trackId = detail.item.id,
                        title = content.title,
                        creator = content.creatorId,
                        aiGenerated = content.aiGenerated,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = userMessage(e)
            } finally {
                opening = false
            }
        }
    }

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        // 플레이어 화면에서 뒤로 가도 재생은 이어지고, 홈의 미니 플레이어로 제어한다
        onBack = { backStack.removeLastOrNull() },
        entryProvider = { key ->
            when (key) {
                is HomeRoute -> NavEntry(key) {
                    val home by homeState.collectAsState()
                    Column {
                        HomeScreen(
                            items = home.items,
                            loading = home.loading,
                            error = home.error,
                            hasMore = home.nextCursor != null,
                            notice = notice,
                            onOpen = ::openContent,
                            onRetry = onRetry,
                            onLoadMore = onLoadMore,
                            modifier = Modifier.weight(1f),
                        )
                        val currentId = playback.mediaId
                        if (currentId != null) {
                            MiniPlayer(
                                title = playback.title,
                                subtitle = if (playback.aiGenerated) "${playback.creatorId} · AI 생성" else playback.creatorId,
                                isPlaying = playback.isPlaying,
                                onTogglePlay = { if (playback.isPlaying) player.pause() else player.play() },
                                onOpen = {
                                    // 이미 로드된 곡이므로 다시 불러오지 않고 화면만 연다
                                    if (backStack.lastOrNull() == HomeRoute) {
                                        backStack.add(
                                            PlayerRoute(currentId, playback.title, playback.creatorId, playback.aiGenerated)
                                        )
                                    }
                                },
                            )
                        }
                    }
                }

                is PlayerRoute -> NavEntry(key) {
                    PlayerScreen(
                        player = player,
                        title = key.title,
                        creator = key.creator,
                        aiGenerated = key.aiGenerated,
                    )
                }

                else -> error("알 수 없는 화면: $key")
            }
        },
    )
}

/** 곡을 불러와 재생한다. 이미 그 곡이 로드돼 있으면 이어서 재생한다. */
private fun playContent(player: Player, detail: ContentDetail) {
    if (player.currentMediaItem?.mediaId != detail.item.id) {
        player.setMediaItem(detail.toMediaItem())
        player.prepare()
    }
    player.play()
}
