package com.kimyubin33.audioapp.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import uniffi.audio_core.ReportReason

/** 플레이어 화면에서 띄우는 창 */
private enum class ModerationDialog { Report, Block }

// 화면 주소. 직렬화가 가능해야 화면 회전·프로세스 종료 뒤에도 이동 상태가 복원된다.
// 플레이어 화면은 목록 없이도 그려질 수 있도록 표시에 필요한 값을 함께 가진다.
@Serializable
private data object HomeRoute : NavKey

@Serializable
private data object BlockedRoute : NavKey

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
    /** 신고한다. 실패하면 예외를 던진다. (콘텐츠 id, 사유, 설명) */
    onReport: suspend (String, ReportReason, String?) -> Unit,
    /** 크리에이터를 차단한다. 실패하면 예외를 던진다. */
    onBlock: suspend (String) -> Unit,
    /** 차단을 푼다. 실패하면 예외를 던진다. */
    onUnblock: suspend (String) -> Unit,
    /** 내가 차단한 크리에이터 목록을 가져온다. 실패하면 예외를 던진다. */
    onLoadBlocked: suspend () -> List<String>,
    /** 목록을 처음부터 다시 불러온다 (차단 결과를 반영) */
    onRefresh: () -> Unit,
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
                            onOpenBlocked = {
                                if (backStack.lastOrNull() == HomeRoute) backStack.add(BlockedRoute)
                            },
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

                is BlockedRoute -> NavEntry(key) {
                    val context = LocalContext.current
                    var creators by remember { mutableStateOf<List<String>?>(null) }
                    var error by remember { mutableStateOf<String?>(null) }
                    var reload by remember { mutableIntStateOf(0) }

                    LaunchedEffect(reload) {
                        error = null
                        try {
                            creators = onLoadBlocked()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = userMessage(e)
                        }
                    }

                    BlockedScreen(
                        creators = creators,
                        error = error,
                        onRetry = { reload++ },
                        onUnblock = { id ->
                            scope.launch {
                                try {
                                    onUnblock(id)
                                    creators = creators?.filter { it != id }
                                    onRefresh() // 풀린 크리에이터의 곡이 목록에 다시 나타나게 한다
                                    Toast.makeText(context, "차단을 해제했습니다.", Toast.LENGTH_SHORT).show()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Toast.makeText(context, userMessage(e), Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    )
                }

                is PlayerRoute -> NavEntry(key) {
                    val context = LocalContext.current
                    var dialog by remember { mutableStateOf<ModerationDialog?>(null) }
                    var submitting by remember { mutableStateOf(false) }

                    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

                    PlayerScreen(
                        player = player,
                        title = key.title,
                        creator = key.creator,
                        aiGenerated = key.aiGenerated,
                        onReport = { dialog = ModerationDialog.Report },
                        onBlock = { dialog = ModerationDialog.Block },
                    )

                    when (dialog) {
                        ModerationDialog.Report -> ReportDialog(
                            submitting = submitting,
                            onDismiss = { dialog = null },
                            onSubmit = { reason, detail ->
                                submitting = true
                                scope.launch {
                                    try {
                                        onReport(key.trackId, reason, detail)
                                        dialog = null
                                        toast("신고가 접수되었습니다.")
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        toast(userMessage(e)) // 창은 열어 둬서 다시 시도할 수 있게 한다
                                    } finally {
                                        submitting = false
                                    }
                                }
                            },
                        )

                        ModerationDialog.Block -> BlockDialog(
                            creator = key.creator,
                            submitting = submitting,
                            onDismiss = { dialog = null },
                            onConfirm = {
                                submitting = true
                                scope.launch {
                                    try {
                                        onBlock(key.creator)
                                        dialog = null
                                        // 차단한 크리에이터의 곡을 더 이어 재생하지 않고, 목록을 새로 불러온다
                                        player.stop()
                                        player.clearMediaItems()
                                        toast("차단했습니다.")
                                        backStack.removeLastOrNull()
                                        onRefresh()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        toast(userMessage(e))
                                    } finally {
                                        submitting = false
                                    }
                                }
                            },
                        )

                        null -> Unit
                    }
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
