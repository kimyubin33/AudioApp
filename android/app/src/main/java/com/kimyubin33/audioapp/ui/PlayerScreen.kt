package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import kotlinx.coroutines.delay

/** 플레이어와 연결된 화면. 재생 상태를 읽어서 [PlayerContent]에 넘긴다. */
@Composable
fun PlayerScreen(
    player: Player,
    title: String,
    creator: String,
    aiGenerated: Boolean,
    onReport: () -> Unit,
    onBlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var positionMs by remember { mutableLongStateOf(player.currentPosition) }
    var durationMs by remember { mutableLongStateOf(player.duration.coerceAtLeast(0L)) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
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
        onReport = onReport,
        onBlock = onBlock,
        modifier = modifier,
    )
}

/** 화면만 그린다. 상태는 모두 바깥에서 받는다 (그래서 Preview에서도 보인다). */
@Composable
fun PlayerContent(
    title: String,
    creator: String,
    aiGenerated: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 모든 콘텐츠에 AI 생성 여부를 표시한다
        if (aiGenerated) {
            Text(
                text = "AI 생성",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(24.dp))
        }

        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = creator,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(48.dp))

        val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        Slider(
            value = fraction,
            onValueChange = { onSeekTo((it * durationMs).toLong()) },
            enabled = durationMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledThumbColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledActiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val timeStyle = MaterialTheme.typography.labelSmall
            val timeColor = MaterialTheme.colorScheme.onSurfaceVariant
            Text(formatTime(positionMs), style = timeStyle, color = timeColor)
            Text(formatTime(durationMs), style = timeStyle, color = timeColor)
        }

        Spacer(Modifier.height(32.dp))

        PlayPauseButton(isPlaying = isPlaying, onClick = onTogglePlay)

        Spacer(Modifier.height(48.dp))

        // 신고·차단은 눈에 띄지 않게, 하지만 언제든 찾을 수 있게 아래쪽에 둔다
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val color = MaterialTheme.colorScheme.onSurfaceVariant
            TextButton(onClick = onReport) { Text("신고", color = color) }
            TextButton(onClick = onBlock) { Text("크리에이터 차단", color = color) }
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

@Preview(showBackground = true)
@Composable
private fun PlayerContentPreview() {
    AudioAppTheme {
        PlayerContent(
            title = "첫 번째 곡",
            creator = "creator_01",
            aiGenerated = true,
            isPlaying = false,
            positionMs = 72_000,
            durationMs = 225_000,
            onTogglePlay = {},
            onSeekTo = {},
            onReport = {},
            onBlock = {},
        )
    }
}
