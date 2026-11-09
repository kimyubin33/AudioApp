package com.kimyubin33.audioapp.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.kimyubin33.audioapp.data.LibraryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import uniffi.audio_core.ContentDetail
import uniffi.audio_core.ContentKind

/** 신고·차단 창 */
private enum class ModerationDialog { Report, Block }

/** 신고·차단 대상 (콘텐츠 id와 그 크리에이터) */
private data class ModerationTarget(val contentId: String, val creator: String)

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
    val isVideo: Boolean,
) : NavKey

private fun MainTab.feedKind(): FeedKind? = when (this) {
    MainTab.Audio -> FeedKind.Audio
    MainTab.Shorts -> FeedKind.Shorts
    MainTab.Subscriptions -> FeedKind.Subscribed
    MainTab.Library -> null
}

@Composable
fun AppNavigation(
    player: Player,
    viewModel: HomeViewModel,
    onPlayRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(HomeRoute)
    val playback by rememberPlaybackSnapshot(player)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    // 곡을 여는 중에 또 누르는 것을 막고, 실패하면 홈 위쪽에 짧게 알려준다
    var opening by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // 신고·차단 창은 한 곳에서 띄운다 (시청 화면과 숏츠 화면이 함께 쓴다)
    var dialog by remember { mutableStateOf<ModerationDialog?>(null) }
    var target by remember { mutableStateOf<ModerationTarget?>(null) }
    var submitting by remember { mutableStateOf(false) }

    fun openContent(id: String) {
        if (opening || backStack.lastOrNull() != HomeRoute) return
        opening = true
        notice = null
        scope.launch {
            try {
                val detail = viewModel.loadDetail(id)
                onPlayRequested()
                playContent(player, detail)
                viewModel.recordPlay(detail)
                val content = detail.item.content
                backStack.add(
                    PlayerRoute(
                        trackId = detail.item.id,
                        title = content.title,
                        creator = content.creatorId,
                        aiGenerated = content.aiGenerated,
                        isVideo = content.kind == ContentKind.SHORT_VIDEO,
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

    fun toggleSubscribe(creator: String) {
        scope.launch {
            try {
                toast(if (viewModel.toggleSubscription(creator)) "구독했습니다." else "구독을 해제했습니다.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(userMessage(e))
            }
        }
    }

    fun toggleSave(entry: LibraryEntry) {
        toast(if (viewModel.toggleSaved(entry)) "보관함에 저장했습니다." else "저장을 취소했습니다.")
    }

    Box(modifier = modifier) {
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize(),
            // 플레이어 화면에서 뒤로 가도 재생은 이어지고, 홈의 미니 플레이어로 제어한다
            onBack = { backStack.removeLastOrNull() },
            entryProvider = { key ->
                when (key) {
                    is HomeRoute -> NavEntry(key) {
                        var tab by rememberSaveable { mutableStateOf(MainTab.Audio) }
                        val audio by viewModel.feed(FeedKind.Audio).collectAsState()
                        val shorts by viewModel.feed(FeedKind.Shorts).collectAsState()
                        val subs by viewModel.feed(FeedKind.Subscribed).collectAsState()
                        val history by viewModel.history.collectAsState()
                        val saved by viewModel.saved.collectAsState()
                        val subscribed by viewModel.subscribed.collectAsState()
                        val savedIds = remember(saved) { saved.map { it.id }.toSet() }

                        // 보이는 탭의 목록이 아직 없거나 다시 불러와야 하면 불러온다
                        val kind = tab.feedKind()
                        val stale = when (kind) {
                            FeedKind.Audio -> audio.stale
                            FeedKind.Shorts -> shorts.stale
                            FeedKind.Subscribed -> subs.stale
                            null -> false
                        }
                        LaunchedEffect(tab, stale) { kind?.let(viewModel::loadIfNeeded) }
                        LaunchedEffect(tab) {
                            if (tab == MainTab.Shorts || tab == MainTab.Subscriptions) viewModel.refreshSubscriptions()
                            // 숏츠는 짧아서 끝나면 처음부터 다시 돈다. 다른 탭에서는 평소대로.
                            player.repeatMode = if (tab == MainTab.Shorts) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                        }

                        Column {
                            Box(modifier = Modifier.weight(1f)) {
                                when (tab) {
                                    MainTab.Audio -> FeedScreen(
                                        title = "오디오",
                                        emptyMessage = "아직 올라온 오디오가 없습니다.",
                                        items = audio.items,
                                        loading = audio.loading,
                                        error = audio.error,
                                        hasMore = audio.nextCursor != null,
                                        notice = notice,
                                        onOpen = ::openContent,
                                        onRetry = { viewModel.retry(FeedKind.Audio) },
                                        onLoadMore = { viewModel.loadMore(FeedKind.Audio) },
                                    )

                                    MainTab.Shorts -> ShortsScreen(
                                        player = player,
                                        state = shorts,
                                        active = true,
                                        playback = playback,
                                        subscribed = subscribed,
                                        savedIds = savedIds,
                                        onPageSelected = { item ->
                                            try {
                                                val detail = viewModel.loadDetail(item.id)
                                                onPlayRequested()
                                                playContent(player, detail)
                                                viewModel.recordPlay(detail)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                toast(userMessage(e))
                                            }
                                        },
                                        onLoadMore = { viewModel.loadMore(FeedKind.Shorts) },
                                        onRetry = { viewModel.retry(FeedKind.Shorts) },
                                        onToggleSubscribe = ::toggleSubscribe,
                                        onToggleSave = { toggleSave(it.toLibraryEntry()) },
                                        onReport = {
                                            target = ModerationTarget(it.id, it.creator)
                                            dialog = ModerationDialog.Report
                                        },
                                        onBlock = {
                                            target = ModerationTarget(it.id, it.creator)
                                            dialog = ModerationDialog.Block
                                        },
                                    )

                                    MainTab.Subscriptions -> FeedScreen(
                                        title = "구독",
                                        emptyMessage = "구독한 채널의 새 콘텐츠가 여기에 모입니다.\n마음에 드는 채널을 구독해 보세요.",
                                        items = subs.items,
                                        loading = subs.loading,
                                        error = subs.error,
                                        hasMore = subs.nextCursor != null,
                                        notice = notice,
                                        onOpen = ::openContent,
                                        onRetry = { viewModel.retry(FeedKind.Subscribed) },
                                        onLoadMore = { viewModel.loadMore(FeedKind.Subscribed) },
                                    )

                                    MainTab.Library -> LibraryScreen(
                                        history = history,
                                        saved = saved,
                                        onOpen = ::openContent,
                                        onClearHistory = viewModel::clearHistory,
                                        onOpenBlocked = {
                                            if (backStack.lastOrNull() == HomeRoute) backStack.add(BlockedRoute)
                                        },
                                    )
                                }
                            }
                            // 숏츠 탭에서는 영상이 화면을 채우므로 미니 플레이어를 숨긴다
                            val currentId = playback.mediaId
                            if (currentId != null && tab != MainTab.Shorts) {
                                MiniPlayer(
                                    artSeed = currentId,
                                    isVideo = playback.isVideo,
                                    title = playback.title,
                                    subtitle = metaLine(playback.creatorId, playback.isVideo, playback.aiGenerated),
                                    isPlaying = playback.isPlaying,
                                    onTogglePlay = { if (playback.isPlaying) player.pause() else player.play() },
                                    onOpen = {
                                        // 이미 로드된 곡이므로 다시 불러오지 않고 화면만 연다
                                        if (backStack.lastOrNull() == HomeRoute) {
                                            backStack.add(
                                                PlayerRoute(
                                                    currentId,
                                                    playback.title,
                                                    playback.creatorId,
                                                    playback.aiGenerated,
                                                    playback.isVideo,
                                                )
                                            )
                                        }
                                    },
                                )
                            }
                            BottomNav(
                                selected = tab,
                                onSelect = { next ->
                                    // 숏츠를 떠나면 소리가 계속 나지 않도록 멈춘다
                                    if (tab == MainTab.Shorts && next != MainTab.Shorts) player.pause()
                                    tab = next
                                },
                            )
                        }
                    }

                    is BlockedRoute -> NavEntry(key) {
                        var creators by remember { mutableStateOf<List<String>?>(null) }
                        var error by remember { mutableStateOf<String?>(null) }
                        var reload by remember { mutableIntStateOf(0) }

                        LaunchedEffect(reload) {
                            error = null
                            try {
                                creators = viewModel.blockedCreators()
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
                                        viewModel.unblock(id)
                                        creators = creators?.filter { it != id }
                                        viewModel.markAllStale() // 풀린 크리에이터의 콘텐츠가 목록에 다시 나타나게 한다
                                        toast("차단을 해제했습니다.")
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        toast(userMessage(e))
                                    }
                                }
                            },
                        )
                    }

                    is PlayerRoute -> NavEntry(key) {
                        val subscribed by viewModel.subscribed.collectAsState()
                        val saved by viewModel.saved.collectAsState()
                        PlayerScreen(
                            player = player,
                            artSeed = key.trackId,
                            title = key.title,
                            creator = key.creator,
                            aiGenerated = key.aiGenerated,
                            isVideo = key.isVideo,
                            subscribed = key.creator in subscribed,
                            saved = saved.any { it.id == key.trackId },
                            onToggleSubscribe = { toggleSubscribe(key.creator) },
                            onToggleSave = {
                                toggleSave(
                                    LibraryEntry(
                                        id = key.trackId,
                                        title = key.title,
                                        creator = key.creator,
                                        aiGenerated = key.aiGenerated,
                                        isVideo = key.isVideo,
                                        atMillis = System.currentTimeMillis(),
                                    )
                                )
                            },
                            onReport = {
                                target = ModerationTarget(key.trackId, key.creator)
                                dialog = ModerationDialog.Report
                            },
                            onBlock = {
                                target = ModerationTarget(key.trackId, key.creator)
                                dialog = ModerationDialog.Block
                            },
                        )
                    }

                    else -> error("알 수 없는 화면: $key")
                }
            },
        )

        val current = target
        if (current != null) {
            when (dialog) {
                ModerationDialog.Report -> ReportDialog(
                    submitting = submitting,
                    onDismiss = { dialog = null },
                    onSubmit = { reason, detail ->
                        submitting = true
                        scope.launch {
                            try {
                                viewModel.report(current.contentId, reason, detail)
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
                    creator = current.creator,
                    submitting = submitting,
                    onDismiss = { dialog = null },
                    onConfirm = {
                        submitting = true
                        scope.launch {
                            try {
                                viewModel.block(current.creator)
                                dialog = null
                                // 차단한 크리에이터의 콘텐츠를 더 이어 재생하지 않고, 목록을 새로 불러온다
                                player.stop()
                                player.clearMediaItems()
                                toast("차단했습니다.")
                                if (backStack.lastOrNull() is PlayerRoute) backStack.removeLastOrNull()
                                viewModel.markAllStale()
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
    }
}

/** 곡을 불러와 재생한다. 이미 그 곡이 로드돼 있으면 이어서 재생한다. */
private fun playContent(player: Player, detail: ContentDetail) {
    if (player.currentMediaItem?.mediaId != detail.item.id) {
        player.setMediaItem(detail.toMediaItem())
        player.prepare()
    }
    player.play()
}
