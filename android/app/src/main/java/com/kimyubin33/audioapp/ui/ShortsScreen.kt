package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import com.kimyubin33.audioapp.ui.theme.Paper

/**
 * 숏츠 탭. 세로로 넘기는 전체 화면 영상. 지금 보는 한 편만 재생하고, 넘기면 다음 영상으로 바뀐다.
 * 영상 위에는 흰 글씨로 제목·크리에이터를 올리고, 오른쪽 아래에 저장·신고·차단을 둔다.
 */
@Composable
fun ShortsScreen(
    player: Player,
    state: HomeUiState,
    /** 이 탭이 지금 화면에 보이는가. 보일 때만 재생한다 */
    active: Boolean,
    playback: PlaybackSnapshot,
    subscribed: Set<String>,
    savedIds: Set<String>,
    /** 한 편이 화면에 자리 잡으면(넘김이 끝나면) 호출된다. 재생 주소를 받아 재생한다. 다른 영상으로 넘기면 취소된다 */
    onPageSelected: suspend (HomeItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onToggleSubscribe: (String) -> Unit,
    onToggleSave: (HomeItem) -> Unit,
    onReport: (HomeItem) -> Unit,
    onBlock: (HomeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.items.isEmpty()) {
        Column(modifier = modifier.fillMaxSize().padding(20.dp)) {
            Text("숏츠", style = MaterialTheme.typography.displaySmall)
            InkDivider(Modifier.padding(vertical = 8.dp))
            val message = state.error ?: if (state.loading) "불러오는 중..." else "아직 올라온 숏츠가 없습니다."
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.error != null) TextButton(onClick = onRetry) { Text("다시 시도", color = MaterialTheme.colorScheme.onSurface) }
        }
        return
    }

    val items = state.items
    val pager = rememberPagerState { items.size }

    // 자리 잡은 영상을 재생한다. 영상이 바뀌거나 목록을 다시 불러오면 새로 시작하고, 이전 요청은 취소된다.
    val settledId = items.getOrNull(pager.settledPage)?.id
    LaunchedEffect(settledId, active) {
        if (active) items.getOrNull(pager.settledPage)?.let { onPageSelected(it) }
    }
    // 끝이 가까워지면 다음 페이지를 미리 불러온다
    LaunchedEffect(pager.currentPage, items.size) {
        if (pager.currentPage >= items.size - 3) onLoadMore()
    }

    VerticalPager(
        state = pager,
        modifier = modifier.fillMaxSize().background(Color.Black),
        beyondViewportPageCount = 0,
        key = { items[it].id },
    ) { index ->
        val item = items[index]
        ShortPage(
            item = item,
            isCurrent = active && index == pager.settledPage,
            player = player,
            playback = playback,
            subscribed = item.creator in subscribed,
            saved = item.id in savedIds,
            onToggleSubscribe = { onToggleSubscribe(item.creator) },
            onToggleSave = { onToggleSave(item) },
            onReport = { onReport(item) },
            onBlock = { onBlock(item) },
        )
    }
}

@Composable
private fun ShortPage(
    item: HomeItem,
    isCurrent: Boolean,
    player: Player,
    playback: PlaybackSnapshot,
    subscribed: Boolean,
    saved: Boolean,
    onToggleSubscribe: () -> Unit,
    onToggleSave: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (isCurrent) {
            FullVideoSurface(player)
        } else {
            // 아직 재생 전인 영상은 먹 그림 썸네일을 어둡게 깔아 둔다 (흰 글씨가 읽히도록)
            InkArt(seed = item.id, isVideo = true, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }

        // 화면을 한 번 누르면 재생/일시정지
        if (isCurrent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (player.isPlaying) player.pause() else player.play()
                    },
            )
        }

        // 글씨가 영상 위에서도 읽히도록 아래쪽을 어둡게
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(280.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))),
        )

        // 제목·크리에이터·구독
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 88.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (item.aiGenerated) {
                Text(
                    text = "AI 생성",
                    style = MaterialTheme.typography.labelMedium,
                    color = Paper,
                    modifier = Modifier.border(1.dp, Paper, RoundedCornerShape(2.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleLarge,
                color = Paper,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                CreatorMark(item.creator, size = 32.dp)
                Spacer(Modifier.width(10.dp))
                Text(text = item.creator, style = MaterialTheme.typography.bodyMedium, color = Paper, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(12.dp))
                SubscribeButton(subscribed = subscribed, onClick = onToggleSubscribe, onDark = true)
            }
        }

        // 오른쪽 아래: 저장·신고·차단
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.End,
        ) {
            TextButton(onClick = onToggleSave) { Text(if (saved) "저장됨" else "저장", color = Paper) }
            TextButton(onClick = onReport) { Text("신고", color = Paper.copy(alpha = 0.8f)) }
            TextButton(onClick = onBlock) { Text("차단", color = Paper.copy(alpha = 0.8f)) }
        }

        // 재생에 실패했거나 멈춰 있으면 가운데에 알린다
        if (isCurrent && playback.hasError) {
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("재생할 수 없습니다.", style = MaterialTheme.typography.bodyMedium, color = Paper)
                TextButton(onClick = { player.prepare(); player.play() }) { Text("다시 시도", color = Paper) }
            }
        }
    }
}

/** 화면 전체에 영상을 비율대로 맞춰 채운다. (남는 부분은 검게) */
@Composable
internal fun FullVideoSurface(player: Player, modifier: Modifier = Modifier) {
    val presentationState = rememberPresentationState(player)
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        PlayerSurface(
            player = player,
            modifier = Modifier
                .align(Alignment.Center)
                .resizeWithContentScale(ContentScale.Fit, presentationState.videoSizeDp),
        )
        // 첫 화면이 준비되기 전에는 검게 가린다 (깜빡임 방지)
        if (presentationState.coverSurface) Box(Modifier.matchParentSize().background(Color.Black))
    }
}
