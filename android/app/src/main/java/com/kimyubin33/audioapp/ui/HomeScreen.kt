package com.kimyubin33.audioapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kimyubin33.audioapp.ui.theme.AudioAppTheme
import com.kimyubin33.audioapp.ui.theme.BrushFont
import com.kimyubin33.audioapp.ui.theme.Ink
import com.kimyubin33.audioapp.ui.theme.Paper

/** 목록 한 줄에 필요한 정보만 담은 화면용 모델 (Rust·네트워크와 무관해서 Preview에서도 쓸 수 있다). */
data class HomeItem(
    val id: String,
    val title: String,
    val creator: String,
    val aiGenerated: Boolean,
    val isVideo: Boolean = false,
)

/** "creator · 영상 · AI 생성" 처럼 보조 정보를 한 줄로. */
internal fun metaLine(creator: String, isVideo: Boolean, aiGenerated: Boolean): String =
    listOfNotNull(creator, if (isVideo) "영상" else null, if (aiGenerated) "AI 생성" else null).joinToString(" · ")

/** 피드. YouTube 모바일처럼 큰 썸네일 카드를 위에서 아래로 쌓는다. 오디오 탭과 구독 탭이 함께 쓴다. */
@Composable
fun FeedScreen(
    /** 맨 위에 붓글씨로 쓰는 제목 */
    title: String,
    /** 목록이 비었을 때 보여 줄 안내 */
    emptyMessage: String,
    items: List<HomeItem>,
    loading: Boolean,
    error: String?,
    hasMore: Boolean,
    /** 곡을 열다 실패했을 때 등, 잠깐 알려줄 문구 */
    notice: String?,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
            )
            InkDivider(Modifier.padding(bottom = 16.dp))
        }
        if (notice != null) {
            item {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }

        if (items.isEmpty()) {
            // 목록이 비어 있을 때: 불러오는 중이거나, 실패했거나
            item {
                when {
                    error != null -> StatusMessage(message = error, actionLabel = "다시 시도", onAction = onRetry)
                    loading -> StatusMessage(message = "불러오는 중...")
                    else -> StatusMessage(message = emptyMessage)
                }
            }
        } else {
            items(items, key = { it.id }) { item -> FeedCard(item = item, onOpen = onOpen) }
            // 목록 끝: 더 불러오는 중이거나, 실패했거나, 더 있거나
            item {
                when {
                    loading -> StatusMessage(message = "불러오는 중...")
                    error != null -> StatusMessage(message = error, actionLabel = "다시 시도", onAction = onRetry)
                    hasMore -> StatusMessage(message = null, actionLabel = "더 보기", onAction = onLoadMore)
                }
            }
        }
    }
}

/** 큰 먹 그림 썸네일 + 크리에이터 표식 + 제목·보조 정보. */
@Composable
private fun FeedCard(item: HomeItem, onOpen: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "재생") { onOpen(item.id) }
            .padding(bottom = 22.dp),
    ) {
        Box {
            InkArt(seed = item.id, isVideo = item.isVideo, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            if (item.isVideo) PlayBadge(Modifier.align(Alignment.BottomEnd).padding(12.dp))
        }
        Row(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            CreatorMark(item.creator)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = metaLine(item.creator, item.isVideo, item.aiGenerated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 크리에이터를 나타내는 먹빛 낙관(도장) 같은 동그라미. 크리에이터 id 끝 두 글자를 붓글씨로 새긴다. */
@Composable
internal fun CreatorMark(creator: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(Ink),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = creator.takeLast(2),
            color = Paper,
            fontFamily = BrushFont,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** 영상임을 알리는 작은 검은 동그라미 + 흰 삼각형. */
@Composable
private fun PlayBadge(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(30.dp)) {
        drawCircle(Ink.copy(alpha = 0.92f))
        val s = size.minDimension
        val path = Path().apply {
            moveTo(s * 0.40f, s * 0.30f)
            lineTo(s * 0.72f, s * 0.50f)
            lineTo(s * 0.40f, s * 0.70f)
            close()
        }
        drawPath(path, Color.White)
    }
}

@Composable
private fun StatusMessage(
    message: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)) {
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

private val previewItems = listOf(
    HomeItem("sample-1", "샘플 오디오 하나", "creator_01", true),
    HomeItem("sample-4", "짧은 영상 샘플", "creator_01", true, isVideo = true),
    HomeItem("sample-2", "파리의 재즈", "creator_02", true),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun FeedScreenPreview() {
    AudioAppTheme {
        FeedScreen(
            title = "AudioApp", emptyMessage = "",
            items = previewItems, loading = false, error = null, hasMore = true, notice = null,
            onOpen = {}, onRetry = {}, onLoadMore = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 400)
@Composable
private fun FeedScreenErrorPreview() {
    AudioAppTheme {
        FeedScreen(
            title = "구독", emptyMessage = "",
            items = emptyList(), loading = false, error = "서버에 연결할 수 없습니다. 연결을 확인해 주세요.",
            hasMore = false, notice = null, onOpen = {}, onRetry = {}, onLoadMore = {},
        )
    }
}
