@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import com.kimyubin33.audioapp.ui.theme.Ash
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import com.kimyubin33.audioapp.ui.theme.Ink
import com.kimyubin33.audioapp.ui.theme.Mist
import kotlinx.coroutines.delay

private const val SEEK_STEP_MS = 10_000L

/** 플레이어와 연결된 화면. 재생 상태를 읽어서 [PlayerContent]에 넘긴다. */
@Composable
fun PlayerScreen(
    player: Player,
    /** 먹 그림 썸네일을 정하는 값 (곡 id) */
    artSeed: String,
    title: String,
    creator: String,
    aiGenerated: Boolean,
    isVideo: Boolean,
    subscribed: Boolean,
    saved: Boolean,
    onToggleSubscribe: () -> Unit,
    onToggleSave: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var hasError by remember { mutableStateOf(player.playerError != null) }
    var positionMs by remember { mutableLongStateOf(player.currentPosition) }
    var durationMs by remember { mutableLongStateOf(player.duration.coerceAtLeast(0L)) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            // 재생 실패(네트워크 끊김, 깨진 파일, 기기가 지원하지 않는 형식 등)를 알려 준다
            override fun onPlayerErrorChanged(error: PlaybackException?) {
                hasError = error != null
            }

            override fun onEvents(player: Player, events: Player.Events) {
                // 길이를 모를 때는 음수(TIME_UNSET)가 오므로 0으로 맞춘다
                durationMs = player.duration.coerceAtLeast(0L)
                positionMs = player.currentPosition
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 재생 중에만 위치를 주기적으로 갱신
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = player.currentPosition
            delay(250)
        }
    }

    PlayerContent(
        artSeed = artSeed,
        title = title,
        creator = creator,
        aiGenerated = aiGenerated,
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = durationMs,
        onTogglePlay = { if (player.isPlaying) player.pause() else player.play() },
        onSeekTo = { ms ->
            positionMs = ms
            player.seekTo(ms)
        },
        onSeekBy = { delta ->
            val target = (player.currentPosition + delta).coerceIn(0L, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
            positionMs = target
            player.seekTo(target)
        },
        onReport = onReport,
        onBlock = onBlock,
        subscribed = subscribed,
        saved = saved,
        onToggleSubscribe = onToggleSubscribe,
        onToggleSave = onToggleSave,
        hasError = hasError,
        onRetry = {
            player.prepare() // 오류 상태를 풀고 처음부터 다시 불러온다
            player.play()
        },
        video = if (isVideo) ({ VideoSurface(player) }) else null,
        modifier = modifier,
    )
}

/** 영상 화면. 영상 비율을 지켜서 16:9 검은 영역 안에 맞춘다. */
@Composable
private fun VideoSurface(player: Player, modifier: Modifier = Modifier) {
    val presentationState = rememberPresentationState(player)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black),
    ) {
        PlayerSurface(
            player = player,
            modifier = Modifier
                .align(Alignment.Center)
                .resizeWithContentScale(ContentScale.Fit, presentationState.videoSizeDp),
        )
        // 첫 화면이 준비되기 전에는 검게 가린다 (깜빡임 방지)
        if (presentationState.coverSurface) {
            Box(Modifier.matchParentSize().background(Color.Black))
        }
    }
}

/**
 * 시청 화면. YouTube처럼 위쪽에 영상(음원은 먹 그림)을 화면 폭 가득 두고, 아래에 제목과 조작을 둔다.
 * 화면만 그린다. 상태는 모두 바깥에서 받는다 (그래서 Preview에서도 보인다).
 */
@Composable
fun PlayerContent(
    artSeed: String,
    title: String,
    creator: String,
    aiGenerated: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
    modifier: Modifier = Modifier,
    subscribed: Boolean = false,
    saved: Boolean = false,
    onToggleSubscribe: () -> Unit = {},
    onToggleSave: () -> Unit = {},
    /** 재생에 실패한 상태인가 */
    hasError: Boolean = false,
    onRetry: () -> Unit = {},
    /** 영상일 때만 넘긴다. 오디오면 null (먹 그림을 보여 준다) */
    video: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (video != null) video() else InkArt(seed = artSeed, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CreatorMark(creator, size = 32.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = creator,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                SubscribeButton(subscribed = subscribed, onClick = onToggleSubscribe)
                Spacer(Modifier.width(10.dp))
                // 모든 콘텐츠에 AI 생성 여부를 표시한다. 낙관(도장)처럼 얇은 먹 테두리로.
                if (aiGenerated) {
                    Text(
                        text = "AI 생성",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .border(1.dp, Ink, RoundedCornerShape(2.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            InkSeekBar(
                fraction = fraction,
                enabled = durationMs > 0,
                onSeek = { f -> onSeekTo((f * durationMs).toLong()) },
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val timeStyle = MaterialTheme.typography.labelSmall
                Text(formatTime(positionMs), style = timeStyle, color = Ash)
                Text(formatTime(durationMs), style = timeStyle, color = Ash)
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onSeekBy(-SEEK_STEP_MS) }) { Text("−10초", color = MaterialTheme.colorScheme.onSurface) }
                Spacer(Modifier.width(12.dp))
                PlayPauseButton(isPlaying = isPlaying, onClick = onTogglePlay, diameter = 64.dp)
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { onSeekBy(SEEK_STEP_MS) }) { Text("+10초", color = MaterialTheme.colorScheme.onSurface) }
            }

            if (hasError) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "재생할 수 없습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = onRetry) { Text("다시 시도", color = MaterialTheme.colorScheme.onSurface) }
                }
            }

            Spacer(Modifier.height(20.dp))
            InkDivider()
            Spacer(Modifier.height(4.dp))

            // 신고·차단은 눈에 띄지 않게, 하지만 언제든 찾을 수 있게 아래쪽에 둔다
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onToggleSave) { Text(if (saved) "저장됨" else "저장", color = MaterialTheme.colorScheme.onSurface) }
                TextButton(onClick = onReport) { Text("신고", color = Ash) }
                TextButton(onClick = onBlock) { Text("크리에이터 차단", color = Ash) }
            }
        }
    }
}

/**
 * 한 획으로 그은 먹 진행선. 지나온 부분은 먹이 번지듯 굵어지고, 끝에는 먹점이 있다.
 * 눌러서(탭) 또는 끌어서 이동하며, 화면 낭독기에서는 진행바로 읽힌다.
 */
@Composable
private fun InkSeekBar(fraction: Float, enabled: Boolean, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { onSeek((it.x / widthPx).coerceIn(0f, 1f)) }
            }
            .pointerInput(enabled) {
                if (enabled) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        onSeek((change.position.x / widthPx).coerceIn(0f, 1f))
                    }
                }
            }
            .semantics {
                contentDescription = "재생 위치"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                if (enabled) setProgress { target -> onSeek(target.coerceIn(0f, 1f)); true }
            },
    ) {
        val y = size.height / 2f
        // 바탕: 아직 지나지 않은 부분 (옅은 먹선)
        drawLine(Mist, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        if (enabled && fraction > 0f) {
            val x = size.width * fraction
            val half = 2.4.dp.toPx()
            // 지나온 부분: 시작은 가늘고 갈수록 굵어지는 붓 자국
            val stroke = Path().apply {
                moveTo(0f, y)
                quadraticTo(x * 0.5f, y - half * 0.7f, x, y - half)
                lineTo(x, y + half)
                quadraticTo(x * 0.5f, y + half * 0.7f, 0f, y)
                close()
            }
            drawPath(stroke, Ink)
            drawCircle(Ink, radius = 6.dp.toPx(), center = Offset(x, y))
        }
    }
}

/** 검은 원 안에 재생/일시정지 표시. 미니 플레이어에서도 크기만 바꿔 쓴다. */
@Composable
internal fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit, diameter: Dp = 72.dp) {
    val label = if (isPlaying) "일시정지" else "재생"
    val fill = MaterialTheme.colorScheme.primary
    val mark = MaterialTheme.colorScheme.onPrimary
    Box(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(diameter)) {
            drawCircle(color = fill)
            val s = size.minDimension
            if (isPlaying) {
                // 일시정지: 세로 막대 둘
                val barW = s * 0.07f
                val barH = s * 0.32f
                val top = (s - barH) / 2
                drawRect(mark, Offset(s / 2 - barW * 1.8f, top), androidx.compose.ui.geometry.Size(barW, barH))
                drawRect(mark, Offset(s / 2 + barW * 0.8f, top), androidx.compose.ui.geometry.Size(barW, barH))
            } else {
                // 재생: 오른쪽을 향한 삼각형 (시각적 중심을 맞추려고 살짝 오른쪽으로)
                val w = s * 0.26f
                val h = s * 0.32f
                val left = s / 2 - w / 2 + s * 0.02f
                val top = (s - h) / 2
                val path = Path().apply {
                    moveTo(left, top)
                    lineTo(left + w, s / 2)
                    lineTo(left, top + h)
                    close()
                }
                drawPath(path, mark)
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun PlayerContentPreview() {
    AudioAppTheme {
        PlayerContent(
            artSeed = "sample-1",
            title = "첫 번째 곡",
            creator = "creator_01",
            aiGenerated = true,
            isPlaying = false,
            positionMs = 72_000,
            durationMs = 225_000,
            onTogglePlay = {},
            onSeekTo = {},
            onSeekBy = {},
            onReport = {},
            onBlock = {},
        )
    }
}
