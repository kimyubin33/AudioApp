package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme

/** 홈 화면 아래, 탭 막대 바로 위에 붙는 작은 플레이어. 누르면 시청 화면으로 이동한다. */
@Composable
fun MiniPlayer(
    artSeed: String,
    isVideo: Boolean,
    title: String,
    subtitle: String,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        InkDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "플레이어 열기", onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InkArt(
                seed = artSeed,
                isVideo = isVideo,
                modifier = Modifier.width(84.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            PlayPauseButton(isPlaying = isPlaying, onClick = onTogglePlay, diameter = 42.dp)
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun MiniPlayerPreview() {
    AudioAppTheme {
        MiniPlayer(
            artSeed = "sample-2",
            isVideo = false,
            title = "파리의 재즈",
            subtitle = "creator_01 · AI 생성",
            isPlaying = true,
            onTogglePlay = {},
            onOpen = {},
        )
    }
}
