package com.kimyubin33.audioapp.ui

import android.widget.Toast
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kimyubin33.audioapp.BuildConfig
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
import com.kimyubin33.audioapp.data.GoogleSignIn
import com.kimyubin33.audioapp.data.LibraryEntry
import com.kimyubin33.audioapp.data.SignInResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import uniffi.audio_core.NetException
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
private data object AccountRoute : NavKey

@Serializable
private data class ProfileRoute(val userId: String) : NavKey

@Serializable
private data object MomentsRoute : NavKey

@Serializable
private data object CreatorRoute : NavKey

@Serializable
private data object NearbyRoute : NavKey

@Serializable
private data object ShortsRoute : NavKey

@Serializable
private data object MemoryEditorRoute : NavKey

@Serializable
private data class MemoryDetailRoute(val id: String) : NavKey

@Serializable
private data class PlayerRoute(
    val trackId: String,
    val title: String,
    val creator: String,
    val aiGenerated: Boolean,
    val isVideo: Boolean,
) : NavKey

private fun MainTab.feedKind(): FeedKind? = when (this) {
    MainTab.Feed -> FeedKind.Audio
    MainTab.Subscriptions -> FeedKind.Subscribed
    MainTab.Places, MainTab.Create, MainTab.Library -> null
}

@Composable
fun AppNavigation(
    player: Player,
    viewModel: HomeViewModel,
    creatorViewModel: CreatorViewModel,
    nearbyViewModel: NearbyViewModel,
    memoryViewModel: MemoryViewModel,
    accountViewModel: AccountViewModel,
    onPlayRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(HomeRoute)
    val playback by rememberPlaybackSnapshot(player)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,accountViewModel) {
        val observer=LifecycleEventObserver { _,event->if(event==Lifecycle.Event.ON_RESUME) accountViewModel.refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // 곡을 여는 중에 또 누르는 것을 막고, 실패하면 홈 위쪽에 짧게 알려준다
    var opening by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // 신고·차단 창은 한 곳에서 띄운다 (시청 화면과 숏츠 화면이 함께 쓴다)
    var dialog by remember { mutableStateOf<ModerationDialog?>(null) }
    var target by remember { mutableStateOf<ModerationTarget?>(null) }
    var submitting by remember { mutableStateOf(false) }

    fun openContent(id: String) {
        val origin = backStack.lastOrNull()
        if (opening || (origin != HomeRoute && origin != NearbyRoute)) return
        opening = true
        notice = null
        scope.launch {
            try {
                val detail = viewModel.loadDetail(id)
                if (backStack.lastOrNull() != origin) return@launch
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

    // 로그인이 필요한 동작에서 로그인이 안 돼 있으면 로그인 안내 창을 띄운다
    var loginPrompt by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }

    fun failed(e: Exception) {
        if (e is NetException.Unauthorized) loginPrompt = true else toast(userMessage(e))
    }

    fun signIn() {
        if (signingIn) return
        signingIn = true
        scope.launch {
            try {
                when (GoogleSignIn.signIn(context)) {
                    SignInResult.Success -> {
                        loginPrompt = false
                        toast("로그인했습니다.")
                        viewModel.markAllStale()
                        viewModel.refreshSubscriptions()
                        if (backStack.lastOrNull() == CreatorRoute) creatorViewModel.refresh()
                    }
                    SignInResult.Cancelled -> Unit
                    SignInResult.NotConfigured -> toast("로그인 설정이 아직 되어 있지 않습니다.")
                    SignInResult.Failed -> toast("로그인하지 못했습니다. 다시 시도해 주세요.")
                }
            } finally {
                signingIn = false
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
                failed(e)
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
                        var tab by rememberSaveable { mutableStateOf(MainTab.Feed) }
                        val feed by viewModel.feed(FeedKind.Audio).collectAsState()
                        val searchQuery by viewModel.query.collectAsState()
                        val format by viewModel.format.collectAsState()
                        val subs by viewModel.feed(FeedKind.Subscribed).collectAsState()
                        val history by viewModel.history.collectAsState()
                        val saved by viewModel.saved.collectAsState()
                        val account by accountViewModel.state.collectAsState()
                        val kind=tab.feedKind()
                        val stale=when(kind) { FeedKind.Audio->feed.stale; FeedKind.Subscribed->subs.stale; else->false }
                        LaunchedEffect(tab,stale) { kind?.let(viewModel::loadIfNeeded) }
                        LaunchedEffect(tab) {
                            if(tab==MainTab.Subscriptions) viewModel.refreshSubscriptions()
                            if(tab==MainTab.Library) accountViewModel.refresh()
                            player.repeatMode=Player.REPEAT_MODE_OFF
                        }
                        fun createMemory() {
                            if(backStack.lastOrNull()==HomeRoute) backStack.add(MemoryEditorRoute)
                        }
                        fun upload() {
                            if(backStack.lastOrNull()==HomeRoute) { player.pause(); backStack.add(CreatorRoute) }
                        }
                        Column {
                            Box(Modifier.weight(1f)) {
                                when(tab) {
                                    MainTab.Feed -> FeedScreen(
                                        title="피드",emptyMessage="게시물이 없습니다.",
                                        items=feed.items,loading=feed.loading,error=feed.error,hasMore=feed.nextCursor!=null,notice=notice,
                                        onOpen=::openContent,onRetry={viewModel.retry(FeedKind.Audio)},onLoadMore={viewModel.loadMore(FeedKind.Audio)},
                                        onCreateMemory=::createMemory,
                                        searchQuery=searchQuery,onSearch=viewModel::search,format=format,onFormat=viewModel::selectFormat,
                                        onOpenShorts={if(backStack.lastOrNull()==HomeRoute) { player.pause(); backStack.add(ShortsRoute) }},
                                    )
                                    MainTab.Places -> NearbyScreen(nearbyViewModel,
                                        onBack={tab=MainTab.Feed},onOpen=::openContent,notice=notice,embedded=true,
                                        onSearchPlace={query->viewModel.search(query);tab=MainTab.Feed})
                                    MainTab.Create -> {
                                        // 이전 버전에서 저장된 창작 탭도 새 업로드 입구로 복원한다.
                                        LaunchedEffect(Unit) { tab=MainTab.Feed; upload() }
                                    }
                                    MainTab.Subscriptions -> FeedScreen(
                                        title="구독",emptyMessage="구독한 게시물이 없습니다.",
                                        items=subs.items,loading=subs.loading,error=subs.error,hasMore=subs.nextCursor!=null,notice=notice,
                                        onOpen=::openContent,
                                        onRetry={if(subs.error==userMessage(NetException.Unauthorized())) loginPrompt=true else viewModel.retry(FeedKind.Subscribed)},
                                        onLoadMore={viewModel.loadMore(FeedKind.Subscribed)},
                                    )
                                    MainTab.Library -> LibraryScreen(
                                        history=history,saved=saved,onOpen=::openContent,account=account,
                                        onAccount={if(backStack.lastOrNull()==HomeRoute) backStack.add(AccountRoute)},
                                    )
                                }
                            }
                            val currentId=playback.mediaId
                            if(currentId!=null) MiniPlayer(
                                artSeed=currentId,isVideo=playback.isVideo,title=playback.title,
                                subtitle=metaLine(playback.creatorId,playback.isVideo,playback.aiGenerated),
                                isPlaying=playback.isPlaying,onTogglePlay={if(playback.isPlaying) player.pause() else player.play()},
                                onOpen={if(backStack.lastOrNull()==HomeRoute) backStack.add(PlayerRoute(currentId,playback.title,
                                    playback.creatorId,playback.aiGenerated,playback.isVideo))},
                            )
                            BottomNav(selected=tab,onSelect={ next ->
                                notice=null
                                if(next==MainTab.Create) upload() else tab=next
                            })
                        }
                    }

                    is AccountRoute -> NavEntry(key) {
                        val account by accountViewModel.state.collectAsState()
                        AccountScreen(account.copy(busy=account.busy||signingIn),
                            onBack={backStack.removeLastOrNull()},onLogin=::signIn,onDeveloperLogin=accountViewModel::developerSignIn,
                            onProfile={account.userId?.let { if(backStack.lastOrNull()==AccountRoute) backStack.add(ProfileRoute(it)) }},
                            onMoments={if(backStack.lastOrNull()==AccountRoute) backStack.add(MomentsRoute)},
                            onBlocked={if(account.signedIn && backStack.lastOrNull()==AccountRoute) backStack.add(BlockedRoute)},
                            onClearHistory=viewModel::clearHistory,
                            onPermissions={
                                try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}"))) }
                                catch (_: android.content.ActivityNotFoundException) { toast("기기 설정을 열 수 없습니다.") }
                            },
                            onSignOut={player.pause();player.clearMediaItems();accountViewModel.signOut()},
                            onRetry=accountViewModel::refresh,developerLogin=BuildConfig.DEBUG&&BuildConfig.DEV_LOGIN)
                    }

                    is ProfileRoute -> NavEntry(key) {
                        val account by accountViewModel.state.collectAsState()
                        if(account.userId==key.userId) ProfileEditorScreen(accountViewModel,key.userId,onBack={backStack.removeLastOrNull()})
                        else LaunchedEffect(account.loading) {
                            if(!account.loading && backStack.lastOrNull()==key) backStack.removeLastOrNull()
                        }
                    }

                    is MomentsRoute -> NavEntry(key) {
                        val album by memoryViewModel.state.collectAsState()
                        MomentsScreen(album,memoryViewModel::photo,onBack={backStack.removeLastOrNull()},
                            onCreate={if(backStack.lastOrNull()==MomentsRoute) backStack.add(MemoryEditorRoute)},
                            onOpen={id->if(backStack.lastOrNull()==MomentsRoute) backStack.add(MemoryDetailRoute(id))},
                            onRetry=memoryViewModel::reload)
                    }

                    is ShortsRoute -> NavEntry(key) {
                        val shorts by viewModel.feed(FeedKind.Shorts).collectAsState()
                        val subscribed by viewModel.subscribed.collectAsState()
                        val saved by viewModel.saved.collectAsState()
                        val savedIds=remember(saved) { saved.map { it.id }.toSet() }
                        LaunchedEffect(shorts.stale) {
                            viewModel.loadIfNeeded(FeedKind.Shorts)
                            viewModel.refreshSubscriptions()
                        }
                        androidx.compose.runtime.DisposableEffect(player) {
                            player.repeatMode=Player.REPEAT_MODE_ONE
                            onDispose { player.pause(); player.repeatMode=Player.REPEAT_MODE_OFF }
                        }
                        Column {
                            TextButton(onClick={backStack.removeLastOrNull()}){Text("피드로 돌아가기")}
                            Box(Modifier.weight(1f)) {
                                ShortsScreen(
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
                            }
                        }
                    }

                    is MemoryEditorRoute -> NavEntry(key) {
                        MemoryEditorScreen(memoryViewModel,onBack={backStack.removeLastOrNull()},onSave={
                            scope.launch {
                                val id=memoryViewModel.saveMemory()
                                if(id!=null && backStack.lastOrNull()==MemoryEditorRoute) {
                                    backStack.removeLastOrNull()
                                    backStack.add(MemoryDetailRoute(id))
                                }
                            }
                        })
                    }

                    is MemoryDetailRoute -> NavEntry(key) {
                        val state by memoryViewModel.state.collectAsState()
                        val memory=state.album.memories.find { it.id==key.id }
                        MemoryDetailScreen(memory,memory?.photoId?.let(memoryViewModel::photo),state.busy,state.error,
                            onBack={backStack.removeLastOrNull()},
                            onDelete={scope.launch { if(memoryViewModel.deleteMemory(key.id) && backStack.lastOrNull()==key) backStack.removeLastOrNull() }})
                    }

                    is NearbyRoute -> NavEntry(key) {
                        NearbyScreen(nearbyViewModel, onBack = { backStack.removeLastOrNull() },
                            onOpen = ::openContent, notice = notice)
                    }

                    is CreatorRoute -> NavEntry(key) {
                        StreamerScreen(creatorViewModel,
                            onBack = { backStack.removeLastOrNull() },
                            onLogin = ::signIn)
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

        if (loginPrompt) {
            AlertDialog(
                onDismissRequest = { loginPrompt = false },
                title = { Text("로그인이 필요합니다") },
                text = { Text("구독, 신고, 차단은 Google 계정으로 로그인한 뒤 쓸 수 있습니다. 보는 것과 듣는 것은 로그인 없이 됩니다.") },
                confirmButton = { TextButton(onClick = ::signIn, enabled = !signingIn) { Text("Google로 로그인") } },
                dismissButton = { TextButton(onClick = { loginPrompt = false }) { Text("나중에") } },
            )
        }

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
                                if (e is NetException.Unauthorized) dialog = null
                                failed(e) // 그 밖의 오류는 창을 열어 둬서 다시 시도할 수 있게 한다
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
                                if (e is NetException.Unauthorized) dialog = null
                                failed(e)
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
